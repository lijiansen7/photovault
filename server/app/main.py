"""PhotoVault NAS 服务端

设计要点
--------
1. 原文件字节级原样落盘（不重编码、不压缩、不剥离 EXIF）→ 拍摄时间/地点/相机信息天然保留。
2. 抽取的元数据只进 SQLite 索引，用于检索与分组，不回写文件。
3. 内容级去重：同一份字节在盘上只存一份；不同用户各自持有一条记录，互不可见。
4. 用户体系：账号密码登录换取签名 token，普通用户只看自己的，管理员看全部。
   旧的单一 ACCESS_TOKEN 作为兼容通道保留，可用 ALLOW_LEGACY_TOKEN=0 关掉。
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import shutil
import time
import uuid
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response

from . import auth, db, media
from .auth import ROLE_ADMIN
from .config import (
    ACCESS_TOKEN, ALLOW_ANY_FILE, CHUNK_SIZE, ENABLE_THUMB, IMAGE_EXT, MAX_UPLOAD_MB,
    PHOTO_DIR, TMP_DIR, URL_PREFIX, VIDEO_EXT, guess_mime, kind_of,
)

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("photovault")

app = FastAPI(title="PhotoVault", version="1.1.0")


@app.middleware("http")
async def _security_headers(request: Request, call_next):
    """给所有响应补上安全头。

    这些头是"默认不设就没有"的，成本几乎为零：
    - nosniff：别让浏览器猜 MIME 类型（防止把图片当脚本执行）
    - X-Frame-Options / frame-ancestors：禁止被别的页面用 iframe 套起来（点击劫持）
    - Referrer-Policy：缩略图 URL 上带着 token，别让它随 Referer 漏到外站
    - CSP：只允许加载自己的资源；因为页面是内联 style/script，得放行 inline
    """
    # 挂在子路径下时（飞牛统一网关：/app/photovault），进来的路径带着前缀，
    # 这里先剥掉再交给路由，否则 /app/photovault 会 404。
    if URL_PREFIX:
        _p = request.scope.get("path", "")
        if _p == URL_PREFIX:
            request.scope["path"] = "/"
        elif _p.startswith(URL_PREFIX + "/"):
            request.scope["path"] = _p[len(URL_PREFIX):]

    resp = await call_next(request)
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["Referrer-Policy"] = "no-referrer"
    resp.headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=()"
    # 走统一网关时，页面是被飞牛桌面用 iframe 嵌进去的（同源）。
    # 这时必须放行同源框架，否则桌面里点开应用就是一片空白。
    if URL_PREFIX:
        resp.headers["X-Frame-Options"] = "SAMEORIGIN"
        _frame = "frame-ancestors 'self'"
    else:
        resp.headers["X-Frame-Options"] = "DENY"
        _frame = "frame-ancestors 'none'"
    resp.headers["Content-Security-Policy"] = (
        "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; "
        "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
        f"connect-src 'self'; {_frame}; base-uri 'none'; form-action 'none'"
    )
    # 只有真的走了 HTTPS 才发 HSTS，不然明文访问会被锁死
    if request.url.scheme == "https":
        resp.headers["Strict-Transport-Security"] = "max-age=31536000"
    return resp

# 旧的单一 Token 通道。**默认关闭** —— App 已经改成只能账号登录，
# 留着它等于留一个"用一个公开字符串就能拿管理员权限"的后门。
# 真需要应急时用环境变量显式打开。
ALLOW_LEGACY_TOKEN = os.getenv("ALLOW_LEGACY_TOKEN", "0") not in ("0", "false", "False", "")


# ------------------------------------------------------------------ 身份 -----

class Principal:
    """当前请求者。legacy=True 表示用的是旧的单一 Token，按管理员对待。"""

    def __init__(self, user: dict | None = None, legacy: bool = False):
        self.user = user
        self.legacy = legacy

    @property
    def is_admin(self) -> bool:
        return self.legacy or (self.user is not None and self.user["role"] == ROLE_ADMIN)

    @property
    def uid(self) -> str | None:
        return self.user["id"] if self.user else None

    @property
    def label(self) -> str:
        if self.legacy:
            return "兼容 Token"
        return self.user["username"] if self.user else "?"

    def scope(self, owner: str | None = None) -> str | None:
        """用于 SQL 过滤的 owner_id：管理员给 None 表示看全部。"""
        if self.is_admin:
            return owner or None
        return self.uid

    def can_touch(self, photo: dict | None) -> bool:
        if photo is None:
            return False
        if self.is_admin:
            return True
        return photo.get("owner_id") == self.uid


def _boot() -> None:
    db.init_db()
    auth.init_users_table()
    boot = auth.ensure_bootstrap_admin()
    if boot:
        username, password = boot
        admin = auth.get_user_by_name(username)
        claimed = db.claim_orphan_photos(admin["id"]) if admin else 0
        log.warning("=" * 62)
        log.warning("已创建初始管理员：%s / %s", username, password)
        log.warning("登录后请立刻改密码（网页端「维护」页可以改）。")
        if claimed:
            log.warning("升级前上传的 %d 张照片已归入该管理员名下。", claimed)
        log.warning("=" * 62)
        return

    # 早就建过，但密码还是公开的默认值 —— 等于没设防，逼着改掉
    name = os.getenv("INIT_ADMIN_USER", "admin")
    row = auth.get_user_by_name(name)
    if row and not row["must_change_password"] and auth.verify_password(ACCESS_TOKEN, row["password_hash"]):
        auth.require_password_change(row["id"])
        log.warning("管理员 %s 仍在使用初始密码，已要求下次登录时修改。", name)


# 幂等初始化：ASGI 服务器不触发 startup 事件时也能用
_boot()


def _cleanup_tmp(max_age_hours: int = 24) -> int:
    """清掉 TMP_DIR 里残留的 .part 半截文件。

    正常情况下上传中断由 _save_stream 的 except 分支删除临时文件；但进程被
    SIGKILL / 断电时 except 不会执行，会留下垃圾。半截文件只存在于 tmp、
    **永远不会进正式目录**（完整才算完 SHA、才原子改名），所以清掉是安全的。
    只删超过 max_age_hours 的，避免误删正在写入中的上传。
    """
    import time
    n = 0
    cutoff = time.time() - max_age_hours * 3600
    try:
        for f in TMP_DIR.iterdir():
            try:
                if f.is_file() and f.stat().st_mtime < cutoff:
                    f.unlink(missing_ok=True)
                    n += 1
            except OSError:
                continue
    except OSError:
        pass
    return n


@app.on_event("startup")
def _startup() -> None:
    _boot()
    stale = _cleanup_tmp()
    if stale:
        log.info("cleaned %d stale .part files from tmp", stale)
    log.info("PhotoVault ready. photo dir = %s", PHOTO_DIR)


def _bearer(x_token: str | None, authorization: str | None, token: str | None) -> str | None:
    supplied = x_token or token
    if not supplied and authorization:
        supplied = authorization[7:] if authorization.lower().startswith("bearer ") else authorization
    return supplied


def require_principal(
    x_token: str | None = Header(default=None, alias="X-Token"),
    authorization: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> Principal:
    raw = _bearer(x_token, authorization, token)
    if not raw:
        raise HTTPException(status_code=401, detail="missing token")

    if ALLOW_LEGACY_TOKEN and hmac.compare_digest(raw, ACCESS_TOKEN):
        return Principal(legacy=True)

    user = auth.resolve_token(raw)
    if not user:
        raise HTTPException(status_code=401, detail="bad or expired token")
    return Principal(user)


def require_admin(p: Principal = Depends(require_principal)) -> Principal:
    if not p.is_admin:
        raise HTTPException(status_code=403, detail="admin only")
    return p


# -------------------------------------------------------------- 存储布局 -----

def _dest_path(sha256: str, filename: str) -> tuple[Path, str]:
    ext = os.path.splitext(filename)[1].lower() or ".bin"
    rel = f"{sha256[:2]}/{sha256[2:4]}/{sha256}{ext}"
    return PHOTO_DIR / rel, rel


def _save_stream(upload: UploadFile) -> tuple[str, int, Path]:
    """边读边算 SHA256 边落临时盘，避免大文件吃内存。"""
    tmp = TMP_DIR / f"{uuid.uuid4().hex}.part"
    h = hashlib.sha256()
    size = 0
    # 0（默认）表示不限制大小；设成正数才会按 MAX_UPLOAD_MB 拒掉超限文件。
    # 注意：不限制意味着一个超大视频就能占满 NAS 磁盘 —— 真要兜底就把
    # MAX_UPLOAD_MB 设成正数，别指望靠它防误操作。
    limit = MAX_UPLOAD_MB * 1024 * 1024 if MAX_UPLOAD_MB > 0 else None
    try:
        with open(tmp, "wb") as f:
            while True:
                chunk = upload.file.read(CHUNK_SIZE)
                if not chunk:
                    break
                size += len(chunk)
                if limit is not None and size > limit:
                    raise HTTPException(413, f"file exceeds {MAX_UPLOAD_MB}MB")
                h.update(chunk)
                f.write(chunk)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise
    return h.hexdigest(), size, tmp


# ------------------------------------------------------------ 认证接口 ------

@app.get("/api/health")
def health():
    return {"ok": True, "ts": int(time.time() * 1000)}


@app.post("/api/ping")
def ping(p: Principal = Depends(require_principal)):
    """手机端「测试连接」。"""
    return {"ok": True, "server": "photovault", "version": "1.1.0", "as": p.label}


@app.post("/api/auth/login")
async def login(request: Request):
    body = await request.json()
    username = str(body.get("username") or "").strip()
    password = str(body.get("password") or "")
    user, err, locked = auth.authenticate(username, password)
    if not user:
        # 锁定用 429，和"密码错"区分开，前端能给出不同的提示
        raise HTTPException(status_code=429 if locked else 401, detail=err)
    row = auth.get_user_by_name(username)
    return {"token": auth.issue_token(row), "user": user}


@app.get("/api/auth/me")
def me(p: Principal = Depends(require_principal)):
    return {"user": p.user, "legacy": p.legacy, "is_admin": p.is_admin}


@app.post("/api/auth/logout")
def logout(p: Principal = Depends(require_principal)):
    """token 是无状态的，服务端只需让当前 token 作废。"""
    if p.user:
        auth.bump_epoch(p.user["id"])
    return {"ok": True}


@app.post("/api/auth/password")
async def change_password(request: Request, p: Principal = Depends(require_principal)):
    if not p.user:
        raise HTTPException(status_code=400, detail="兼容 Token 没有账号，请改用账号密码登录")
    body = await request.json()
    old = str(body.get("old_password") or "")
    new = str(body.get("new_password") or "")
    row = auth.get_user_by_name(p.user["username"])
    if not auth.verify_password(old, row["password_hash"]):
        raise HTTPException(status_code=400, detail="原密码不对")
    try:
        auth.set_password(p.user["id"], new)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    # 改密码会踢掉全部旧 token（包括当前这个），直接补发一个新的，
    # 免得用户改完立刻被登出、还要再登一次
    row = auth.get_user_by_name(p.user["username"])
    return {"ok": True, "token": auth.issue_token(row),
            "detail": "已修改，其他设备需要重新登录"}


# ---------------------------------------------------------- 用户管理 --------

@app.get("/api/admin/users")
def admin_list_users(_: Principal = Depends(require_admin)):
    return {"items": auth.list_users()}


@app.post("/api/admin/users")
async def admin_create_user(request: Request, _: Principal = Depends(require_admin)):
    body = await request.json()
    try:
        u = auth.create_user(
            str(body.get("username") or ""),
            str(body.get("password") or ""),
            str(body.get("role") or "user"),
            str(body.get("display_name") or ""),
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"user": u}


@app.patch("/api/admin/users/{uid}")
async def admin_update_user(uid: str, request: Request, _: Principal = Depends(require_admin)):
    body = await request.json()
    fields = {}
    if "role" in body:
        fields["role"] = body["role"]
    if "disabled" in body:
        fields["disabled"] = bool(body["disabled"])
    if "display_name" in body:
        fields["display_name"] = body["display_name"]
    if fields:
        auth.update_user(uid, **fields)
        if body.get("disabled") is True:
            auth.bump_epoch(uid)          # 立刻踢下线
    return {"user": auth.get_user(uid)}


@app.post("/api/admin/users/{uid}/password")
async def admin_reset_password(uid: str, request: Request, _: Principal = Depends(require_admin)):
    body = await request.json()
    try:
        auth.set_password(uid, str(body.get("new_password") or ""))
        auth.require_password_change(uid)     # 是他被人设的，得自己改一次
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"ok": True}


@app.delete("/api/admin/users/{uid}")
def admin_delete_user(uid: str, p: Principal = Depends(require_admin)):
    if p.uid == uid:
        raise HTTPException(status_code=400, detail="不能删自己")
    # 照片不跟着删，转给操作者，避免误删数据
    with db.connect() as conn:
        conn.execute("UPDATE photos SET owner_id=? WHERE owner_id=?", (p.uid or "", uid))
    if not auth.delete_user(uid):
        raise HTTPException(status_code=404, detail="not found")
    return {"ok": True}


# ------------------------------------------------------------------ 照片 -----

@app.post("/api/photos/exists")
async def photos_exists(request: Request, p: Principal = Depends(require_principal)):
    """批量查重：手机端上传前先问一遍，已存在的直接跳过，省流量。

    body: {"sha256": ["abc...", "def..."]}
    """
    body = await request.json()
    hashes = [h.lower() for h in (body.get("sha256") or []) if h]
    exist = db.find_hashes(hashes, p.scope())
    return {"existing": sorted(exist)}


@app.post("/api/upload")
async def upload(
    file: UploadFile = File(...),
    meta: str = Form(default="{}"),
    device_id: str | None = Form(default=None),
    thumb: UploadFile | None = File(default=None),
    p: Principal = Depends(require_principal),
):
    """上传一张照片/一个视频。

    `meta` 为 JSON 字符串，字段（全部可选）：
        taken_at      int   拍摄时间（毫秒 epoch）
        gps_lat/gps_lon/gps_alt  float
        width/height/orientation int
        camera_make/camera_model str
        album         str   手机上的相册桶名（如 Camera / Screenshots）
        original_path str   手机上的原始路径
    """
    filename = file.filename or "unknown"

    # 只收媒体文件。文件名是客户端说了算的，光靠扩展名判断挡不住有心人，
    # 但至少能拦住"往照片库里塞脚本/可执行文件"这类顺手的事
    if not ALLOW_ANY_FILE:
        ext = os.path.splitext(filename)[1].lower()
        if ext not in IMAGE_EXT and ext not in VIDEO_EXT:
            raise HTTPException(
                status_code=415,
                detail=f"只接受图片和视频（{'无扩展名' if not ext else ext} 不在允许范围内）",
            )

    sha256, size, tmp = _save_stream(file)
    owner = p.uid or ""

    # 1) 自己传过这张：还在就跳过；被删进回收站就复活原来那条
    #    （不能直接插新记录，会撞 (owner_id, sha256) 唯一约束）
    dup = db.find_by_sha(sha256, owner, include_deleted=True)
    if dup and not dup.get("deleted"):
        tmp.unlink(missing_ok=True)
        return {"status": "duplicate", "id": dup["id"], "sha256": sha256,
                "taken_at": dup.get("taken_at"), "has_gps": dup.get("gps_lat") is not None}

    try:
        client_meta = json.loads(meta) if meta else {}
    except json.JSONDecodeError:
        client_meta = {}

    # 2) 别人传过同一份字节，而且**文件确实还在盘上** → 不重复落盘
    #    注意必须确认文件存在：记录可能是悬空的（文件被清空回收站删掉了）
    shared = db.find_by_sha(sha256)
    if shared and shared.get("rel_path") and (PHOTO_DIR / shared["rel_path"]).exists():
        tmp.unlink(missing_ok=True)
        rel_path = shared["rel_path"]
    else:
        dest, rel_path = _dest_path(sha256, filename)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(tmp), str(dest))          # 同一卷内 move 是原子改名

    # 3) 元数据：服务端抽 EXIF，客户端提供的值优先
    extracted = media.extract_metadata(PHOTO_DIR / rel_path, filename)
    merged = media.merge_client_metadata(extracted, client_meta)

    rec = {
        "sha256": sha256,
        "filename": filename,
        "rel_path": rel_path,
        "mime": guess_mime(filename, file.content_type or ""),
        "kind": kind_of(filename, file.content_type or ""),
        "size": size,
        "device_id": device_id or client_meta.get("device_id"),
        "owner_id": owner,
        **{k: merged.get(k) for k in (
            "taken_at", "uploaded_at", "meta_source", "gps_lat", "gps_lon", "gps_alt",
            "width", "height", "orientation", "camera_make", "camera_model",
            "album", "original_path", "rel_dir")},
    }
    rec["uploaded_at"] = rec.get("uploaded_at") or int(time.time() * 1000)

    if dup:                       # 从回收站复活
        db.revive_photo(dup["id"], rec)
        photo_id, status = dup["id"], "restored"
    else:
        photo_id, status = db.insert_photo(rec), "created"

    # 4) 客户端抽好的封面帧（主要是视频）：服务端没 ffmpeg 解不了视频帧，
    #    存下之后 /api/photos/{id}/thumb 就会先命中这个文件直接返回。
    #    已经有缩略图的就不再覆盖（图片是服务端自己生成的，够用）。
    if thumb is not None:
        try:
            data = await thumb.read()
        except Exception:
            data = b""
        if data and not media.thumbnail_path(photo_id).exists():
            media.save_thumbnail_bytes(photo_id, data)

    return {"status": status, "id": photo_id, "sha256": sha256,
            "taken_at": rec.get("taken_at"), "meta_source": rec.get("meta_source"),
            "has_gps": rec.get("gps_lat") is not None}


@app.get("/api/photos")
def list_photos(
    limit: int = Query(200, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    kind: str | None = Query(None),
    album: str | None = Query(None),
    device_id: str | None = Query(None),
    taken_from: int | None = Query(None),
    taken_to: int | None = Query(None),
    has_gps: bool | None = Query(None),
    order: str = Query("taken_at DESC"),
    owner: str | None = Query(None),
    folder: str | None = Query(None),
    p: Principal = Depends(require_principal),
):
    items, total = db.list_photos(limit, offset, kind, album, device_id,
                                  taken_from, taken_to, has_gps, order, p.scope(owner),
                                  folder)
    return {"total": total, "offset": offset, "limit": limit, "items": items}


def _load(photo_id: str, p: Principal) -> dict:
    photo = db.get_photo(photo_id)
    if not photo or not p.can_touch(photo):
        raise HTTPException(404, "not found")
    return photo


@app.get("/api/photos/{photo_id}")
def photo_meta(photo_id: str, p: Principal = Depends(require_principal)):
    return _load(photo_id, p)


@app.get("/api/photos/{photo_id}/thumb")
def photo_thumb(photo_id: str, p: Principal = Depends(require_principal)):
    photo = _load(photo_id, p)
    if not ENABLE_THUMB:
        raise HTTPException(404, "thumbnail disabled")
    data = media.get_or_make_thumbnail(photo_id, PHOTO_DIR / photo["rel_path"], photo["filename"])
    if data is None:
        raise HTTPException(404, "no thumbnail available")
    return Response(content=data, media_type="image/jpeg",
                    headers={"Cache-Control": "public, max-age=31536000"})


@app.get("/api/photos/{photo_id}/file")
def photo_file(photo_id: str, download: bool = Query(False), p: Principal = Depends(require_principal)):
    """下载**原文件**。

    字节与手机上传时完全一致，EXIF 完好 → 手机端写回系统相册后，
    Google Photos / 系统相册读到的拍摄时间与 GPS 与拍摄当天一致。
    """
    photo = _load(photo_id, p)
    src = PHOTO_DIR / photo["rel_path"]
    if not src.exists():
        raise HTTPException(410, "file missing on disk")
    headers = {}
    if download:
        # 文件名来自客户端上传，可能含引号 / 换行 / 路径分隔，直接塞进响应头会有注入风险；
        # 这里剥掉控制字符与路径分隔，再用 RFC 5987 把原始名（含中文）安全地编码出去
        from urllib.parse import quote
        name = photo.get("filename") or "photo"
        safe = "".join(c for c in name if c.isprintable() and c not in '/\\').strip() or "photo"
        ascii_name = "".join(c if 32 <= ord(c) < 127 else "_" for c in safe)
        utf8_name = quote(safe, safe="")
        headers["Content-Disposition"] = (
            f'attachment; filename="{ascii_name}"; filename*=UTF-8\'\'{utf8_name}'
        )
    return FileResponse(src, media_type=photo.get("mime") or guess_mime(photo["filename"]),
                        headers=headers)


@app.delete("/api/photos/{photo_id}")
def delete_photo(photo_id: str, purge: bool = Query(False),
                 p: Principal = Depends(require_principal)):
    """删除 = 进回收站。真要立刻抹掉文件用 purge=true（不推荐）。"""
    _load(photo_id, p)      # 先确认有权限
    owner = p.uid if not p.is_admin else None
    if not db.soft_delete(photo_id, purge, owner):
        raise HTTPException(404, "not found")
    return {"ok": True, "trashed": not purge}


@app.post("/api/photos/delete")
async def delete_many(request: Request, p: Principal = Depends(require_principal)):
    """批量删除（进回收站）。全选删除时用，省得前端发几百个请求。"""
    body = await request.json()
    ids = [str(i) for i in (body.get("ids") or []) if i]
    owner = p.uid if not p.is_admin else None
    deleted = 0
    for pid in ids:
        photo = db.get_photo(pid)
        if photo and p.can_touch(photo) and db.soft_delete(pid, False, owner):
            deleted += 1
    return {"ok": True, "deleted": deleted, "requested": len(ids)}


@app.get("/api/trash")
def list_trash(limit: int = Query(200, ge=1, le=1000), p: Principal = Depends(require_principal)):
    return {"items": db.list_trash(limit, p.scope()), "total": db.count_trash(p.scope())}


@app.post("/api/photos/{photo_id}/restore")
def restore_photo(photo_id: str, p: Principal = Depends(require_principal)):
    owner = p.uid if not p.is_admin else None
    if not db.restore_photo(photo_id, owner):
        raise HTTPException(404, "not found")
    return {"ok": True}


@app.delete("/api/trash")
def purge_trash(p: Principal = Depends(require_principal)):
    n = db.purge_trash(p.uid if not p.is_admin else None)
    return {"ok": True, "removed": n}


@app.post("/api/trash/delete")
async def delete_trash_items(request: Request, p: Principal = Depends(require_principal)):
    """只彻底删掉回收站里**指定的几条**（选择性删除）。

    body: {"ids": ["...", "..."]}

    注意不能复用 DELETE /api/photos/{id}：那条路要先 get_photo()，而它
    `WHERE deleted=0`，对已进回收站的记录直接 404。
    """
    body = await request.json()
    ids = [str(i) for i in (body.get("ids") or []) if i]
    owner = p.uid if not p.is_admin else None
    n = db.purge_trash_ids(ids, owner)
    return {"ok": True, "deleted": n, "requested": len(ids)}


@app.get("/api/admin/integrity")
def integrity(_: Principal = Depends(require_admin)):
    """索引 vs 磁盘：文件是否都在、大小是否一致、有没有孤儿文件。"""
    return db.integrity_report()


@app.post("/api/photos/prescreen")
async def prescreen(request: Request, p: Principal = Depends(require_principal)):
    """上传前的粗筛：只看 size + 拍摄时间 + 文件名，不读文件内容。

    手机端拿它先过一遍，命中直接跳过 —— 增量备份时绝大多数照片都能这样省掉
    一整遍读文件算 SHA256。粗筛可能漏（同尺寸同时间的不同照片），
    所以没命中的还得走 SHA256 精确查重。
    """
    body = await request.json()
    items = body.get("items") or []
    owner = p.scope()
    with db.connect() as conn:
        if owner is None:
            rows = conn.execute(
                "SELECT size, taken_at, filename FROM photos WHERE deleted=0").fetchall()
        else:
            rows = conn.execute(
                "SELECT size, taken_at, filename FROM photos WHERE owner_id=? AND deleted=0",
                (owner,)).fetchall()
    sig = {(r["size"], r["taken_at"], r["filename"]) for r in rows}

    known: list[str] = []
    unknown: list[str] = []
    for it in items:
        key = str(it.get("key") or "")
        hit = (it.get("size"), it.get("taken_at"), it.get("name")) in sig
        (known if hit else unknown).append(key)
    return {"known": known, "unknown": unknown}


@app.get("/api/albums")
def albums(p: Principal = Depends(require_principal)):
    return {"items": db.list_albums(p.scope())}


@app.get("/api/folders")
def folders(p: Principal = Depends(require_principal)):
    """按手机上真实的目录分组（DCIM/Camera、Pictures/WeiXin …），供手机端按文件夹浏览。"""
    return {"items": db.list_folders(p.scope())}


@app.get("/api/calendar")
def calendar(taken_from: int = Query(...), taken_to: int = Query(...),
             p: Principal = Depends(require_principal)):
    return {"items": db.calendar_counts(taken_from, taken_to, p.scope())}


@app.get("/api/stats")
def stats(p: Principal = Depends(require_principal)):
    return db.stats(p.scope())


# --------------------------------------------------------- 网页管理控制台 ----

# 页面版本标记：显示在界面上，一眼就能看出浏览器是不是还在用缓存的旧页面
BUILD_TAG = time.strftime("%m-%d %H:%M")


@app.get("/", response_class=HTMLResponse)
def console():
    # 必须禁用缓存 —— 否则改了页面用户那边还是旧的，
    # 会出现"我明明修好了你却说没好"的错位
    return HTMLResponse(
        CONSOLE_HTML.replace("__BUILD__", BUILD_TAG).replace("__BASE__", URL_PREFIX),
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
            "Expires": "0",
        },
    )


@app.get("/favicon.ico")
def favicon():
    return Response(status_code=204)


CONSOLE_HTML = """<!doctype html><meta charset="utf-8"><title>PhotoVault</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="pv-base" content="__BASE__">
<style>
:root{
  color-scheme:light;
  --bg:#f6f7f9;--surface:#fff;--surface-2:#f0f2f6;
  --text:#191c21;--text-2:#5d646f;--text-3:#8b929d;
  --border:#e6e9ef;--border-2:#d5dae2;
  --brand:#2f6fed;--brand-weak:#e9f0ff;--brand-text:#1d4fd0;
  --danger:#d8443c;--danger-weak:#fdecea;
  --r:12px;--r-sm:8px;--r-lg:16px;
  --shadow:0 1px 2px rgba(16,24,40,.04),0 1px 3px rgba(16,24,40,.05);
  --shadow-lg:0 10px 30px rgba(16,24,40,.14);
}
@media (prefers-color-scheme:dark){:root{
  color-scheme:dark;
  --bg:#0f1216;--surface:#171b21;--surface-2:#1f242c;
  --text:#e4e7eb;--text-2:#a6aeb9;--text-3:#79818d;
  --border:#252b34;--border-2:#333a45;
  --brand:#6ea1ff;--brand-weak:#182742;--brand-text:#a9c8ff;
  --danger:#ff6f66;--danger-weak:#3b1d1a;
  --shadow:0 1px 2px rgba(0,0,0,.35);
  --shadow-lg:0 12px 34px rgba(0,0,0,.5);
}}
*{box-sizing:border-box}
body{font:14px/1.6 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;margin:0;background:var(--bg);color:var(--text);-webkit-font-smoothing:antialiased}
header{background:var(--surface);border-bottom:1px solid var(--border);padding:10px 20px;display:flex;gap:12px;align-items:center;flex-wrap:wrap;position:sticky;top:0;z-index:6}
.logo{display:flex;align-items:center;gap:8px;font-weight:600;font-size:15px}
.logo i{width:22px;height:22px;border-radius:7px;background:var(--brand);display:block;position:relative;box-shadow:0 2px 6px rgba(47,111,237,.35)}
.logo i::after{content:"";position:absolute;left:6px;right:6px;bottom:6px;top:7px;border-radius:3px;background:rgba(255,255,255,.9)}
input,select{padding:7px 11px;border:1px solid var(--border-2);border-radius:var(--r-sm);font-size:14px;background:var(--surface);color:var(--text);transition:border-color .15s,box-shadow .15s}
input:focus,select:focus{outline:0;border-color:var(--brand);box-shadow:0 0 0 3px var(--brand-weak)}
button{padding:7px 14px;border:0;border-radius:var(--r-sm);background:var(--brand);color:#fff;cursor:pointer;font-size:14px;font-weight:500;transition:filter .15s,transform .06s}
button:hover:not(:disabled){filter:brightness(1.07)}
button:active:not(:disabled){transform:translateY(1px)}
button.ghost{background:var(--surface);color:var(--brand-text);border:1px solid var(--border-2)}
button.ghost:hover:not(:disabled){background:var(--brand-weak);border-color:var(--brand)}
button.danger{background:var(--danger)}
button:disabled{opacity:.45;cursor:not-allowed}
main{padding:18px 20px 40px;max-width:1400px;margin:0 auto}
.card{background:var(--surface);border:1px solid var(--border);border-radius:var(--r);padding:16px;margin-bottom:14px;box-shadow:var(--shadow)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(158px,1fr));gap:12px}
.daybar{display:flex;align-items:baseline;gap:9px;margin:22px 0 10px;font-size:14px;font-weight:600;color:var(--text);position:sticky;top:56px;z-index:3;background:var(--bg);padding:8px 0 6px}
.daybar:first-child{margin-top:4px}
.daybar span{font-size:12px;font-weight:400;color:var(--text-3)}
#g{padding:0 0 20px}
.cell{position:relative;aspect-ratio:1;background:var(--surface-2);border-radius:var(--r);overflow:hidden;transition:transform .18s ease,box-shadow .18s ease}
.cell:hover{transform:translateY(-2px);box-shadow:var(--shadow-lg)}
.cell img{width:100%;height:100%;object-fit:cover;display:block;cursor:zoom-in}
.cell.trashed img{opacity:.55;filter:grayscale(.45)}
.cell.noimg::after{content:"缩略图不可用";position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:var(--text-3);font-size:12px;text-align:center;padding:8px}
.tag{position:absolute;left:6px;bottom:6px;background:rgba(0,0,0,.62);color:#fff;font-size:11px;padding:2px 8px;border-radius:20px}
.badge{position:absolute;top:6px;left:6px;width:22px;height:22px;border-radius:50%;background:rgba(0,0,0,.55);color:#fff;display:flex;align-items:center;justify-content:center;font-size:9px}
.pick{position:absolute;top:6px;right:6px;width:19px;height:19px;accent-color:var(--brand);cursor:pointer}
.pill{display:inline-block;padding:2px 9px;border-radius:20px;font-size:11px;background:var(--surface-2);color:var(--text-2)}
.pill.admin{background:var(--brand-weak);color:var(--brand-text)}
.pill.off{background:var(--danger-weak);color:var(--danger)}
table{width:100%;border-collapse:collapse}
th,td{padding:9px 10px;text-align:left;border-bottom:1px solid var(--border);font-size:13px}
th{color:var(--text-2);font-weight:500}
tr:last-child td{border-bottom:0}
.muted{color:var(--text-2);font-size:12px}
.login{max-width:360px;margin:11vh auto;background:var(--surface);border:1px solid var(--border);border-radius:var(--r-lg);padding:28px;box-shadow:var(--shadow-lg)}
.login h1{margin:0 0 4px;font-size:20px;font-weight:600}
.login input{width:100%;margin-bottom:10px}
.login button{width:100%;margin-top:4px}
.err{color:var(--danger);font-size:13px;min-height:20px}
.tabs{display:flex;gap:4px;background:var(--surface-2);padding:3px;border-radius:10px}
.tab{padding:6px 15px;border-radius:8px;cursor:pointer;color:var(--text-2);font-size:14px;transition:background .15s,color .15s}
.tab:hover{color:var(--text)}
.tab.on{background:var(--surface);color:var(--text);font-weight:500;box-shadow:var(--shadow)}
.hide{display:none}
.empty{text-align:center;color:var(--text-3);padding:64px 20px;font-size:13px;line-height:1.9}
.empty b{display:block;color:var(--text-2);font-size:15px;font-weight:500;margin-bottom:2px}
@media (max-width:640px){
  main{padding:12px 12px 32px}
  header{padding:10px 12px;gap:8px}
  .grid{grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:8px}
  .daybar{top:0;margin:16px 0 8px}
  .logo span{display:none}
  .card{padding:13px}
}
</style>

<div id="login" class="login hide">
  <div class="logo" style="margin-bottom:14px"><i></i><span>PhotoVault</span></div>
  <h1>登录</h1>
  <p class="muted" style="margin:0 0 16px">用 NAS 上的账号登录。手机 App 也用同一组账号。</p>
  <input id="lu" placeholder="用户名" autocomplete="username">
  <input id="lp" type="password" placeholder="密码" autocomplete="current-password">
  <div class="err" id="lerr"></div>
  <button onclick="doLogin()">登录</button>
  <p class="muted" style="font-size:11px;margin-bottom:0;margin-top:14px;text-align:center">页面版本 __BUILD__</p>
</div>

<div id="app" class="hide">
  <header>
    <div class="logo"><i></i><span>PhotoVault</span></div>
    <div class="tabs">
      <div class="tab on" id="tab-photos" onclick="show('photos')">照片</div>
      <div class="tab" id="tab-users" onclick="show('users')">用户</div>
      <div class="tab" id="tab-maint" onclick="show('maint')">维护</div>
    </div>
    <span style="flex:1"></span>
    <span class="muted" id="who"></span>
    <button class="ghost" onclick="doLogout()">退出</button>
    <span class="muted" id="build" style="font-size:11px;opacity:.7" title="页面构建时间，用来确认不是缓存的旧版">__BUILD__</span>
  </header>
  <main>
    <section id="v-photos">
      <div class="card">
        <div id="stats" class="muted" style="margin-bottom:10px;font-size:13px"></div>
        <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
          <input id="q" placeholder="搜索文件名 / 相册 / 设备" style="width:230px" oninput="loadPhotos()">
          <select id="ownerSel" onchange="loadPhotos()"><option value="">全部用户</option></select>
          <select id="kindSel" onchange="loadPhotos()">
            <option value="">全部类型</option><option value="image">照片</option><option value="video">视频</option>
          </select>
          <span style="flex:1"></span>
          <span class="muted" id="st"></span>
          <button class="ghost" id="trashBtn" onclick="toggleTrash()">回收站</button>
          <button class="ghost" id="selBtn" onclick="toggleSel()">批量选择</button>
          <button class="ghost hide" id="allBtn" onclick="selectAll()">全选</button>
          <button class="danger hide" id="delBtn" onclick="deleteSelected()">删除所选</button>
        </div>
      </div>
      <div id="g"></div>
      <p class="muted" id="more"></p>
    </section>

    <section id="v-users" class="hide">
      <div class="card">
        <b>新建用户</b>
        <div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:10px">
          <input id="nu" placeholder="用户名">
          <input id="np" placeholder="初始密码" type="text">
          <input id="nd" placeholder="显示名（可空）">
          <select id="nr"><option value="user">普通用户</option><option value="admin">管理员</option></select>
          <button onclick="createUser()">创建</button>
        </div>
        <p class="muted">手机 App 里填服务器地址 + 这组账号密码即可登录备份。</p>
      </div>
      <div class="card">
        <table><thead><tr><th>用户</th><th>角色</th><th>状态</th><th>最近登录</th><th>操作</th></tr></thead>
        <tbody id="ut"></tbody></table>
      </div>
    </section>

    <section id="v-maint" class="hide">
      <div class="card">
        <b>完整性巡检</b>
        <p class="muted">比对索引与磁盘：文件是否都还在、大小是否一致、有没有没人认领的孤儿文件。</p>
        <button onclick="runCheck()">开始巡检</button>
        <div id="chk" class="muted" style="margin-top:10px"></div>
      </div>
      <div class="card">
        <b>回收站</b>
        <p class="muted">删除的照片先进回收站，可以恢复。清空后文件才会真正从磁盘删除。</p>
        <button class="danger" onclick="purgeTrash()">清空回收站</button>
      </div>
      <div class="card">
        <b>改自己的密码</b>
        <div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:10px">
          <input id="opw" type="password" placeholder="当前密码">
          <input id="npw1" type="password" placeholder="新密码">
          <input id="npw2" type="password" placeholder="再输一次">
          <button onclick="changePw()">修改</button>
        </div>
        <p class="muted" id="pwmsg"></p>
      </div>
    </section>
  </main>
</div>

<div id="force" class="login hide">
  <h1>请先修改密码</h1>
  <p class="muted">当前用的是初始密码，或别人代为重置的密码。改掉它才能继续。</p>
  <input id="fop" type="password" placeholder="当前密码" autocomplete="current-password">
  <input id="fnp1" type="password" placeholder="新密码（至少 4 位）" autocomplete="new-password">
  <input id="fnp2" type="password" placeholder="再输一次新密码" autocomplete="new-password">
  <div class="err" id="ferr"></div>
  <button onclick="doForceChange()">修改并继续</button>
</div>

<div id="viewer" onclick="closeViewer()"
     style="display:none;position:fixed;inset:0;background:rgba(0,0,0,.82);align-items:center;justify-content:center;z-index:20">
  <img id="vimg" style="max-width:92%;max-height:92%;border-radius:8px">
</div>

<div id="toast"
     style="display:none;position:fixed;left:50%;bottom:34px;transform:translateX(-50%);background:#1c1e21;color:#fff;padding:10px 18px;border-radius:8px;font-size:13px;z-index:40;max-width:80%;text-align:center"></div>

<div id="modal" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,.45);align-items:center;justify-content:center;z-index:30">
  <div class="card" style="max-width:380px;width:86%;margin:0">
    <p id="modalMsg" style="margin:0 0 16px;font-size:14px;line-height:1.6;white-space:pre-line"></p>
    <div style="display:flex;gap:8px;justify-content:flex-end">
      <button class="ghost" onclick="modalAnswer(false)">取消</button>
      <button class="danger" id="modalOk" onclick="modalAnswer(true)">确定</button>
    </div>
  </div>
</div>

<script>
let TK = localStorage.getItem('pv_token') || '';
// 被挂在子路径下时（飞牛统一网关 /app/photovault），页面里的 /api/... 必须带上前缀，
// 否则请求会打到网关根上变成 404。服务端把前缀写进 meta，这里读出来拼。
let API_BASE = (function () {
  var m = document.querySelector('meta[name="pv-base"]');
  if (!m) return '';
  var s = m.getAttribute('content') || '';
  while (s.length && s.charAt(s.length - 1) === '/') s = s.slice(0, -1);
  return s;
})();
let ME = null;
let ALL = [];
let selecting = false;
let inTrash = false;
const picked = new Set();

// 用自己的弹窗，不用原生 confirm/alert：
// 浏览器在用户点过几次之后会弹「阻止此页面创建更多对话框」，
// 一旦被勾选，confirm 会永远返回 false —— 表现就是「点了删除没反应」。
let modalResolve = null;

function ask(msg, okText) {
  return new Promise(function (resolve) {
    // 已经有一个在问就别叠上来：否则后一个会覆盖前一个的回调，
    // 前一个 Promise 永远挂着，用户点了也没用。
    if (document.getElementById('modal').style.display === 'flex') {
      resolve(false);
      return;
    }
    modalResolve = resolve;
    document.getElementById('modalMsg').innerText = msg;
    document.getElementById('modalOk').textContent = okText || '确定';
    document.getElementById('modal').style.display = 'flex';
  });
}

function modalAnswer(v) {
  document.getElementById('modal').style.display = 'none';
  if (modalResolve) { modalResolve(v); modalResolve = null; }
}

function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.style.display = 'block';
  clearTimeout(window.__toastTimer);
  window.__toastTimer = setTimeout(function () { t.style.display = 'none'; }, 3600);
}

async function api(path, opt = {}) {
  // 注意：不能写成 Object.assign({headers:{Authorization}}, opt)
  // 那样 opt 里的 headers 会整个替换掉基础 headers，Authorization 就丢了。
  const headers = Object.assign({'Authorization': 'Bearer ' + TK}, opt.headers || {});
  const r = await fetch(API_BASE + path, Object.assign({}, opt, {headers: headers}));
  if (r.status === 401) { localStorage.removeItem('pv_token'); location.reload(); throw new Error('401'); }
  if (!r.ok) throw new Error((await r.json().catch(() => ({}))).detail || ('HTTP ' + r.status));
  return r.json();
}

async function boot() {
  if (!TK) { document.getElementById('login').classList.remove('hide'); return; }
  try {
    const d = await api('/api/auth/me');
    ME = d.user;
    if (ME && ME.must_change_password) {
      document.getElementById('force').classList.remove('hide');
      return;
    }
    document.getElementById('app').classList.remove('hide');
    document.getElementById('who').textContent = (d.legacy ? '兼容 Token' : (ME.display_name + '（' + ME.role + '）'));
    if (!d.is_admin) {
      document.getElementById('tab-users').classList.add('hide');
      document.getElementById('tab-maint').classList.add('hide');
    }
    if (d.is_admin) await loadOwners();
    loadPhotos();
    loadStats();
    if (d.is_admin) loadUsers();
  } catch (e) { TK = ''; document.getElementById('login').classList.remove('hide'); }
}

async function doLogin() {
  const u = document.getElementById('lu').value, p = document.getElementById('lp').value;
  document.getElementById('lerr').textContent = '';
  try {
    const r = await fetch(API_BASE + '/api/auth/login', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({username: u, password: p})});
    const d = await r.json();
    if (!r.ok) throw new Error(d.detail || '登录失败');
    TK = d.token; localStorage.setItem('pv_token', TK); location.reload();
  } catch (e) { document.getElementById('lerr').textContent = e.message; }
}

async function doLogout() {
  try { await api('/api/auth/logout', {method: 'POST'}); } catch (e) {}
  localStorage.removeItem('pv_token'); location.reload();
}

function show(v) {
  document.getElementById('v-photos').classList.toggle('hide', v !== 'photos');
  document.getElementById('v-users').classList.toggle('hide', v !== 'users');
  document.getElementById('v-maint').classList.toggle('hide', v !== 'maint');
  document.getElementById('tab-photos').classList.toggle('on', v === 'photos');
  document.getElementById('tab-users').classList.toggle('on', v === 'users');
  document.getElementById('tab-maint').classList.toggle('on', v === 'maint');
}

async function doForceChange() {
  const o = document.getElementById('fop').value;
  const n1 = document.getElementById('fnp1').value;
  const n2 = document.getElementById('fnp2').value;
  const err = document.getElementById('ferr');
  if (n1.length < 4) { err.textContent = '新密码至少 4 位'; return; }
  if (n1 !== n2) { err.textContent = '两次输入的新密码不一致'; return; }
  try {
    const d = await api('/api/auth/password', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({old_password: o, new_password: n1})});
    if (d.token) { TK = d.token; localStorage.setItem('pv_token', TK); }
    location.reload();
  } catch (e) { err.textContent = e.message; }
}

async function changePw() {
  const msg = document.getElementById('pwmsg');
  const n1 = document.getElementById('npw1').value;
  const n2 = document.getElementById('npw2').value;
  if (n1.length < 4) { msg.textContent = '新密码至少 4 位'; return; }
  if (n1 !== n2) { msg.textContent = '两次输入不一致'; return; }
  try {
    const d = await api('/api/auth/password', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({old_password: document.getElementById('opw').value, new_password: n1})});
    if (d.token) { TK = d.token; localStorage.setItem('pv_token', TK); }
    msg.textContent = '已修改，其他设备需要重新登录。';
    document.getElementById('opw').value = document.getElementById('npw1').value = document.getElementById('npw2').value = '';
  } catch (e) { msg.textContent = e.message; }
}

async function runCheck() {
  const el = document.getElementById('chk');
  el.textContent = '巡检中…';
  try {
    const d = await api('/api/admin/integrity');
    el.innerHTML = d.healthy
      ? '一切正常：' + d.total + ' 个文件都在，大小一致，没有孤儿文件。'
      : ('索引 ' + d.total + ' 条<br>缺失文件 ' + d.missing_count
         + '<br>大小不符 ' + d.mismatch_count
         + '<br>孤儿文件 ' + d.orphan_count);
  } catch (e) { el.textContent = '巡检失败：' + e.message; }
}

async function purgeTrash() {
  let n = 0;
  try { n = (await api('/api/trash?limit=1000')).items.length; } catch (e) {}
  if (!n) { toast('回收站是空的'); return; }
  const ok = await ask(
    '清空回收站？\\n\\n'
    + '会把这 ' + n + ' 个原始文件从磁盘上永久删除，找不回来。\\n\\n'
    + '唯一能恢复的办法：手机相册里如果还留着原图，重新备份一次就能回来。',
    '永久删除');
  if (!ok) return;
  try {
    const d = await api('/api/trash', {method: 'DELETE'});
    toast('已彻底清除 ' + d.removed + ' 项');
    if (inTrash) loadPhotos();
    loadStats();
  } catch (e) { toast(e.message); }
}

async function restore(id) {
  try { await api('/api/photos/' + id + '/restore', {method: 'POST'}); loadPhotos(); }
  catch (e) { toast(e.message); }
}

async function loadOwners() {
  try {
    const d = await api('/api/admin/users');
    const sel = document.getElementById('ownerSel');
    d.items.forEach(u => {
      const o = document.createElement('option');
      o.value = u.id; o.textContent = u.display_name + (u.disabled ? '（已禁用）' : '');
      sel.appendChild(o);
    });
  } catch (e) {}
}

function humanSize(n) {
  if (n == null) return '-';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = n, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return (i === 0 ? v : v.toFixed(v >= 100 ? 0 : 1)) + ' ' + u[i];
}

/** 全库统计：照片 / 视频分别多少、占了多大 */
async function loadStats() {
  try {
    const s = await api('/api/stats');
    document.getElementById('stats').innerHTML =
      '<b>' + s.images + '</b> 张照片　<b>' + s.videos + '</b> 个视频　共 <b>'
      + s.count + '</b> 项　' + humanSize(s.bytes)
      + (s.with_gps ? '　含位置 ' + s.with_gps + ' 项' : '');
  } catch (e) { /* 统计失败不影响看照片 */ }
}

async function toggleTrash() {
  inTrash = !inTrash;
  document.getElementById('trashBtn').textContent = inTrash ? '返回照片' : '回收站';
  document.getElementById('delBtn').classList.add('hide');
  document.getElementById('allBtn').classList.add('hide');
  // 回收站里的东西本来就是删过的，在那批量删除会"点了没反应"，干脆不给选
  document.getElementById('selBtn').classList.toggle('hide', inTrash);
  if (inTrash && selecting) { selecting = false; picked.clear(); }
  loadPhotos();
}

async function loadPhotos() {
  if (inTrash) {
    try {
      const d = await api('/api/trash?limit=300');
      document.getElementById('st').textContent = '回收站 ' + d.items.length + ' 项';
      document.getElementById('g').innerHTML = renderByDay(d.items, true);
    } catch (e) { document.getElementById('st').textContent = '加载失败：' + e.message; }
    return;
  }
  const q = document.getElementById('q').value.trim().toLowerCase();
  const owner = document.getElementById('ownerSel').value;
  const kind = document.getElementById('kindSel').value;
  const qs = new URLSearchParams({limit: '300'});
  if (owner) qs.set('owner', owner);
  if (kind) qs.set('kind', kind);
  try {
    const d = await api('/api/photos?' + qs);
    ALL = d.items;
    const items = q ? ALL.filter(p => [p.filename, p.album, p.device_id, p.camera_model]
      .filter(Boolean).join(' ').toLowerCase().includes(q)) : ALL;
    document.getElementById('st').textContent = '共 ' + d.total + ' 项' + (q ? '，筛选后 ' + items.length : '');
    document.getElementById('g').innerHTML = renderByDay(items, false);
  } catch (e) { document.getElementById('st').textContent = '加载失败：' + e.message; }
}

// 按拍摄日期分组，每组一个日期条 + 一片网格
function dayKey(p) {
  const t = p.taken_at || p.uploaded_at || Date.now();
  const d = new Date(t);
  const pad = n => (n < 10 ? '0' + n : '' + n);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function dayLabel(k) {
  const now = new Date();
  const pad = n => (n < 10 ? '0' + n : '' + n);
  const today = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
  const y = new Date(now.getTime() - 86400000);
  const yest = y.getFullYear() + '-' + pad(y.getMonth() + 1) + '-' + pad(y.getDate());
  if (k === today) return '今天';
  if (k === yest) return '昨天';
  const parts = k.split('-');
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][new Date(k + 'T00:00:00').getDay()];
  return parts[0] + '年' + parseInt(parts[1], 10) + '月' + parseInt(parts[2], 10) + '日 ' + week;
}

function renderByDay(items, trashed) {
  if (!items.length) {
    return '<div class="empty"><b>' + (trashed ? '回收站是空的' : '还没有照片') + '</b>'
      + (trashed
          ? '删除的照片会先放到这里，可以随时恢复。'
          : '在手机 App 里登录 NAS、打开「自动备份」，照片就会同步上来。')
      + '</div>';
  }
  const groups = new Map();
  items.forEach(p => {
    const k = dayKey(p);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(p);
  });
  return Array.from(groups.entries()).map(([k, list]) =>
    '<div class="daybar">' + dayLabel(k) + '<span>' + list.length + ' 张</span></div>'
    + '<div class="grid">' + list.map(p => cellHtml(p, trashed)).join('') + '</div>'
  ).join('');
}

function cellHtml(p, trashed) {
  const isVid = p.kind === 'video' || String(p.mime || '').startsWith('video');
  const tags = [];
  if (p.taken_at) tags.push(new Date(p.taken_at).toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit'}));
  if (p.gps_lat != null) tags.push('位置');
  const extra = trashed
    ? '<button class="ghost" style="position:absolute;top:6px;left:6px;padding:3px 10px;font-size:12px" '
      + 'onclick="restore(\\'' + p.id + '\\')">恢复</button>'
    : '';
  return '<div class="cell' + (trashed ? ' trashed' : '') + '" data-id="' + p.id + '">'
    + '<img loading="lazy" src="' + API_BASE + '/api/photos/' + p.id + '/thumb?token=' + encodeURIComponent(TK)
    + '" onclick="view(\\'' + p.id + '\\')"'
    + ' onerror="this.style.display=\\'none\\';this.parentElement.classList.add(\\'noimg\\')">'
    + '<input type="checkbox" class="pick' + (selecting ? '' : ' hide') + '"'
    + (picked.has(p.id) ? ' checked' : '') + ' onchange="pick(this)">'
    + (isVid && !trashed ? '<span class="badge">▶</span>' : '')
    + (tags.length ? '<div class="tag">' + tags.join(' · ') + '</div>' : '')
    + extra + '</div>';
}

// 注意：这层遮罩用 style.display 控制，不能用 class="hide" ——
// 内联的 display:flex 优先级高于类选择器，会把整页盖住并吃掉所有点击。
function view(id) {
  document.getElementById('vimg').src = API_BASE + '/api/photos/' + id + '/file?token=' + encodeURIComponent(TK);
  document.getElementById('viewer').style.display = 'flex';
}

function closeViewer() {
  document.getElementById('viewer').style.display = 'none';
  document.getElementById('vimg').removeAttribute('src');
}

document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeViewer(); });

function toggleSel() {
  selecting = !selecting;
  document.querySelectorAll('.pick').forEach(e => {
    e.classList.toggle('hide', !selecting);
    if (!selecting) e.checked = false;
  });
  document.getElementById('delBtn').classList.toggle('hide', !selecting);
  document.getElementById('allBtn').classList.toggle('hide', !selecting);
  document.getElementById('allBtn').textContent = '全选';
  if (!selecting) picked.clear();
  updateDelBtn();
}

function pick(el) {
  const id = el.parentElement.dataset.id;
  el.checked ? picked.add(id) : picked.delete(id);
  updateDelBtn();
}

/** 按钮上直接显示选中数量，免得点了没反应还不知道为什么 */
function updateDelBtn() {
  const b = document.getElementById('delBtn');
  b.textContent = picked.size ? '删除所选 (' + picked.size + ')' : '删除所选';
}

function selectAll() {
  const boxes = Array.from(document.querySelectorAll('.pick'));
  if (!boxes.length) return;
  const allOn = boxes.every(b => b.checked);
  boxes.forEach(b => { b.checked = !allOn; pick(b); });
  document.getElementById('allBtn').textContent = allOn ? '全选' : '取消全选';
  updateDelBtn();
}

async function deleteSelected() {
  if (!picked.size) {
    toast('还没有勾选照片。先点每张图右上角的方框，或点右边的「全选」。');
    return;
  }
  if (!await ask('删除选中的 ' + picked.size + ' 张？\\n文件会先进回收站，可以随时恢复。', '删除')) return;
  const ids = Array.from(picked);
  document.getElementById('st').textContent = '正在删除 ' + ids.length + ' 项…';
  document.getElementById('delBtn').textContent = '删除中…';
  try {
    const d = await api('/api/photos/delete', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ids: ids}),
    });
    document.getElementById('st').textContent = '已移入回收站 ' + d.deleted + ' 项';
    if (d.deleted === 0) toast('没有删掉任何东西（可能这些照片本来就在回收站里）。');
  } catch (e) { toast('删除失败：' + e.message); }
  picked.clear();
  toggleSel();
  loadPhotos();
  loadStats();
}

async function loadUsers() {
  try {
    const d = await api('/api/admin/users');
    document.getElementById('ut').innerHTML = d.items.map(u =>
      '<tr><td>' + u.display_name + '<div class="muted">' + u.username + '</div></td>'
      + '<td><span class="pill ' + (u.role === 'admin' ? 'admin' : '') + '">' + u.role + '</span></td>'
      + '<td>' + (u.disabled ? '<span class="pill off">已禁用</span>' : '正常') + '</td>'
      + '<td class="muted">' + (u.last_login_at ? new Date(u.last_login_at).toLocaleString() : '从未登录') + '</td>'
      + '<td>' + (u.username === (ME && ME.username) ? '<span class="muted">当前账号</span>' :
        '<button class="ghost" onclick="resetPw(\\'' + u.id + '\\',\\'' + u.username + '\\')">重置密码</button> '
        + '<button class="ghost" onclick="toggleUser(\\'' + u.id + '\\",' + u.disabled + ')">' + (u.disabled ? '启用' : '禁用') + '</button> '
        + '<button class="danger" onclick="delUser(\\'' + u.id + '\\',\\'' + u.username + '\\')">删除</button>')
      + '</td></tr>').join('');
  } catch (e) {}
}

async function createUser() {
  const body = {username: document.getElementById('nu').value, password: document.getElementById('np').value,
    display_name: document.getElementById('nd').value, role: document.getElementById('nr').value};
  if (!body.username || !body.password) { toast('用户名和密码都要填'); return; }
  try { await api('/api/admin/users', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});
    document.getElementById('nu').value = document.getElementById('np').value = document.getElementById('nd').value = '';
    loadUsers(); loadOwners();
  } catch (e) { toast(e.message); }
}

async function resetPw(id) {
  const name = (USERS[id] && USERS[id].username) || id;
  const p = prompt('给 ' + name + ' 设置新密码（至少 4 位）：');
  if (!p) return;
  try { await api('/api/admin/users/' + id + '/password', {method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({new_password: p})});
    toast('已重置。该用户已登录的手机需要重新登录。');
  } catch (e) { toast(e.message); }
}

async function toggleUser(id, disabled) {
  try { await api('/api/admin/users/' + id, {method: 'PATCH', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({disabled: !disabled})}); loadUsers(); } catch (e) { toast(e.message); }
}

async function delUser(id, name) {
  if (!await ask('删除账号「' + name + '」？\\n其照片会转到你名下，不会丢失。', '删除')) return;
  try { await api('/api/admin/users/' + id, {method: 'DELETE'}); loadUsers(); loadOwners(); } catch (e) { toast(e.message); }
}

boot();
</script>"""

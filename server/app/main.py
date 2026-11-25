"""PhotoVault NAS 服务端

设计要点
--------
1. 原文件字节级原样落盘（不重编码、不压缩、不剥离 EXIF）→ 拍摄时间/地点/相机信息天然保留。
2. 抽取的元数据只进 SQLite 索引，用于检索与分组，不回写文件。
3. 内容级去重：同一份字节在盘上只存一份；不同用户各自持有一条记录，互不可见。
4. 用户体系：账号密码登录换取签名 token，普通用户只看自己的，管理员看全部。
   旧的单一 ACCESS_TOKEN 作为兼容通道保留，可用 ALLOW_LEGACY_TOKEN=1 显式打开。
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import re
import shutil
import signal
import sqlite3
import ssl
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import auth, db, media
from .auth import ROLE_ADMIN
from .config import (
    ACCESS_TOKEN, ALLOW_ANY_FILE, CHUNK_SIZE, DATA_DIR, ENABLE_DOCS, ENABLE_THUMB, IMAGE_EXT,
    MAX_UPLOAD_MB, MIN_FREE_MB, PHOTO_DIR, THUMB_SIZE, TMP_DIR, URL_PREFIX,
    USER_QUOTA_GB, VIDEO_EXT, guess_mime, kind_of,
)

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("photovault")

VERSION = "2.0.9"
COOKIE_NAME = "pv_token"
CERT_DIR = DATA_DIR / "certs"
HTTPS_FLAG = CERT_DIR / "https.enabled"
BYTES_PER_MB = 1024 * 1024

# 兼容旧版 App：它们把 token 拼在缩略图/原文件的 URL 上。
# 只对媒体 GET 生效，且默认只保留一个版本；全部手机升级后设 0 彻底关掉。
ALLOW_LEGACY_MEDIA_TOKEN = os.getenv("ALLOW_LEGACY_MEDIA_TOKEN", "1") not in ("0", "false", "False", "")

# 旧的单一 Token 通道。**默认关闭** —— 留着它等于留一个"用一个公开字符串就能
# 拿管理员权限"的后门。真需要应急时用环境变量显式打开。
ALLOW_LEGACY_TOKEN = os.getenv("ALLOW_LEGACY_TOKEN", "0") not in ("0", "false", "False", "")


# ------------------------------------------------------------------ 身份 -----

class Principal:
    """当前请求者。legacy=True 表示用的是旧的单一 Token，按管理员对待。"""

    def __init__(self, user: dict | None = None, legacy: bool = False, raw: str = ""):
        self.user = user
        self.legacy = legacy
        self.raw = raw        # 原始 token，退出登录时用来吊销当前会话

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


def _bearer(x_token: str | None, authorization: str | None) -> str | None:
    if x_token:
        return x_token
    if authorization:
        return authorization[7:] if authorization.lower().startswith("bearer ") else authorization
    return None


def _principal_from(raw: str | None) -> Principal:
    if not raw:
        raise HTTPException(status_code=401, detail="missing token")
    if ALLOW_LEGACY_TOKEN and hmac.compare_digest(raw, ACCESS_TOKEN):
        return Principal(legacy=True, raw=raw)
    user = auth.resolve_token(raw)
    if not user:
        raise HTTPException(status_code=401, detail="bad or expired token")
    return Principal(user, raw=raw)


def require_principal(
    x_token: str | None = Header(default=None, alias="X-Token"),
    authorization: str | None = Header(default=None),
) -> Principal:
    """API 鉴权只认请求头：token 放在 URL 上会进访问日志和浏览器历史。"""
    return _principal_from(_bearer(x_token, authorization))


def require_admin(p: Principal = Depends(require_principal)) -> Principal:
    if not p.is_admin:
        raise HTTPException(status_code=403, detail="admin only")
    return p


def require_media(
    request: Request,
    photo_id: str,
    sig: str | None = Query(default=None),
    token: str | None = Query(default=None),
    x_token: str | None = Header(default=None, alias="X-Token"),
    authorization: str | None = Header(default=None),
) -> Principal:
    """图片/视频的 GET 鉴权。

    浏览器 <img>/<video> 不能自定义请求头，所以登录时额外下发 HttpOnly cookie；
    系统播放器拿不到 cookie/header，则用 /api/photos/{id}/link 换一个短期签名。
    这两条通道都只用于媒体 GET，写操作仍然必须带 Authorization 头。
    """
    raw = _bearer(x_token, authorization) or request.cookies.get(COOKIE_NAME)
    if not raw and ALLOW_LEGACY_MEDIA_TOKEN:
        raw = token
    if raw:
        return _principal_from(raw)
    if sig:
        uid = auth.verify_media_sig(photo_id, sig)
        user = auth.get_user(uid) if uid else None
        if not user:
            raise HTTPException(status_code=401, detail="bad media signature")
        return Principal(user)
    raise HTTPException(status_code=401, detail="missing token")


# ------------------------------------------------------------------ 启动 -----

def _cleanup_tmp(max_age_hours: int = 24) -> int:
    """清掉 TMP_DIR 里残留的 .part 半截文件。

    正常情况下上传中断由 _save_stream 的 except 分支删除临时文件；但进程被
    SIGKILL / 断电时 except 不会执行，会留下垃圾。半截文件只存在于 tmp、
    **永远不会进正式目录**（完整才算完 SHA、才原子改名），所以清掉是安全的。
    """
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


def _startup_cleanup() -> None:
    stale = _cleanup_tmp()
    if stale:
        log.info("cleaned %d stale .part files from tmp", stale)
    try:
        removed = media.cleanup_thumb_cache(db.all_photo_ids())
        if removed:
            log.info("cleaned %d orphan thumbnails", removed)
    except Exception:
        log.warning("thumbnail cache cleanup failed", exc_info=True)
    log.info("PhotoVault ready. photo dir = %s", PHOTO_DIR)


@asynccontextmanager
async def _lifespan(_app: FastAPI):
    _boot()
    _startup_cleanup()
    yield


# 幂等初始化：ASGI 服务器不触发 startup 事件时（TestClient 直接使用）也能用
_boot()

app = FastAPI(
    title="PhotoVault",
    version=VERSION,
    lifespan=_lifespan,
    docs_url="/docs" if ENABLE_DOCS else None,
    redoc_url=None,
    openapi_url="/openapi.json" if ENABLE_DOCS else None,
)


@app.exception_handler(Exception)
async def _unhandled(request: Request, exc: Exception):
    """兜底：把未捕获异常的**原因**回给客户端，同时把完整栈写进日志。

    以前 500 的响应体只有一句 "Internal Server Error"，手机端失败清单里就显示
    "HTTP 500 Internal Server Error"，用户不知道是数据库忙、磁盘满还是别的，
    排查只能去 NAS 上翻日志。现在响应体是 JSON，detail 里带异常类型和消息，
    App 那边会自动取出来显示（见 NasApi 的 detailOf），rid 用来对日志。
    """
    rid = uuid.uuid4().hex[:8]
    log.exception("[%s] 未处理异常 %s %s", rid, request.method, request.url.path)
    reason = f"{type(exc).__name__}: {exc}".strip() or type(exc).__name__
    return JSONResponse(status_code=500, content={"detail": reason[:300], "rid": rid})


@app.middleware("http")
async def _security_headers(request: Request, call_next):
    """给所有响应补上安全头。"""
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
    resp.headers["Cross-Origin-Resource-Policy"] = "same-origin"
    # 网页控制台/静态资源不要被浏览器或飞牛内嵌 WebView 长期缓存，
    # 否则升级后仍加载旧 HTML/JS。
    if request.scope.get("path", "") == "/" or request.scope.get("path", "").startswith("/static/"):
        resp.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        resp.headers["Pragma"] = "no-cache"
        resp.headers["Expires"] = "0"
    # 只有真的走了 HTTPS 才发 HSTS，不然明文访问会被锁死
    if request.url.scheme == "https":
        resp.headers["Strict-Transport-Security"] = "max-age=31536000"
    return resp


# ------------------------------------------------------------ 请求体模型 -----

class LoginIn(BaseModel):
    username: str = Field(default="", max_length=64)
    password: str = Field(default="", max_length=512)


class ChangePasswordIn(BaseModel):
    old_password: str = Field(default="", max_length=512)
    new_password: str = Field(default="", max_length=512)


class UserIn(BaseModel):
    username: str = Field(default="", max_length=64)
    password: str = Field(default="", max_length=512)
    display_name: str = Field(default="", max_length=64)
    role: str = Field(default="user", max_length=16)


class UserPatchIn(BaseModel):
    role: str | None = None
    disabled: bool | None = None
    display_name: str | None = Field(default=None, max_length=64)


class ResetPasswordIn(BaseModel):
    new_password: str = Field(default="", max_length=512)


class HttpsIn(BaseModel):
    enabled: bool


class DeviceRenameIn(BaseModel):
    old_id: str
    new_id: str


class IdsIn(BaseModel):
    ids: list[str] = Field(default_factory=list)


class HashesIn(BaseModel):
    sha256: list[str] = Field(default_factory=list)


class ForgetIn(BaseModel):
    ids: list[str] = Field(default_factory=list)
    device_id: str = Field(default="", max_length=120)


class PrescreenItemIn(BaseModel):
    key: str = ""
    size: int = 0
    taken_at: int | None = None
    name: str = Field(default="", max_length=512)


class PrescreenIn(BaseModel):
    items: list[PrescreenItemIn] = Field(default_factory=list)


# ------------------------------------------------------------ 认证接口 ------

def _login_throttled(ip: str) -> bool:
    """按 IP 的滑动窗口限流。账号锁定挡不住"换个账号接着撞"，这层补上。"""
    now = time.time()
    limit = max(10, int(os.getenv("LOGIN_RATE_LIMIT", "60")))
    with _LOGIN_LOCK:
        hits = [t for t in _LOGIN_HITS.get(ip, []) if now - t < 300]
        if len(hits) >= limit:
            _LOGIN_HITS[ip] = hits
            return True
        hits.append(now)
        _LOGIN_HITS[ip] = hits
    return False


_LOGIN_HITS: dict[str, list[float]] = {}
_LOGIN_LOCK = threading.Lock()


def _set_cookie(resp: Response, token: str, request: Request) -> None:
    resp.set_cookie(
        COOKIE_NAME, token, max_age=auth.TOKEN_TTL_SEC, httponly=True,
        samesite="lax", secure=request.url.scheme == "https", path="/",
    )


@app.get("/api/health")
def health():
    return {"ok": True, "server": "photovault", "version": VERSION,
            "ts": int(time.time() * 1000)}


@app.post("/api/ping")
def ping(p: Principal = Depends(require_principal)):
    """手机端「测试连接」。"""
    return {"ok": True, "server": "photovault", "version": VERSION, "as": p.label}


@app.post("/api/auth/login")
def login(body: LoginIn, request: Request):
    ip = request.client.host if request.client else "unknown"
    if _login_throttled(ip):
        raise HTTPException(status_code=429, detail="尝试过于频繁，请稍后再试")
    user, err, locked = auth.authenticate(body.username, body.password)
    if not user:
        # 锁定用 429，和"密码错"区分开，前端能给出不同的提示
        raise HTTPException(status_code=429 if locked else 401, detail=err)
    row = auth.get_user_by_name(body.username)
    token = auth.issue_token(row, device=request.headers.get("User-Agent", ""))
    resp = JSONResponse({"token": token, "user": user})
    _set_cookie(resp, token, request)
    return resp


@app.get("/api/auth/me")
def me(request: Request, p: Principal = Depends(require_principal)):
    resp = JSONResponse({"user": p.user, "legacy": p.legacy, "is_admin": p.is_admin})
    # 老网页会话只有 localStorage 里的 Bearer token、没有媒体 cookie；
    # 这里顺手补发一次 HttpOnly cookie，缩略图才能正常加载。
    if p.raw and p.user:
        _set_cookie(resp, p.raw, request)
    return resp


@app.post("/api/auth/logout")
def logout(request: Request, all_devices: bool = Query(False, alias="all"),
           p: Principal = Depends(require_principal)):
    """默认只吊销当前会话，不动同一账号的其他设备。"""
    if p.user and all_devices:
        auth.bump_epoch(p.user["id"])
    elif p.raw:
        auth.revoke_token(p.raw)
    resp = JSONResponse({"ok": True, "all": bool(all_devices)})
    resp.delete_cookie(COOKIE_NAME, path="/")
    return resp


@app.post("/api/auth/password")
def change_password(body: ChangePasswordIn, request: Request,
                    p: Principal = Depends(require_principal)):
    if not p.user:
        raise HTTPException(status_code=400, detail="兼容 Token 没有账号，请改用账号密码登录")
    row = auth.get_user_by_name(p.user["username"])
    if not row or not auth.verify_password(body.old_password, row["password_hash"]):
        raise HTTPException(status_code=400, detail="原密码不对")
    try:
        auth.set_password(p.user["id"], body.new_password)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    # 改密码会吊销全部旧 token（包括当前这个），补发一个新的，
    # 免得用户改完立刻被登出、还要再登一次
    row = auth.get_user_by_name(p.user["username"])
    token = auth.issue_token(row, device=request.headers.get("User-Agent", ""))
    resp = JSONResponse({"ok": True, "token": token,
                         "detail": "已修改，其他设备需要重新登录"})
    _set_cookie(resp, token, request)
    return resp


# ---------------------------------------------------------- 用户管理 --------

@app.get("/api/admin/users")
def admin_list_users(_: Principal = Depends(require_admin)):
    return {"items": auth.list_users()}


@app.post("/api/admin/users")
def admin_create_user(body: UserIn, _: Principal = Depends(require_admin)):
    try:
        u = auth.create_user(body.username, body.password, body.role, body.display_name)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"user": u}


@app.patch("/api/admin/users/{uid}")
def admin_update_user(uid: str, body: UserPatchIn, p: Principal = Depends(require_admin)):
    target = auth.get_user(uid)
    if not target:
        raise HTTPException(status_code=404, detail="not found")
    if uid == p.uid and (body.disabled is True or body.role == "user"):
        raise HTTPException(status_code=400, detail="不能禁用或降级自己")
    if (body.disabled is True or body.role == "user") and target["role"] == ROLE_ADMIN:
        if auth.count_admins(exclude_uid=uid) == 0:
            raise HTTPException(status_code=400, detail="系统里至少要保留一个管理员")
    fields = {}
    if body.role is not None:
        fields["role"] = body.role
    if body.disabled is not None:
        fields["disabled"] = bool(body.disabled)
    if body.display_name is not None:
        fields["display_name"] = body.display_name
    try:
        if fields:
            auth.update_user(uid, **fields)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    if body.disabled is True:
        auth.bump_epoch(uid)          # 立刻踢下线
    return {"user": auth.get_user(uid)}


@app.post("/api/admin/users/{uid}/password")
def admin_reset_password(uid: str, body: ResetPasswordIn, _: Principal = Depends(require_admin)):
    if not auth.get_user(uid):
        raise HTTPException(status_code=404, detail="not found")
    try:
        auth.set_password(uid, body.new_password)
        auth.require_password_change(uid)     # 是他被人设的，得自己改一次
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"ok": True}


@app.delete("/api/admin/users/{uid}")
def admin_delete_user(uid: str, p: Principal = Depends(require_admin)):
    if p.uid == uid:
        raise HTTPException(status_code=400, detail="不能删自己")
    target = auth.get_user(uid)
    if not target:
        raise HTTPException(status_code=404, detail="not found")
    if target["role"] == ROLE_ADMIN and auth.count_admins(exclude_uid=uid) == 0:
        raise HTTPException(status_code=400, detail="系统里至少要保留一个管理员")
    # 照片不跟着删，转给操作者；冲突（两人有同一份字节）在 db 层处理
    moved = db.transfer_photos(uid, p.uid or "")
    if not auth.delete_user(uid):
        raise HTTPException(status_code=404, detail="not found")
    return {"ok": True, "moved": moved}


# ------------------------------------------------------------------ 照片 -----

@app.post("/api/photos/exists")
def photos_exists(body: HashesIn, p: Principal = Depends(require_principal)):
    """批量查重：手机端上传前先问一遍，已存在的直接跳过，省流量。"""
    hashes = [h.lower() for h in body.sha256[:5000] if h]
    exist = db.find_hashes(hashes, p.scope())
    return {"existing": sorted(exist)}


def _dest_path(sha256: str, filename: str) -> tuple[Path, str]:
    ext = os.path.splitext(filename)[1].lower() or ".bin"
    rel = f"{sha256[:2]}/{sha256[2:4]}/{sha256}{ext}"
    return PHOTO_DIR / rel, rel


def _sanitize_filename(name: str) -> str:
    name = (name or "unknown").replace("\\", "/").split("/")[-1]
    name = "".join(c for c in name if c.isprintable()).strip()
    return name[:200] or "unknown"


def _content_disposition(name: str, inline: bool) -> str:
    """文件名来自客户端，直接塞进响应头会有注入风险。"""
    safe = "".join(c for c in name if c.isprintable() and c not in '/\\').strip() or "file"
    ascii_name = "".join(c if 32 <= ord(c) < 127 else "_" for c in safe)
    utf8_name = quote(safe, safe="")
    kind = "inline" if inline else "attachment"
    return f'{kind}; filename="{ascii_name}"; filename*=UTF-8\'\'{utf8_name}'


def _save_stream(upload: UploadFile, limit: int | None) -> tuple[str, int, Path]:
    """边读边算 SHA256 边落临时盘，避免大文件吃内存。"""
    tmp = TMP_DIR / f"{uuid.uuid4().hex}.part"
    h = hashlib.sha256()
    size = 0
    try:
        with open(tmp, "wb") as f:
            while True:
                chunk = upload.file.read(CHUNK_SIZE)
                if not chunk:
                    break
                size += len(chunk)
                if limit is not None and size > limit:
                    raise HTTPException(413, f"file exceeds {limit // BYTES_PER_MB}MB")
                h.update(chunk)
                f.write(chunk)
    except Exception:
        tmp.unlink(missing_ok=True)
        raise
    return h.hexdigest(), size, tmp


@app.post("/api/upload")
def upload(
    request: Request,
    file: UploadFile = File(...),
    meta: str = Form(default="{}"),
    device_id: str | None = Form(default=None),
    thumb: UploadFile | None = File(default=None),
    p: Principal = Depends(require_principal),
):
    """上传一张照片/一个视频。

    同步端点（普通 def）：FastAPI 会把它丢到线程池，读写大文件、算 SHA256
    不会阻塞事件循环。之前是 async def + 同步 IO，一个 1GB 视频能把整个
    服务（包括 /api/health）卡住几分钟。
    """
    filename = _sanitize_filename(file.filename or "unknown")
    ext = os.path.splitext(filename)[1].lower()

    # 只收媒体文件。文件名是客户端说了算的，光靠扩展名判断挡不住有心人，
    # 但至少能拦住"往照片库里塞脚本/可执行文件"这类顺手的事
    if not ALLOW_ANY_FILE and ext not in IMAGE_EXT and ext not in VIDEO_EXT:
        raise HTTPException(
            status_code=415,
            detail=f"只接受图片和视频（{'无扩展名' if not ext else ext} 不在允许范围内）",
        )

    limit_mb = MAX_UPLOAD_MB
    limit = limit_mb * BYTES_PER_MB if limit_mb > 0 else None
    owner = p.uid or ""

    # 配额与磁盘余量：一个账号不能把 NAS 写满
    if USER_QUOTA_GB > 0 and owner:
        used = db.user_usage_bytes(owner)
        if used >= USER_QUOTA_GB * 1024 ** 3:
            raise HTTPException(413, "已超出该账号的存储配额")
        room = int(USER_QUOTA_GB * 1024 ** 3 - used)
        limit = min(limit, room) if limit is not None else room
    try:
        free = shutil.disk_usage(PHOTO_DIR).free
    except OSError:
        free = 0
    if free and free < MIN_FREE_MB * BYTES_PER_MB:
        raise HTTPException(507, "NAS 磁盘空间不足")

    # Content-Length 是 multipart 总长，留 8MB 给表单开销
    cl = request.headers.get("content-length")
    if cl and limit and cl.isdigit() and int(cl) > limit + 8 * BYTES_PER_MB:
        raise HTTPException(413, f"file exceeds {limit_mb}MB")

    sha256, size, tmp = _save_stream(file, limit)

    # 1) 自己传过这张：还在就跳过；被删进回收站就复活原来那条
    #    （不能直接插新记录，会撞 (owner_id, sha256) 唯一约束）
    dup = db.find_by_sha(sha256, owner, include_deleted=True)
    if dup and not dup.get("deleted") and (PHOTO_DIR / dup["rel_path"]).exists():
        if device_id:
            db.link_device(dup["id"], device_id)
        tmp.unlink(missing_ok=True)
        return {"status": "duplicate", "id": dup["id"], "sha256": sha256,
                "taken_at": dup.get("taken_at"), "has_gps": dup.get("gps_lat") is not None}
    if dup and not dup.get("deleted"):
        # 记录还在、磁盘文件没了（手工误删/迁移丢文件）：清掉悬空记录，重新落盘，
        # 否则这张照片会被永远当成"已备份"而再也补不回来。
        db.delete_record(dup["id"])
        dup = None

    try:
        # 有些客户端（Windows/PowerShell 脚本）会在表单文本前带 UTF-8 BOM
        raw_meta = (meta or "").lstrip("\ufeff \t\r\n")
        client_meta = json.loads(raw_meta) if raw_meta else {}
        if not isinstance(client_meta, dict):
            client_meta = {}
    except json.JSONDecodeError:
        client_meta = {}

    # 2) 别人传过同一份字节，而且**文件确实还在盘上** → 不重复落盘
    shared = db.find_by_sha(sha256)
    if shared and shared.get("rel_path") and (PHOTO_DIR / shared["rel_path"]).exists():
        tmp.unlink(missing_ok=True)
        rel_path = shared["rel_path"]
        extracted = {k: shared.get(k) for k in (
            "width", "height", "orientation", "taken_at", "gps_lat", "gps_lon",
            "gps_alt", "camera_make", "camera_model", "meta_source") if shared.get(k) is not None}
    else:
        dest, rel_path = _dest_path(sha256, filename)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(tmp), str(dest))          # 同一卷内 move 是原子改名
        extracted = media.extract_metadata(PHOTO_DIR / rel_path, filename)

    # 3. 元数据：服务端抽 EXIF，客户端提供的值优先
    merged = media.merge_client_metadata(extracted, client_meta)

    rec = {
        "sha256": sha256,
        "filename": filename,
        "rel_path": rel_path,
        # MIME 只按扩展名推导，绝不用客户端声明的 Content-Type
        "mime": guess_mime(filename),
        "kind": kind_of(filename),
        "size": size,
        "device_id": (str(device_id or client_meta.get("device_id") or "")[:120] or None),
        "owner_id": owner,
        **{k: merged.get(k) for k in (
            "taken_at", "uploaded_at", "meta_source", "gps_lat", "gps_lon", "gps_alt",
            "width", "height", "orientation", "camera_make", "camera_model",
            "album", "original_path", "rel_dir")},
    }
    if rec.get("rel_dir"):
        rec["rel_dir"] = str(rec["rel_dir"]).replace("\\", "/")[:512]
    # 客户端 meta 是任意 JSON，类型不能信：DB 绑定前统一收敛
    for k in ("camera_make", "camera_model", "album", "original_path"):
        if rec.get(k) is not None:
            rec[k] = str(rec[k])[:256]
    for k in ("width", "height", "orientation"):
        try:
            rec[k] = int(rec[k]) if rec.get(k) is not None else None
        except (TypeError, ValueError):
            rec[k] = None
    for k in ("gps_lat", "gps_lon", "gps_alt", "taken_at"):
        try:
            rec[k] = float(rec[k]) if k != "taken_at" and rec.get(k) is not None else (
                int(rec[k]) if rec.get(k) is not None else None)
        except (TypeError, ValueError):
            rec[k] = None
    rec["uploaded_at"] = rec.get("uploaded_at") or int(time.time() * 1000)

    try:
        if dup:                       # 从回收站复活
            db.revive_photo(dup["id"], rec)
            photo_id, status = dup["id"], "restored"
        else:
            photo_id, status = db.insert_photo(rec), "created"
    except sqlite3.IntegrityError:
        # 两个客户端同时传同一份字节：后到的会撞唯一约束。
        # 不是错误，按"已存在"返回即可。
        again = db.find_by_sha(sha256, owner, include_deleted=True)
        if not again:
            raise
        photo_id, status = again["id"], "duplicate"

    if device_id:
        db.link_device(photo_id, device_id)

    # 4) 客户端抽好的封面帧（主要是视频）：服务端没 ffmpeg 解不了视频帧，
    #    存下之后 /api/photos/{id}/thumb 就会先命中这个文件直接返回。
    if thumb is not None:
        # 注意：UploadFile.read() 是 async 的，这个端点却是同步 def（要靠线程池
        # 跑大文件 IO）。在这调 read() 拿到的是 coroutine，写盘时才会炸成
        # TypeError: memoryview: a bytes-like object is required, not 'coroutine'
        # —— 表现就是"备份视频 500"。必须走底层的同步 file 对象。
        try:
            data = thumb.file.read(2 * BYTES_PER_MB)
        except Exception as e:
            log.warning("thumb read failed for %s: %s", filename, e)
            data = b""
        if isinstance(data, (bytes, bytearray)) and data and not media.thumbnail_path(photo_id).exists():
            media.save_thumbnail_bytes(photo_id, bytes(data))

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
    q: str | None = Query(None, max_length=128),
    viewer_device: str | None = Query(None, max_length=120),
    p: Principal = Depends(require_principal),
):
    # total 每页都算：以前只在第一页算（省一次 COUNT），结果客户端翻到第二页时
    # total=0，用 "offset >= total" 判断结束就立刻停 —— 2000 张的库只能还原 1000 张。
    items, total = db.list_photos(
        limit, offset, kind, album, device_id, taken_from, taken_to, has_gps,
        order, p.scope(owner), folder, q, viewer_device, with_total=True,
    )
    return {
        "total": total, "offset": offset, "limit": limit,
        "has_more": offset + len(items) < total,
        "items": items,
    }


def _load(photo_id: str, p: Principal, include_deleted: bool = False) -> dict:
    # 回收站里的照片在线列表里不可见，但缩略图/原图仍要能预览，
    # 否则网页端回收站就是一排裂图。
    photo = db.get_photo(photo_id, include_deleted=include_deleted)
    if not photo or not p.can_touch(photo):
        raise HTTPException(404, "not found")
    return photo


@app.get("/api/photos/{photo_id}")
def photo_meta(photo_id: str, p: Principal = Depends(require_principal)):
    return _load(photo_id, p)


@app.get("/api/photos/{photo_id}/thumb")
def photo_thumb(photo_id: str, request: Request, size: int = Query(0, ge=0, le=2048),
                p: Principal = Depends(require_media)):
    photo = _load(photo_id, p, include_deleted=True)
    if not ENABLE_THUMB:
        raise HTTPException(404, "thumbnail disabled")
    want = size or THUMB_SIZE
    etag = f'"{photo_id}-{photo.get("size") or 0}-{want}"'
    if request.headers.get("if-none-match") == etag:
        return Response(status_code=304)
    src = PHOTO_DIR / photo["rel_path"]
    if not src.exists():
        raise HTTPException(410, "file missing on disk")
    data = media.get_or_make_thumbnail(photo_id, src, photo["filename"], want)
    if data is None:
        raise HTTPException(404, "no thumbnail available")
    return Response(
        content=data, media_type="image/jpeg",
        headers={
            # 私有内容不能进共享缓存；浏览器自身可以缓存很久
            "Cache-Control": "private, max-age=31536000, immutable",
            "ETag": etag,
        },
    )


@app.get("/api/photos/{photo_id}/link")
def photo_link(photo_id: str, p: Principal = Depends(require_principal)):
    """给系统播放器/外部应用用的短期直链（1 小时，仅这张照片有效）。"""
    _load(photo_id, p)
    uid = p.uid or (p.user or {}).get("id") or ""
    if not uid:
        raise HTTPException(400, "兼容 Token 无账号，无法签发媒体链接")
    return {"url": f"/api/photos/{photo_id}/file?sig={auth.make_media_sig(uid, photo_id)}",
            "expires_in": 3600}


@app.get("/api/photos/{photo_id}/file")
def photo_file(photo_id: str, request: Request, download: bool = Query(False),
               p: Principal = Depends(require_media)):
    """下载**原文件**。

    字节与手机上传时完全一致，EXIF 完好 → 手机端写回系统相册后，
    Google Photos / 系统相册读到的拍摄时间与 GPS 与拍摄当天一致。

    支持 HTTP Range（206）：播放器靠它拖进度条，也靠它先读 MP4 尾部的 moov。
    没有 Range 时播放器只能从头把整个文件拉完，大视频基本没法看。
    """
    photo = _load(photo_id, p, include_deleted=True)
    src = PHOTO_DIR / photo["rel_path"]
    if not src.exists():
        raise HTTPException(410, "file missing on disk")
    mime = guess_mime(photo["filename"])
    inline = (not download) and mime != "application/octet-stream"
    headers = {"Content-Disposition": _content_disposition(photo.get("filename") or "photo", inline)}
    if not inline:
        headers["Cache-Control"] = "private, no-store"
    else:
        headers["Cache-Control"] = "private, max-age=3600"
    headers["Accept-Ranges"] = "bytes"

    size = src.stat().st_size
    rng = (request.headers.get("range") or "").strip()
    m = re.match(r"^bytes=(\d*)-(\d*)$", rng) if rng else None
    if m:
        first, last = m.group(1), m.group(2)
        if not first and not last:
            m = None
        else:
            if not first:                       # bytes=-N → 末尾 N 字节
                start = max(size - int(last), 0)
                end = size - 1
            else:
                start = int(first)
                end = int(last) if last else size - 1
            end = min(end, size - 1)
            if start >= size or start > end:
                return Response(
                    status_code=416,
                    headers={"Content-Range": f"bytes */{size}", "Accept-Ranges": "bytes"},
                )
            length = end - start + 1

            def _chunks(path: Path, offset: int, count: int):
                with open(path, "rb") as fh:
                    fh.seek(offset)
                    left = count
                    while left > 0:
                        buf = fh.read(min(262144, left))
                        if not buf:
                            break
                        left -= len(buf)
                        yield buf

            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
            headers["Content-Length"] = str(length)
            return StreamingResponse(_chunks(src, start, length), status_code=206,
                                     media_type=mime, headers=headers)

    headers["Content-Length"] = str(size)
    return FileResponse(src, media_type=mime, headers=headers)


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
def delete_many(body: IdsIn, p: Principal = Depends(require_principal)):
    """批量删除（进回收站）。全选删除时用，省得前端发几百个请求。"""
    ids = [str(i) for i in body.ids[:1000] if i]
    owner = p.uid if not p.is_admin else None
    deleted = db.soft_delete_many(ids, owner)
    return {"ok": True, "deleted": deleted, "requested": len(body.ids)}


@app.post("/api/photos/forget")
def forget_photos(body: ForgetIn, p: Principal = Depends(require_principal)):
    """手机端已删除的照片：在 NAS 上按设备打墓碑并移入回收站。

    只处理调用者自己的照片；多设备时另一台设备仍持有就保留活记录。
    永远只软删除，网页端回收站还能恢复。
    """
    if not p.uid:
        raise HTTPException(status_code=400, detail="兼容 Token 没有账号，无法同步删除")
    ids = [str(i) for i in body.ids[:1000] if i]
    n = db.forget_for_device(p.uid, body.device_id, ids)
    return {"ok": True, "trashed": n, "requested": len(body.ids)}


@app.get("/api/trash")
def list_trash(limit: int = Query(200, ge=1, le=1000), offset: int = Query(0, ge=0),
               p: Principal = Depends(require_principal)):
    return {"items": db.list_trash(limit, p.scope(), offset),
            "total": db.count_trash(p.scope())}


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
def delete_trash_items(body: IdsIn, p: Principal = Depends(require_principal)):
    """只彻底删掉回收站里**指定的几条**（选择性删除）。"""
    ids = [str(i) for i in body.ids[:1000] if i]
    owner = p.uid if not p.is_admin else None
    n = db.purge_trash_ids(ids, owner)
    return {"ok": True, "deleted": n, "requested": len(body.ids)}


@app.post("/api/trash/restore")
def restore_trash(body: IdsIn, p: Principal = Depends(require_principal)):
    """批量从回收站恢复（网页端多选恢复用）。"""
    ids = [str(i) for i in body.ids[:1000] if i]
    owner = p.uid if not p.is_admin else None
    n = db.restore_many(ids, owner)
    return {"ok": True, "restored": n, "requested": len(body.ids)}


@app.post("/api/device/rename")
def rename_device(body: DeviceRenameIn, p: Principal = Depends(require_principal)):
    """用户自定义设备 ID 时，把旧 ID 下的照片/映射迁到新 ID。"""
    old_id = body.old_id.strip()
    new_id = body.new_id.strip()
    if not p.uid:
        raise HTTPException(400, "当前登录方式不支持修改设备 ID")
    pattern = r"^[A-Za-z0-9._-]{4,64}$"
    if not re.match(pattern, old_id) or not re.match(pattern, new_id):
        raise HTTPException(400, "设备 ID 只能包含字母、数字、点、下划线、短横线，长度 4~64")
    photos, mappings = db.rename_device(p.uid, old_id, new_id)
    return {"ok": True, "photos": photos, "mappings": mappings}


@app.get("/api/admin/devices")
def admin_devices(_: Principal = Depends(require_admin)):
    return {"items": db.device_summary()}


@app.get("/api/admin/integrity")
def integrity(_: Principal = Depends(require_admin)):
    """索引 vs 磁盘：文件是否都在、大小是否一致、有没有孤儿文件。"""
    return db.integrity_report()


def _find_cert() -> tuple[Path | None, Path | None]:
    CERT_DIR.mkdir(parents=True, exist_ok=True)
    for crt in sorted(CERT_DIR.glob("*.crt")):
        key = crt.with_suffix(".key")
        if key.is_file():
            return crt, key
    return None, None


def _https_state() -> dict:
    crt, key = _find_cert()
    flag = ""
    if HTTPS_FLAG.is_file():
        flag = HTTPS_FLAG.read_text(encoding="utf-8", errors="ignore").strip()
    # 没有 flag 时，有证书就默认启用（兼容已经配好证书的安装）。
    enabled = bool(crt and key) and flag != "0"
    valid = False
    if crt and key:
        try:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(str(crt), str(key))
            valid = True
        except Exception as exc:  # noqa: BLE001 - 只在状态接口里报告，不泄露私钥
            log.warning("HTTPS 证书无效：%s", exc)
    return {
        "enabled": enabled,
        "cert_present": bool(crt and key),
        "cert_valid": valid,
        "restart_policy": "container",
    }


def _restart_after_https_change() -> None:
    time.sleep(1.2)
    log.info("HTTPS 设置已变更，正在重启服务")
    os.kill(os.getpid(), signal.SIGTERM)


@app.get("/api/admin/https")
def https_state(_: Principal = Depends(require_admin)):
    return _https_state()


@app.post("/api/admin/https")
def set_https(body: HttpsIn, _: Principal = Depends(require_admin)):
    state = _https_state()
    if body.enabled:
        crt, key = _find_cert()
        if not crt or not key:
            raise HTTPException(400, "没有找到证书文件，请先把 .crt 和 .key 放到 data/certs")
        try:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(str(crt), str(key))
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(400, f"证书或私钥无效：{exc}") from exc
    CERT_DIR.mkdir(parents=True, exist_ok=True)
    HTTPS_FLAG.write_text("1" if body.enabled else "0", encoding="utf-8")
    changed = state["enabled"] != body.enabled
    if changed:
        threading.Thread(target=_restart_after_https_change, daemon=True).start()
    return {
        "ok": True,
        "enabled": body.enabled,
        "changed": changed,
        "restarting": changed,
    }


@app.post("/api/photos/prescreen")
def prescreen(body: PrescreenIn, p: Principal = Depends(require_principal)):
    """上传前的粗筛：只看 size + 拍摄时间 + 文件名，不读文件内容。

    手机端拿它先过一遍，命中直接跳过 —— 增量备份时绝大多数照片都能这样省掉
    一整遍读文件算 SHA256。注意客户端必须用**和上传 meta 同一个 taken_at**，
    否则两边时区/来源不一致，永远命中不了。
    """
    items = [it.model_dump() if hasattr(it, "model_dump") else it.dict()
             for it in body.items[:20000]]
    known = db.prescreen_known(p.scope(), items)
    out_known, out_unknown = [], []
    for it in items:
        key = str(it.get("key") or "")
        sig = f"{it.get('size') or 0}|{it.get('taken_at') if it.get('taken_at') is not None else -1}|{it.get('name') or ''}"
        (out_known if sig in known else out_unknown).append(key)
    return {"known": out_known, "unknown": out_unknown}


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
BUILD_TAG = time.strftime("%m%d-%H%M%S")
STATIC_DIR = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


def _console_html() -> str:
    """网页控制台拆成独立静态文件，Python 字符串不再参与 HTML/JS 转义。"""
    raw = (STATIC_DIR / "index.html").read_text(encoding="utf-8")
    return raw.replace("__BUILD__", BUILD_TAG).replace("__BASE__", URL_PREFIX)


@app.get("/", response_class=HTMLResponse)
def console():
    # 必须禁用缓存 —— 否则改了页面用户那边还是旧的
    return HTMLResponse(
        _console_html(),
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
            "Expires": "0",
        },
    )


@app.get("/favicon.ico")
def favicon():
    return Response(status_code=204)

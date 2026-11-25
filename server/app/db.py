"""SQLite 元数据索引。

只存索引，不存图片本体；图片本体以原字节躺在 PHOTO_DIR 下。
"""

from __future__ import annotations

import functools
import os
import sqlite3
import time
import uuid
from contextlib import contextmanager

from .config import DB_PATH, PHOTO_DIR

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;

CREATE TABLE IF NOT EXISTS photos (
    id            TEXT PRIMARY KEY,
    sha256        TEXT NOT NULL,
    filename      TEXT NOT NULL,
    rel_path      TEXT NOT NULL,
    mime          TEXT,
    kind          TEXT NOT NULL DEFAULT 'image',
    size          INTEGER NOT NULL DEFAULT 0,

    taken_at      INTEGER,          -- 拍摄时间（毫秒 epoch，UTC）
    uploaded_at   INTEGER NOT NULL,
    meta_source   TEXT,             -- 'exif' | 'client' | 'filename'

    gps_lat       REAL,
    gps_lon       REAL,
    gps_alt       REAL,

    width         INTEGER,
    height        INTEGER,
    orientation   INTEGER,
    camera_make   TEXT,
    camera_model  TEXT,

    device_id     TEXT,
    original_path TEXT,
    album         TEXT,
    rel_dir       TEXT,             -- 手机上的相对目录，如 DCIM/Camera，还原时按它写回去

    owner_id      TEXT NOT NULL DEFAULT '',   -- 归属用户，空串表示迁移前的数据
    deleted       INTEGER NOT NULL DEFAULT 0,
    deleted_at    INTEGER,

    UNIQUE(owner_id, sha256)
);

CREATE INDEX IF NOT EXISTS idx_photos_taken    ON photos(taken_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_uploaded ON photos(uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_album    ON photos(album);
CREATE INDEX IF NOT EXISTS idx_photos_device   ON photos(device_id);
-- 查重（按内容）是每次上传的必经路径。UNIQUE(owner_id,sha256) 的最左列是
-- owner_id，单独按 sha256 查用不上它，会退化成全表扫描。
CREATE INDEX IF NOT EXISTS idx_photos_sha      ON photos(sha256);
-- 列表默认 WHERE deleted=0 ORDER BY taken_at DESC
CREATE INDEX IF NOT EXISTS idx_photos_live     ON photos(deleted, taken_at DESC, id);
-- 普通用户列表：owner 过滤 + 时间排序一次走完，避免临时 B 树
CREATE INDEX IF NOT EXISTS idx_photos_owner_list ON photos(owner_id, deleted, taken_at DESC, id);
-- 粗筛用的 (size, filename)，避免每次同步全表扫描
CREATE INDEX IF NOT EXISTS idx_photos_prescreen ON photos(size, filename);

CREATE TABLE IF NOT EXISTS devices (
    device_id   TEXT PRIMARY KEY,
    name        TEXT,
    last_seen   INTEGER,
    uploaded    INTEGER NOT NULL DEFAULT 0
);

-- 一张照片可能被同一账号的多台设备备份过。删除同步必须按设备记录，
-- 否则 A 手机删掉照片会把 B 手机的备份记录一起删掉。
CREATE TABLE IF NOT EXISTS photo_devices (
    photo_id    TEXT NOT NULL,
    device_id   TEXT NOT NULL,
    first_seen  INTEGER NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,   -- 该设备上已删除（墓碑）
    PRIMARY KEY(photo_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_photo_devices_device ON photo_devices(device_id);
"""


# SQLite 并发写的两种"假故障"：database is locked（busy）和
# attempt to write a readonly database（WAL 下写者被顶掉的变体，Windows 上尤其常见）。
# 两者都是等一下重试就能过的，直接冒到 HTTP 层就是一个 500 ——
# 用户看到的就是"备份失败 HTTP 500 Internal Server Error"，还查不出原因。
_BUSY_HINTS = ("database is locked", "database table is locked", "readonly", "busy")


def _retry_write(fn):
    """写操作遇到 busy / readonly 自动退避重试，重试完还是不行才抛。"""

    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        last: Exception | None = None
        for delay in (0.05, 0.15, 0.4, 1.0):
            try:
                return fn(*args, **kwargs)
            except sqlite3.OperationalError as e:
                if not any(h in str(e).lower() for h in _BUSY_HINTS):
                    raise
                last = e
                time.sleep(delay)
        assert last is not None
        raise last

    return wrapper


@contextmanager
def connect():
    """一次请求/操作一个连接，用完立刻关闭。

    以前是直接返回 Connection 然后 `with connect() as conn`：Python 的
    Connection.__exit__ 只提交/回滚，**不会 close()**，全靠 GC 回收。
    巡检、批量删除这类会连续开几十个连接的操作很容易把 fd 堆满。
    """
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    conn.row_factory = sqlite3.Row
    # journal_mode 是持久化设置，只需 init_db 时设一次；
    # 每个连接都执行 PRAGMA journal_mode=WAL 会额外拿锁，缩略图并发时很明显。
    conn.execute("PRAGMA busy_timeout=30000")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db() -> None:
    # 顺序有讲究：老库上 owner_id 还不存在，涉及该列的索引必须等迁移完再建
    with connect() as conn:
        conn.executescript(SCHEMA)
    _migrate_owner_column()
    with connect() as conn:
        # 迁移会重建表、把索引一起丢掉，所以这些必须在迁移之后再确保一次
        for ddl in (
            "CREATE INDEX IF NOT EXISTS idx_photos_owner ON photos(owner_id)",
            "CREATE INDEX IF NOT EXISTS idx_photos_sha ON photos(sha256)",
            "CREATE INDEX IF NOT EXISTS idx_photos_live ON photos(deleted, taken_at DESC, id)",
            "CREATE INDEX IF NOT EXISTS idx_photos_owner_list ON photos(owner_id, deleted, taken_at DESC, id)",
            "CREATE INDEX IF NOT EXISTS idx_photos_prescreen ON photos(size, filename)",
        ):
            conn.execute(ddl)
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(photos)").fetchall()}
        if "deleted_at" not in cols:
            conn.execute("ALTER TABLE photos ADD COLUMN deleted_at INTEGER")
        if "rel_dir" not in cols:
            conn.execute("ALTER TABLE photos ADD COLUMN rel_dir TEXT")


# 老库里 sha256 是全局唯一的，约束改不动，只能重建表。
# 数据按原样搬过去，owner_id 先留空，等管理员账号建好后再认领。
_MIGRATE_OWNER_SQL = """
CREATE TABLE IF NOT EXISTS photos_new (
    id TEXT PRIMARY KEY, sha256 TEXT NOT NULL, filename TEXT NOT NULL,
    rel_path TEXT NOT NULL, mime TEXT, kind TEXT NOT NULL DEFAULT 'image',
    size INTEGER NOT NULL DEFAULT 0, taken_at INTEGER, uploaded_at INTEGER NOT NULL,
    meta_source TEXT, gps_lat REAL, gps_lon REAL, gps_alt REAL,
    width INTEGER, height INTEGER, orientation INTEGER,
    camera_make TEXT, camera_model TEXT, device_id TEXT, original_path TEXT,
    album TEXT, rel_dir TEXT, owner_id TEXT NOT NULL DEFAULT '', deleted INTEGER NOT NULL DEFAULT 0,
    UNIQUE(owner_id, sha256)
);

INSERT OR IGNORE INTO photos_new (
    id,sha256,filename,rel_path,mime,kind,size,taken_at,uploaded_at,meta_source,
    gps_lat,gps_lon,gps_alt,width,height,orientation,camera_make,camera_model,
    device_id,original_path,album,owner_id,deleted
) SELECT
    id,sha256,filename,rel_path,mime,kind,size,taken_at,uploaded_at,meta_source,
    gps_lat,gps_lon,gps_alt,width,height,orientation,camera_make,camera_model,
    device_id,original_path,album,'',deleted
  FROM photos;

DROP TABLE photos;
ALTER TABLE photos_new RENAME TO photos;

CREATE INDEX IF NOT EXISTS idx_photos_taken    ON photos(taken_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_uploaded ON photos(uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_album    ON photos(album);
CREATE INDEX IF NOT EXISTS idx_photos_device   ON photos(device_id);
CREATE INDEX IF NOT EXISTS idx_photos_owner    ON photos(owner_id);
CREATE INDEX IF NOT EXISTS idx_photos_sha      ON photos(sha256);
CREATE INDEX IF NOT EXISTS idx_photos_live     ON photos(deleted, taken_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_photos_owner_list ON photos(owner_id, deleted, taken_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_photos_prescreen ON photos(size, filename);
CREATE TABLE IF NOT EXISTS photo_devices (
    photo_id TEXT NOT NULL, device_id TEXT NOT NULL,
    first_seen INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(photo_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_photo_devices_device ON photo_devices(device_id);
"""


def _migrate_owner_column() -> None:
    with connect() as conn:
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(photos)").fetchall()}
        if "owner_id" in cols:
            return
        conn.executescript(_MIGRATE_OWNER_SQL)


def claim_orphan_photos(owner_id: str) -> int:
    """把升级前上传的照片归到指定用户（首次启动时给管理员）。

    用 OR IGNORE：如果管理员自己已经有一份相同 sha256 的照片，直接 UPDATE 会撞
    UNIQUE(owner_id, sha256)。撞上的说明内容管理员本来就有，把孤儿记录删掉即可，
    文件仍被管理员那条记录引用，不会丢。
    """
    with connect() as conn:
        cur = conn.execute(
            "UPDATE OR IGNORE photos SET owner_id=? WHERE owner_id IS NULL OR owner_id=''",
            (owner_id,),
        )
        conn.execute("DELETE FROM photos WHERE owner_id IS NULL OR owner_id=''")
        claimed = cur.rowcount
    return claimed


def transfer_photos(from_uid: str, to_uid: str) -> int:
    """把 A 的照片转给 B，并处理两边持有同一份字节时的唯一约束冲突。

    场景很常见：两口子备份了同一张照片（跨用户去重只存一份），
    删掉其中一人时不能因为 UNIQUE(owner_id, sha256) 就 500。
    冲突行说明 B 已经有这份内容，删掉 A 的重复记录；如果这之后没有任何
    记录再引用那个文件，顺手把磁盘上的孤儿文件也清掉。
    """
    orphans: set[str] = set()
    with connect() as conn:
        cur = conn.execute("UPDATE OR IGNORE photos SET owner_id=? WHERE owner_id=?",
                           (to_uid, from_uid))
        remaining = {r["rel_path"] for r in conn.execute(
            "SELECT rel_path FROM photos WHERE owner_id=? AND rel_path IS NOT NULL",
            (from_uid,)).fetchall()}
        conn.execute("DELETE FROM photos WHERE owner_id=?", (from_uid,))
        conn.execute("DELETE FROM photo_devices WHERE photo_id NOT IN (SELECT id FROM photos)")
        moved = cur.rowcount
        for rel in remaining:
            still = conn.execute("SELECT 1 FROM photos WHERE rel_path=? LIMIT 1", (rel,)).fetchone()
            if not still:
                orphans.add(rel)
    for rel in orphans:
        try:
            os.remove(PHOTO_DIR / rel)
        except OSError:
            pass
    return moved


@_retry_write
def delete_record(photo_id: str) -> bool:
    """硬删一条索引记录（不碰磁盘文件）。用于修复"记录在、文件没了"的悬空记录。"""
    with connect() as conn:
        conn.execute("DELETE FROM photo_devices WHERE photo_id=?", (photo_id,))
        cur = conn.execute("DELETE FROM photos WHERE id=?", (photo_id,))
        return cur.rowcount > 0


@_retry_write
def link_device(photo_id: str, device_id: str) -> None:
    """记录"这台设备也有这张照片"；重复上传/复活时把墓碑清掉。"""
    device_id = (device_id or "").strip()[:120]
    if not photo_id or not device_id:
        return
    with connect() as conn:
        conn.execute(
            "INSERT INTO photo_devices(photo_id, device_id, first_seen, deleted) "
            "VALUES(?,?,?,0) "
            "ON CONFLICT(photo_id, device_id) DO UPDATE SET deleted=0",
            (photo_id, device_id, int(time.time() * 1000)),
        )


def rename_device(owner_id: str, old_id: str, new_id: str) -> tuple[int, int]:
    """把某用户在某设备下的照片/映射整体改到新设备 ID。

    用户自定义设备 ID、或卸载重装后想沿用旧 ID 时用。只动自己的照片，
    不会影响其他用户；新 ID 已存在时相当于两台设备合并。
    """
    if not owner_id or not old_id or not new_id or old_id == new_id:
        return 0, 0
    with connect() as conn:
        photos = conn.execute(
            "UPDATE photos SET device_id=? WHERE owner_id=? AND device_id=?",
            (new_id, owner_id, old_id),
        ).rowcount
        mappings = conn.execute(
            "UPDATE OR REPLACE photo_devices SET device_id=? "
            "WHERE photo_id IN (SELECT id FROM photos WHERE owner_id=? AND device_id=?)",
            (new_id, owner_id, new_id),
        ).rowcount
    return photos, mappings


def device_summary() -> list[dict]:
    """管理员用：按用户列出所有设备 ID 和数量，方便重装后找回旧 ID。"""
    with connect() as conn:
        rows = conn.execute(
            "SELECT p.owner_id, COALESCE(NULLIF(p.device_id,''),'') device_id, "
            "COALESCE(u.username,'') username, COALESCE(u.display_name,'') display_name, "
            "SUM(CASE WHEN p.deleted=0 THEN 1 ELSE 0 END) live, "
            "SUM(CASE WHEN p.deleted=1 THEN 1 ELSE 0 END) trashed, "
            "COUNT(*) total, MAX(COALESCE(p.uploaded_at,0)) last_upload_at "
            "FROM photos p LEFT JOIN users u ON u.id=p.owner_id "
            "GROUP BY p.owner_id, p.device_id "
            "ORDER BY live DESC, total DESC"
        ).fetchall()
    return [dict(r) for r in rows]


def forget_for_device(owner_id: str, device_id: str, photo_ids: list[str]) -> int:
    """手机端已删除的照片：在这台设备上打墓碑；没有其他设备引用时移入回收站。

    - 只处理 owner 自己的、未删除的照片；
    - 多设备时保留其他设备的记录，只在当前设备隐藏（列表用 viewer_device 过滤）；
    - 旧数据没有 photo_devices 映射时，退回用 photos.device_id 判断；
    - 永远只软删除（进回收站），不直接抹磁盘。
    """
    device_id = (device_id or "").strip()[:120]
    if not owner_id or not device_id or not photo_ids:
        return 0
    marks = ",".join("?" for _ in photo_ids[:1000])
    args = list(photo_ids[:1000]) + [owner_id]
    trashed = 0
    now = int(time.time() * 1000)
    with connect() as conn:
        rows = conn.execute(
            f"SELECT id, device_id, rel_path FROM photos "
            f"WHERE id IN ({marks}) AND owner_id=? AND deleted=0",
            args,
        ).fetchall()
        for r in rows:
            maps = conn.execute(
                "SELECT device_id, deleted FROM photo_devices WHERE photo_id=?",
                (r["id"],),
            ).fetchall()
            if maps:
                if not any(m["device_id"] == device_id for m in maps):
                    continue        # 不是这台设备备份的，不动
                conn.execute(
                    "UPDATE photo_devices SET deleted=1 WHERE photo_id=? AND device_id=?",
                    (r["id"], device_id),
                )
                still = conn.execute(
                    "SELECT 1 FROM photo_devices WHERE photo_id=? AND deleted=0 LIMIT 1",
                    (r["id"],),
                ).fetchone()
                if still:
                    continue        # 另一台设备还持有，保留活记录
            else:
                # 旧版数据没有设备映射，只有上传设备就是本机（或未知）才敢删
                if (r["device_id"] or "") not in ("", device_id):
                    continue
            cur = conn.execute(
                "UPDATE photos SET deleted=1, deleted_at=? WHERE id=? AND deleted=0",
                (now, r["id"]),
            )
            trashed += cur.rowcount
    return trashed


def all_photo_ids() -> set[str]:
    with connect() as conn:
        return {r["id"] for r in conn.execute("SELECT id FROM photos").fetchall()}


def row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    d["deleted"] = bool(d.get("deleted"))
    return d


# ---------------------------------------------------------------- 写入 -------

@_retry_write
def insert_photo(rec: dict) -> str:
    rec = dict(rec)
    rec["id"] = rec.get("id") or uuid.uuid4().hex
    rec.setdefault("uploaded_at", int(time.time() * 1000))
    cols = ",".join(rec.keys())
    marks = ",".join("?" for _ in rec)
    with connect() as conn:
        conn.execute(f"INSERT INTO photos ({cols}) VALUES ({marks})", list(rec.values()))
        conn.execute(
            "INSERT INTO devices(device_id, last_seen, uploaded) VALUES(?,?,1) "
            "ON CONFLICT(device_id) DO UPDATE SET last_seen=?, uploaded=uploaded+1",
            (rec.get("device_id") or "unknown", rec["uploaded_at"], rec["uploaded_at"]),
        )
    return rec["id"]


def find_by_sha(sha256: str, owner_id: str | None = None,
                include_deleted: bool = False) -> dict | None:
    """按内容查重。owner_id 为空表示不限归属 —— 用于"这份字节是否已在盘上"。

    include_deleted=True 时把回收站里的记录也找出来：用户删过又重传同一张，
    得复活原来那条，否则会撞 (owner_id, sha256) 唯一约束。
    """
    live = "" if include_deleted else " AND deleted=0"
    with connect() as conn:
        if owner_id is None:
            row = conn.execute(
                f"SELECT * FROM photos WHERE sha256=?{live}", (sha256,)
            ).fetchone()
        else:
            row = conn.execute(
                f"SELECT * FROM photos WHERE sha256=? AND owner_id=?{live}",
                (sha256, owner_id),
            ).fetchone()
    return row_to_dict(row) if row else None


def find_hashes(hashes: list[str], owner_id: str | None = None) -> set[str]:
    """批量存在性查询：返回已经存在于服务端的 sha256 集合。"""
    exist: set[str] = set()
    if not hashes:
        return exist
    owner_clause = " AND owner_id=?" if owner_id else ""
    with connect() as conn:
        for i in range(0, len(hashes), 500):
            chunk = hashes[i:i + 500]
            marks = ",".join("?" for _ in chunk)
            args = list(chunk) + ([owner_id] if owner_id else [])
            rows = conn.execute(
                f"SELECT sha256 FROM photos WHERE sha256 IN ({marks}) AND deleted=0{owner_clause}",
                args,
            ).fetchall()
            exist.update(r["sha256"] for r in rows)
    return exist


def prescreen_known(owner_id: str | None, items: list[dict]) -> set[str]:
    """粗筛：只按 (大小, 拍摄时间, 文件名) 判断是否已备份，不读文件内容。

    以前是把该用户所有记录 SELECT 出来建 set，几十万张时每次同步都要
    全表扫描 + 几十 MB 内存。这里分块用行值 IN 查询，命中直接返回。
    """
    if not items:
        return set()
    known: set[str] = set()
    owner_clause = " AND owner_id=?" if owner_id else ""
    with connect() as conn:
        for i in range(0, len(items), 120):
            chunk = items[i:i + 120]
            marks = ",".join("(?,?,?)" for _ in chunk)
            # 参数顺序必须和 SQL 里占位符出现的顺序一致：owner_id 在前面
            args: list = [owner_id] if owner_id else []
            for it in chunk:
                args += [it.get("size") or 0, it.get("taken_at") if it.get("taken_at") is not None else -1,
                         it.get("name") or ""]
            rows = conn.execute(
                "SELECT size, COALESCE(taken_at,-1) t, filename FROM photos "
                f"WHERE deleted=0{owner_clause} AND (size, COALESCE(taken_at,-1), filename) IN ({marks})",
                args,
            ).fetchall()
            for r in rows:
                known.add(f"{r['size']}|{r['t']}|{r['filename']}")
    return known


def user_usage_bytes(owner_id: str) -> int:
    with connect() as conn:
        row = conn.execute(
            "SELECT COALESCE(SUM(size),0) s FROM photos WHERE owner_id=? AND deleted=0",
            (owner_id,),
        ).fetchone()
    return int(row["s"] or 0)


# ---------------------------------------------------------------- 读取 -------

_LIST_COLS = (
    "id,sha256,filename,mime,kind,size,taken_at,uploaded_at,meta_source,owner_id,rel_dir,"
    "gps_lat,gps_lon,width,height,camera_make,camera_model,device_id,album,original_path"
)


def list_photos(
    limit: int = 200,
    offset: int = 0,
    kind: str | None = None,
    album: str | None = None,
    device_id: str | None = None,
    taken_from: int | None = None,
    taken_to: int | None = None,
    has_gps: bool | None = None,
    order: str = "taken_at DESC",
    owner_id: str | None = None,
    folder: str | None = None,
    q: str | None = None,
    viewer_device: str | None = None,
    with_total: bool = True,
) -> tuple[list[dict], int]:
    """owner_id=None 表示管理员视角看全部；否则只返回该用户的照片。

    folder 按手机上真实的目录（rel_dir，如 DCIM/Camera、Pictures/WeiXin）精确过滤。
    q 是服务端搜索（文件名/相册/设备/机型）——网页端以前只在前 300 条里过滤，
    老照片既看不到也搜不到。
    """
    where, args = ["deleted=0"], []
    if owner_id:
        where.append("owner_id=?"); args.append(owner_id)
    if kind:
        where.append("kind=?"); args.append(kind)
    if album:
        where.append("album=?"); args.append(album)
    if folder:
        if folder == "未分类":
            where.append("(rel_dir IS NULL OR rel_dir='')")
        else:
            where.append("rel_dir=?"); args.append(folder)
    if device_id:
        where.append("device_id=?"); args.append(device_id)
    if taken_from is not None:
        where.append("COALESCE(taken_at, uploaded_at) >= ?"); args.append(taken_from)
    if taken_to is not None:
        where.append("COALESCE(taken_at, uploaded_at) <= ?"); args.append(taken_to)
    if has_gps is True:
        where.append("gps_lat IS NOT NULL AND gps_lon IS NOT NULL")
    elif has_gps is False:
        where.append("(gps_lat IS NULL OR gps_lon IS NULL)")
    q = (q or "").strip()
    if q:
        needle = f"%{_escape_like(q)}%"
        where.append(
            "(filename LIKE ? ESCAPE '\\' OR album LIKE ? ESCAPE '\\' "
            "OR device_id LIKE ? ESCAPE '\\' OR camera_model LIKE ? ESCAPE '\\')"
        )
        args += [needle] * 4
    viewer_device = (viewer_device or "").strip()[:120]
    if viewer_device:
        # 这台设备上已经删掉的照片，不再出现在列表/还原清单里；
        # 其他设备或网页仍然能看到（墓碑是按设备记的）。
        where.append(
            "NOT EXISTS (SELECT 1 FROM photo_devices pd WHERE pd.photo_id=photos.id "
            "AND pd.device_id=? AND pd.deleted=1)"
        )
        args.append(viewer_device)

    order = order if order in ("taken_at DESC", "taken_at ASC", "uploaded_at DESC", "uploaded_at ASC") else "taken_at DESC"
    sql_where = " AND ".join(where)

    with connect() as conn:
        total = 0
        if with_total:      # COUNT 只算一次（第一页），翻页不做全表计数
            total = conn.execute(f"SELECT COUNT(*) c FROM photos WHERE {sql_where}", args).fetchone()["c"]
        # 排序必须带 id 兜底：只按 taken_at 排的话，拍摄时间相同（或为空）的照片
        # 顺序不确定，offset 翻页时同一张会在两页都出现 → 手机端列表拿到重复 id
        # → Compose 的 key 重复，直接 IllegalArgumentException 崩。
        rows = conn.execute(
            f"SELECT {_LIST_COLS} FROM photos WHERE {sql_where} "
            f"ORDER BY {order}, id LIMIT ? OFFSET ?", args + [limit, offset]
        ).fetchall()
    return [row_to_dict(r) for r in rows], total


def _escape_like(s: str) -> str:
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def get_photo(photo_id: str, include_deleted: bool = False) -> dict | None:
    sql = "SELECT * FROM photos WHERE id=?"
    if not include_deleted:
        sql += " AND deleted=0"
    with connect() as conn:
        row = conn.execute(sql, (photo_id,)).fetchone()
    return row_to_dict(row) if row else None


def soft_delete(photo_id: str, purge_file: bool = False, owner_id: str | None = None) -> bool:
    import os
    import time
    from .config import PHOTO_DIR
    own_clause = " AND owner_id=?" if owner_id else ""
    with connect() as conn:
        row = conn.execute(
            f"SELECT rel_path FROM photos WHERE id=?{own_clause}",
            (photo_id,) + ((owner_id,) if owner_id else ()),
        ).fetchone()
        if not row:
            return False
        conn.execute(
            "UPDATE photos SET deleted=1, deleted_at=? WHERE id=?",
            (int(time.time() * 1000), photo_id),
        )
        if purge_file and row["rel_path"]:
            # 同一份字节可能被多个用户持有（跨用户复用），还有人引用就不能删文件
            others = conn.execute(
                "SELECT COUNT(*) c FROM photos WHERE rel_path=? AND deleted=0", (row["rel_path"],)
            ).fetchone()["c"]
            if others == 0:
                try:
                    os.remove(PHOTO_DIR / row["rel_path"])
                except OSError:
                    pass
    return True


def soft_delete_many(photo_ids: list[str], owner_id: str | None = None) -> int:
    """批量软删除：一条 SQL 搞定，不再每个 id 开关一次连接、查一次权限。

    权限用 owner_id 子句兜住：非管理员只能删自己的，别人的 id 直接不命中。
    """
    if not photo_ids:
        return 0
    own_clause = " AND owner_id=?" if owner_id else ""
    marks = ",".join("?" for _ in photo_ids)
    args = [int(time.time() * 1000)] + list(photo_ids) + ([owner_id] if owner_id else [])
    with connect() as conn:
        cur = conn.execute(
            f"UPDATE photos SET deleted=1, deleted_at=? "
            f"WHERE id IN ({marks}) AND deleted=0{own_clause}",
            args,
        )
        return cur.rowcount


@_retry_write
def revive_photo(photo_id: str, rec: dict) -> None:
    """把回收站里的一条恢复成正常照片，并用新上传的元数据刷新它。"""
    fields = ("filename", "rel_path", "mime", "kind", "size", "taken_at", "uploaded_at",
              "meta_source", "gps_lat", "gps_lon", "gps_alt", "width", "height",
              "orientation", "camera_make", "camera_model", "album", "original_path",
              "rel_dir", "device_id", "owner_id")
    sets, args = ["deleted=0", "deleted_at=NULL"], []
    for k in fields:
        if k in rec:
            sets.append(f"{k}=?")
            args.append(rec[k])
    args.append(photo_id)
    with connect() as conn:
        conn.execute(f"UPDATE photos SET {','.join(sets)} WHERE id=?", args)


def list_albums(owner_id: str | None = None) -> list[dict]:
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        rows = conn.execute(
            "SELECT COALESCE(album,'') album, COUNT(*) c, MAX(COALESCE(taken_at,uploaded_at)) last "
            f"FROM photos WHERE deleted=0{own_clause} GROUP BY album ORDER BY last DESC", args
        ).fetchall()
    return [dict(r) for r in rows]


def list_folders(owner_id: str | None = None) -> list[dict]:
    """按手机上真实的目录分组（rel_dir），供手机端「按文件夹浏览」。

    空 rel_dir 的老照片归到「未分类」，不会因为它们没有目录信息就整个消失。
    """
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        rows = conn.execute(
            "SELECT COALESCE(rel_dir,'') folder, COUNT(*) c, "
            "MAX(COALESCE(taken_at,uploaded_at)) last "
            f"FROM photos WHERE deleted=0{own_clause} GROUP BY folder ORDER BY c DESC", args
        ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        if not d["folder"]:
            d["folder"] = "未分类"
        out.append(d)
    return out


def calendar_counts(taken_from: int, taken_to: int, owner_id: str | None = None) -> list[dict]:
    """按天聚合，用于手机端日历条。"""
    """按本地日期聚合（以前按 UTC 毫秒整除，东八区凌晨会算到前一天）。"""
    from datetime import datetime

    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        rows = conn.execute(
            "SELECT strftime('%Y-%m-%d', COALESCE(taken_at,uploaded_at)/1000, 'unixepoch', 'localtime') AS day, "
            "COUNT(*) c "
            "FROM photos WHERE deleted=0 AND COALESCE(taken_at,uploaded_at)>=? "
            f"AND COALESCE(taken_at,uploaded_at)<=?{own_clause} GROUP BY day ORDER BY day",
            (taken_from, taken_to) + args,
        ).fetchall()
    out = []
    for r in rows:
        try:
            day = int(datetime.strptime(r["day"], "%Y-%m-%d").timestamp() * 1000)
        except (TypeError, ValueError):
            continue
        out.append({"day": day, "count": r["c"]})
    return out


# -------------------------------------------------------------- 回收站 ------

def list_trash(limit: int = 200, owner_id: str | None = None, offset: int = 0) -> list[dict]:
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        rows = conn.execute(
            f"SELECT {_LIST_COLS}, deleted_at FROM photos WHERE deleted=1{own_clause} "
            "ORDER BY deleted_at DESC LIMIT ? OFFSET ?", args + (limit, offset)
        ).fetchall()
    return [row_to_dict(r) for r in rows]


def count_trash(owner_id: str | None = None) -> int:
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        return conn.execute(
            f"SELECT COUNT(*) c FROM photos WHERE deleted=1{own_clause}", args
        ).fetchone()["c"]


def restore_photo(photo_id: str, owner_id: str | None = None) -> bool:
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (photo_id,) + ((owner_id,) if owner_id else ())
    with connect() as conn:
        cur = conn.execute(
            f"UPDATE photos SET deleted=0, deleted_at=NULL WHERE id=?{own_clause}", args
        )
        if cur.rowcount:
            # 从回收站恢复后，各设备的"已删除"墓碑也要清掉，否则那台设备看不到
            conn.execute("UPDATE photo_devices SET deleted=0 WHERE photo_id=?", (photo_id,))
    return cur.rowcount > 0


def restore_many(photo_ids: list[str], owner_id: str | None = None) -> int:
    """批量从回收站恢复。恢复后清掉这些照片在各设备上的删除墓碑。"""
    if not photo_ids:
        return 0
    own_clause = " AND owner_id=?" if owner_id else ""
    marks = ",".join("?" for _ in photo_ids)
    args = list(photo_ids) + ([owner_id] if owner_id else [])
    with connect() as conn:
        cur = conn.execute(
            f"UPDATE photos SET deleted=0, deleted_at=NULL "
            f"WHERE id IN ({marks}) AND deleted=1{own_clause}",
            args,
        )
        if cur.rowcount:
            conn.execute(
                f"UPDATE photo_devices SET deleted=0 WHERE photo_id IN ({marks})",
                list(photo_ids),
            )
        return cur.rowcount


def purge_trash(owner_id: str | None = None) -> int:
    """彻底清掉回收站。

    顺序很关键：先在事务里删掉索引**并提交**，之后才动磁盘文件。
    反过来的话（边删文件边删记录），中途一旦中断就会留下
    「文件已经被删掉、记录还躺在回收站里」的状态 —— 看上去照片还在，
    实际已经不能恢复了，而且巡检也查不出来（巡检只看未删除的记录）。

    现在的顺序最坏只留下几个孤儿文件，而孤儿文件是能被巡检发现的。
    """
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()

    with connect() as conn:
        rows = conn.execute(
            f"SELECT rel_path FROM photos WHERE deleted=1{own_clause}", args
        ).fetchall()
        paths = {r["rel_path"] for r in rows if r["rel_path"]}
        # 一次查出这些文件还有没有活着的引用，不要每条记录查一次
        kept: set[str] = set()
        plist = list(paths)
        for i in range(0, len(plist), 400):
            chunk = plist[i:i + 400]
            marks = ",".join("?" for _ in chunk)
            kept.update(
                r["rel_path"] for r in conn.execute(
                    f"SELECT DISTINCT rel_path FROM photos WHERE rel_path IN ({marks}) AND deleted=0",
                    chunk,
                ).fetchall()
            )
        removed = conn.execute(f"DELETE FROM photos WHERE deleted=1{own_clause}", args).rowcount
        conn.execute(
            "DELETE FROM photo_devices WHERE photo_id NOT IN (SELECT id FROM photos)"
        )

    for rel in paths - kept:
        try:
            os.remove(PHOTO_DIR / rel)
        except OSError:
            pass
    return removed


def purge_trash_ids(photo_ids: list[str], owner_id: str | None = None) -> int:
    """只彻底清掉回收站里**指定的几条**（选择性删除），不是清空全部。

    为什么必须单独写：DELETE /api/photos/{id} 走的是 get_photo()，而它
    `WHERE deleted=0`，对已经进回收站的记录直接 404 —— 所以「永久删掉
    回收站里的某几条」原来根本没有入口。

    顺序与 purge_trash 完全一致：先删索引并提交，之后才动磁盘文件。
    """
    if not photo_ids:
        return 0
    own_clause = " AND owner_id=?" if owner_id else ""
    marks = ",".join("?" for _ in photo_ids)
    args = tuple(photo_ids) + ((owner_id,) if owner_id else ())

    with connect() as conn:
        rows = conn.execute(
            f"SELECT rel_path FROM photos WHERE id IN ({marks}) AND deleted=1{own_clause}",
            args,
        ).fetchall()
        paths = {r["rel_path"] for r in rows if r["rel_path"]}
        kept: set[str] = set()
        plist = list(paths)
        for i in range(0, len(plist), 400):
            chunk = plist[i:i + 400]
            cmark = ",".join("?" for _ in chunk)
            kept.update(
                r["rel_path"] for r in conn.execute(
                    f"SELECT DISTINCT rel_path FROM photos WHERE rel_path IN ({cmark}) AND deleted=0",
                    chunk,
                ).fetchall()
            )
        removed = conn.execute(
            f"DELETE FROM photos WHERE id IN ({marks}) AND deleted=1{own_clause}", args
        ).rowcount
        conn.execute(
            "DELETE FROM photo_devices WHERE photo_id NOT IN (SELECT id FROM photos)"
        )

    for rel in paths - kept:
        try:
            os.remove(PHOTO_DIR / rel)
        except OSError:
            pass
    return removed


# ---------------------------------------------------------- 完整性巡检 ------

def integrity_report(max_report: int = 200) -> dict:
    """比对索引与磁盘：文件丢了没、大小对不对、有没有没人认领的孤儿文件。

    注意：回收站里的照片文件**还在磁盘上**，它们的 rel_path 必须也算"有人认领"。
    以前只把 deleted=0 的记录放进 indexed，结果只要回收站非空，
    巡检就会把每一张已删除照片报成孤儿，healthy 永远是 false。
    """
    missing: list[dict] = []
    mismatch: list[dict] = []
    indexed: set[str] = set()
    total = 0

    with connect() as conn:
        rows = conn.execute(
            "SELECT id, filename, rel_path, size, deleted FROM photos"
        ).fetchall()
    for r in rows:
        p = PHOTO_DIR / r["rel_path"]
        indexed.add(os.path.normpath(str(p)))
        if r["deleted"]:
            continue        # 回收站：文件还在，但不参与"缺失/大小"统计
        total += 1
        try:
            st = p.stat()
        except OSError:
            missing.append({"id": r["id"], "filename": r["filename"]})
            continue
        if st.st_size != r["size"]:
            mismatch.append({"id": r["id"], "filename": r["filename"],
                             "expected": r["size"], "actual": st.st_size})

    orphan_count = 0
    orphans: list[str] = []
    if PHOTO_DIR.exists():
        for f in PHOTO_DIR.rglob("*"):
            if not f.is_file():
                continue
            if os.path.normpath(str(f)) in indexed:
                continue
            orphan_count += 1
            if len(orphans) < max_report:       # 只截断展示，计数必须完整
                orphans.append(str(f.relative_to(PHOTO_DIR)))

    return {
        "total": total,
        "ok": total - len(missing) - len(mismatch),
        "missing": missing[:max_report],
        "missing_count": len(missing),
        "mismatch": mismatch[:max_report],
        "mismatch_count": len(mismatch),
        "orphans": orphans,
        "orphan_count": orphan_count,
        "healthy": not missing and not mismatch and orphan_count == 0,
    }


def stats(owner_id: str | None = None) -> dict:
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        row = conn.execute(
            "SELECT COUNT(*) c, COALESCE(SUM(size),0) s, "
            "SUM(CASE WHEN kind='video' THEN 1 ELSE 0 END) v, "
            "SUM(CASE WHEN gps_lat IS NOT NULL AND gps_lon IS NOT NULL THEN 1 ELSE 0 END) g, "
            "MAX(uploaded_at) last "
            f"FROM photos WHERE deleted=0{own_clause}", args
        ).fetchone()
        if owner_id:
            devs = conn.execute(
                "SELECT * FROM devices d WHERE EXISTS("
                "SELECT 1 FROM photos p WHERE p.device_id=d.device_id AND p.owner_id=? AND p.deleted=0"
                ") ORDER BY last_seen DESC", args
            ).fetchall()
        else:
            devs = conn.execute("SELECT * FROM devices ORDER BY last_seen DESC").fetchall()
    return {
        "count": row["c"],
        "images": row["c"] - (row["v"] or 0),
        "videos": row["v"] or 0,
        "with_gps": row["g"] or 0,
        "bytes": row["s"],
        "last_upload_at": row["last"],
        "devices": [dict(d) for d in devs],
    }

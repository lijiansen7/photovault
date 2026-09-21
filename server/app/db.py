"""SQLite 元数据索引。

只存索引，不存图片本体；图片本体以原字节躺在 PHOTO_DIR 下。
"""

from __future__ import annotations

import sqlite3
import time
import uuid

from .config import DB_PATH

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

    UNIQUE(owner_id, sha256)
);

CREATE INDEX IF NOT EXISTS idx_photos_taken    ON photos(taken_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_uploaded ON photos(uploaded_at DESC);
CREATE INDEX IF NOT EXISTS idx_photos_album    ON photos(album);
CREATE INDEX IF NOT EXISTS idx_photos_device   ON photos(device_id);

CREATE TABLE IF NOT EXISTS devices (
    device_id   TEXT PRIMARY KEY,
    name        TEXT,
    last_seen   INTEGER,
    uploaded    INTEGER NOT NULL DEFAULT 0
);
"""


def connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init_db() -> None:
    # 顺序有讲究：老库上 owner_id 还不存在，涉及该列的索引必须等迁移完再建
    with connect() as conn:
        conn.executescript(SCHEMA)
    _migrate_owner_column()
    with connect() as conn:
        conn.execute("CREATE INDEX IF NOT EXISTS idx_photos_owner ON photos(owner_id)")
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
"""


def _migrate_owner_column() -> None:
    with connect() as conn:
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(photos)").fetchall()}
        if "owner_id" in cols:
            return
        conn.executescript(_MIGRATE_OWNER_SQL)


def claim_orphan_photos(owner_id: str) -> int:
    """把升级前上传的照片归到指定用户（首次启动时给管理员）。"""
    with connect() as conn:
        cur = conn.execute(
            "UPDATE photos SET owner_id=? WHERE owner_id IS NULL OR owner_id=''", (owner_id,)
        )
    return cur.rowcount


def row_to_dict(row: sqlite3.Row) -> dict:
    d = dict(row)
    d["deleted"] = bool(d.get("deleted"))
    return d


# ---------------------------------------------------------------- 写入 -------

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
) -> tuple[list[dict], int]:
    """owner_id=None 表示管理员视角看全部；否则只返回该用户的照片。

    folder 按手机上真实的目录（rel_dir，如 DCIM/Camera、Pictures/WeiXin）精确过滤。
    """
    where, args = ["deleted=0"], []
    if owner_id:
        where.append("owner_id=?"); args.append(owner_id)
    if kind:
        where.append("kind=?"); args.append(kind)
    if album:
        where.append("album=?"); args.append(album)
    if folder:
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

    order = order if order in ("taken_at DESC", "taken_at ASC", "uploaded_at DESC", "uploaded_at ASC") else "taken_at DESC"
    sql_where = " AND ".join(where)

    with connect() as conn:
        total = conn.execute(f"SELECT COUNT(*) c FROM photos WHERE {sql_where}", args).fetchone()["c"]
        # 排序必须带 id 兜底：只按 taken_at 排的话，拍摄时间相同（或为空）的照片
        # 顺序不确定，offset 翻页时同一张会在两页都出现 → 手机端列表拿到重复 id
        # → Compose 的 key 重复，直接 IllegalArgumentException 崩。
        rows = conn.execute(
            f"SELECT {_LIST_COLS} FROM photos WHERE {sql_where} "
            f"ORDER BY {order}, id LIMIT ? OFFSET ?", args + [limit, offset]
        ).fetchall()
    return [row_to_dict(r) for r in rows], total


def get_photo(photo_id: str) -> dict | None:
    with connect() as conn:
        row = conn.execute("SELECT * FROM photos WHERE id=? AND deleted=0", (photo_id,)).fetchone()
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
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        rows = conn.execute(
            "SELECT (COALESCE(taken_at, uploaded_at)/86400000)*86400000 AS day, COUNT(*) c "
            "FROM photos WHERE deleted=0 AND COALESCE(taken_at,uploaded_at)>=? "
            f"AND COALESCE(taken_at,uploaded_at)<=?{own_clause} GROUP BY day ORDER BY day",
            (taken_from, taken_to) + args,
        ).fetchall()
    return [{"day": r["day"], "count": r["c"]} for r in rows]


# -------------------------------------------------------------- 回收站 ------

def list_trash(limit: int = 200, owner_id: str | None = None) -> list[dict]:
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()
    with connect() as conn:
        rows = conn.execute(
            f"SELECT {_LIST_COLS}, deleted_at FROM photos WHERE deleted=1{own_clause} "
            "ORDER BY deleted_at DESC LIMIT ?", args + (limit,)
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
    return cur.rowcount > 0


def purge_trash(owner_id: str | None = None) -> int:
    """彻底清掉回收站。

    顺序很关键：先在事务里删掉索引**并提交**，之后才动磁盘文件。
    反过来的话（边删文件边删记录），中途一旦中断就会留下
    「文件已经被删掉、记录还躺在回收站里」的状态 —— 看上去照片还在，
    实际已经不能恢复了，而且巡检也查不出来（巡检只看未删除的记录）。

    现在的顺序最坏只留下几个孤儿文件，而孤儿文件是能被巡检发现的。
    """
    import os
    from .config import PHOTO_DIR
    own_clause = " AND owner_id=?" if owner_id else ""
    args = (owner_id,) if owner_id else ()

    victims: list[str] = []
    with connect() as conn:
        rows = conn.execute(
            f"SELECT id, rel_path FROM photos WHERE deleted=1{own_clause}", args
        ).fetchall()
        for r in rows:
            if not r["rel_path"]:
                continue
            kept = conn.execute(
                "SELECT COUNT(*) c FROM photos WHERE rel_path=? AND deleted=0 AND id<>?",
                (r["rel_path"], r["id"]),
            ).fetchone()["c"]
            if kept == 0:
                victims.append(r["rel_path"])
        removed = conn.execute(f"DELETE FROM photos WHERE deleted=1{own_clause}", args).rowcount

    for rel in victims:
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
    import os
    from .config import PHOTO_DIR
    if not photo_ids:
        return 0
    own_clause = " AND owner_id=?" if owner_id else ""
    marks = ",".join("?" for _ in photo_ids)
    args = tuple(photo_ids) + ((owner_id,) if owner_id else ())

    victims: list[str] = []
    with connect() as conn:
        rows = conn.execute(
            f"SELECT id, rel_path FROM photos WHERE id IN ({marks}) AND deleted=1{own_clause}",
            args,
        ).fetchall()
        for r in rows:
            if not r["rel_path"]:
                continue
            kept = conn.execute(
                "SELECT COUNT(*) c FROM photos WHERE rel_path=? AND deleted=0 AND id<>?",
                (r["rel_path"], r["id"]),
            ).fetchone()["c"]
            if kept == 0:
                victims.append(r["rel_path"])
        removed = conn.execute(
            f"DELETE FROM photos WHERE id IN ({marks}) AND deleted=1{own_clause}", args
        ).rowcount

    for rel in victims:
        try:
            os.remove(PHOTO_DIR / rel)
        except OSError:
            pass
    return removed


# ---------------------------------------------------------- 完整性巡检 ------

def integrity_report(max_report: int = 200) -> dict:
    """比对索引与磁盘：文件丢了没、大小对不对、有没有没人认领的孤儿文件。"""
    from .config import PHOTO_DIR
    missing: list[dict] = []
    mismatch: list[dict] = []
    indexed: set[str] = set()
    total = 0

    with connect() as conn:
        rows = conn.execute(
            "SELECT id, filename, rel_path, size FROM photos WHERE deleted=0"
        ).fetchall()
    for r in rows:
        total += 1
        p = PHOTO_DIR / r["rel_path"]
        indexed.add(str(p))
        if not p.exists():
            missing.append({"id": r["id"], "filename": r["filename"]})
        elif p.stat().st_size != r["size"]:
            mismatch.append({"id": r["id"], "filename": r["filename"],
                             "expected": r["size"], "actual": p.stat().st_size})

    orphans: list[str] = []
    if PHOTO_DIR.exists():
        for f in PHOTO_DIR.rglob("*"):
            if f.is_file() and str(f) not in indexed:
                orphans.append(str(f.relative_to(PHOTO_DIR)))
                if len(orphans) >= max_report:
                    break

    return {
        "total": total,
        "ok": total - len(missing) - len(mismatch),
        "missing": missing[:max_report],
        "missing_count": len(missing),
        "mismatch": mismatch[:max_report],
        "mismatch_count": len(mismatch),
        "orphans": orphans,
        "orphan_count": len(orphans),
        "healthy": not missing and not mismatch and not orphans,
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

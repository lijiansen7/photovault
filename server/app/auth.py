"""用户体系：密码哈希、签名 token、用户 CRUD。

刻意不引入任何第三方依赖 —— 密码用 hashlib.pbkdf2_hmac，token 用 hmac 签名，
这样 NAS 上装依赖时不会因为某个 wheel 拉不下来而部署失败。

token 形态：base64url(payload) + "." + base64url(HMAC-SHA256(payload))
payload 里带 token_epoch，改密码 / 禁用账号时 epoch+1，旧 token 立即全体失效。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
import uuid

from .config import ACCESS_TOKEN, DATA_DIR

PBKDF2_ITERS = 120_000
TOKEN_TTL_SEC = int(os.getenv("TOKEN_TTL_HOURS", "720")) * 3600   # 默认 30 天
ROLE_ADMIN = "admin"
ROLE_USER = "user"

MAX_FAILED_LOGINS = int(os.getenv("MAX_FAILED_LOGINS", "5"))
LOCKOUT_SEC = int(os.getenv("LOCKOUT_SEC", "600"))

USERS_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'user',
    display_name  TEXT,
    disabled      INTEGER NOT NULL DEFAULT 0,
    token_epoch   INTEGER NOT NULL DEFAULT 1,
    created_at    INTEGER NOT NULL,
    last_login_at INTEGER,
    failed_logins INTEGER NOT NULL DEFAULT 0,
    locked_until  INTEGER,
    must_change_password INTEGER NOT NULL DEFAULT 0
);
"""


# --------------------------------------------------------------- 签名密钥 -----

def _secret_path() -> str:
    return str(DATA_DIR / ".secret")


def _load_secret() -> bytes:
    env = os.getenv("SECRET_KEY")
    if env:
        return env.encode()
    p = _secret_path()
    if os.path.exists(p):
        with open(p, "rb") as f:
            return f.read().strip()
    # 首次启动生成并落盘，否则重启后所有人的登录态都会掉
    key = secrets.token_bytes(32)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(key)
    try:
        os.chmod(p, 0o600)
    except OSError:
        pass
    return key


SECRET: bytes = _load_secret()


def reset_secret() -> None:
    """所有 token 立刻作废（密钥泄露时用）。"""
    key = secrets.token_bytes(32)
    with open(_secret_path(), "wb") as f:
        f.write(key)
    globals()["SECRET"] = key


# ----------------------------------------------------------------- 编码 ------

def _b64e(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


# ------------------------------------------------------------- 密码哈希 ------

def hash_password(pw: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), salt, PBKDF2_ITERS)
    return f"pbkdf2_sha256${PBKDF2_ITERS}${_b64e(salt)}${_b64e(h)}"


def verify_password(pw: str, stored: str) -> bool:
    try:
        algo, iters, salt, digest = stored.split("$")
        if algo != "pbkdf2_sha256":
            return False
        calc = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), _b64d(salt), int(iters))
        return hmac.compare_digest(calc, _b64d(digest))
    except Exception:
        return False


# ----------------------------------------------------------------- token -----

def _sign(body: str) -> str:
    return _b64e(hmac.new(SECRET, body.encode("utf-8"), hashlib.sha256).digest())


def issue_token(user: sqlite3.Row | dict) -> str:
    payload = {
        "uid": user["id"],
        "u": user["username"],
        "r": user["role"],
        "e": int(user["token_epoch"]),
        "exp": int(time.time()) + TOKEN_TTL_SEC,
    }
    body = _b64e(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    return f"{body}.{_sign(body)}"


def parse_token(token: str) -> dict | None:
    """只做签名与过期校验，不查库（epoch 是否还有效由 resolve_token 判断）。"""
    if not token or "." not in token:
        return None
    body, _, sig = token.rpartition(".")
    if not body or not sig:
        return None
    if not hmac.compare_digest(_sign(body), sig):
        return None
    try:
        p = json.loads(_b64d(body))
    except Exception:
        return None
    if int(p.get("exp", 0)) < time.time():
        return None
    return p


# -------------------------------------------------------------- 用户 CRUD ----

def _conn() -> sqlite3.Connection:
    from .db import connect
    return connect()


_USER_COLS = ("id,username,role,display_name,disabled,token_epoch,created_at,last_login_at,"
              "must_change_password")


def public_user(row: sqlite3.Row | dict) -> dict:
    return {
        "id": row["id"],
        "username": row["username"],
        "role": row["role"],
        "display_name": row["display_name"] or row["username"],
        "disabled": bool(row["disabled"]),
        "created_at": row["created_at"],
        "last_login_at": row["last_login_at"],
        "must_change_password": bool(row["must_change_password"]) if "must_change_password" in row.keys() else False,
    }


def init_users_table() -> None:
    with _conn() as conn:
        conn.executescript(USERS_SCHEMA)
        # 老库补列：这几个字段是后来加的
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(users)").fetchall()}
        for col, ddl in (
            ("failed_logins", "ALTER TABLE users ADD COLUMN failed_logins INTEGER NOT NULL DEFAULT 0"),
            ("locked_until", "ALTER TABLE users ADD COLUMN locked_until INTEGER"),
            ("must_change_password",
             "ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0"),
        ):
            if col not in cols:
                conn.execute(ddl)


def count_users() -> int:
    with _conn() as conn:
        return conn.execute("SELECT COUNT(*) c FROM users").fetchone()["c"]


def create_user(
    username: str,
    password: str,
    role: str = ROLE_USER,
    display_name: str = "",
    must_change: bool = False,
) -> dict:
    username = username.strip().lower()
    if not username:
        raise ValueError("用户名不能为空")
    if not password or len(password) < 4:
        raise ValueError("密码至少 4 位")
    if role not in (ROLE_ADMIN, ROLE_USER):
        raise ValueError("角色只能是 admin 或 user")
    uid = uuid.uuid4().hex
    now = int(time.time() * 1000)
    with _conn() as conn:
        try:
            conn.execute(
                "INSERT INTO users(id,username,password_hash,role,display_name,"
                "token_epoch,created_at,must_change_password) VALUES(?,?,?,?,?,1,?,?)",
                (uid, username, hash_password(password), role, display_name or username, now,
                 1 if must_change else 0),
            )
        except sqlite3.IntegrityError:
            raise ValueError(f"用户名 {username} 已存在")
    return get_user(uid) or {}


def get_user(uid: str) -> dict | None:
    with _conn() as conn:
        row = conn.execute(f"SELECT {_USER_COLS} FROM users WHERE id=?", (uid,)).fetchone()
    return public_user(row) if row else None


def get_user_by_name(username: str) -> sqlite3.Row | None:
    with _conn() as conn:
        return conn.execute("SELECT * FROM users WHERE username=?", (username.strip().lower(),)).fetchone()


def list_users() -> list[dict]:
    with _conn() as conn:
        rows = conn.execute(f"SELECT {_USER_COLS} FROM users ORDER BY created_at").fetchall()
    return [public_user(r) for r in rows]


def update_user(uid: str, **fields) -> dict | None:
    allowed = {"role", "display_name", "disabled"}
    sets, args = [], []
    for k, v in fields.items():
        if k in allowed:
            sets.append(f"{k}=?")
            args.append(int(v) if k == "disabled" else v)
    if not sets:
        return get_user(uid)
    args.append(uid)
    with _conn() as conn:
        conn.execute(f"UPDATE users SET {','.join(sets)} WHERE id=?", args)
    return get_user(uid)


def set_password(uid: str, new_pw: str) -> None:
    """改密码时把 token_epoch 加一，让该用户已发出的所有 token 失效。"""
    if not new_pw or len(new_pw) < 4:
        raise ValueError("密码至少 4 位")
    with _conn() as conn:
        conn.execute(
            "UPDATE users SET password_hash=?, token_epoch=token_epoch+1, "
            "must_change_password=0, failed_logins=0, locked_until=NULL WHERE id=?",
            (hash_password(new_pw), uid),
        )


def require_password_change(uid: str) -> None:
    """管理员代为重置密码后，逼该用户下次登录时自己改一个。"""
    with _conn() as conn:
        conn.execute("UPDATE users SET must_change_password=1 WHERE id=?", (uid,))


def bump_epoch(uid: str) -> None:
    """禁用 / 删除账号时调用，踢掉已登录的会话。"""
    with _conn() as conn:
        conn.execute("UPDATE users SET token_epoch=token_epoch+1 WHERE id=?", (uid,))


def delete_user(uid: str) -> bool:
    with _conn() as conn:
        cur = conn.execute("DELETE FROM users WHERE id=?", (uid,))
    return cur.rowcount > 0


def authenticate(username: str, password: str) -> tuple[dict | None, str, bool]:
    """返回 (用户, 错误说明, 是否因锁定被拒)。错误说明为 "" 表示成功。

    连续输错 MAX_FAILED_LOGINS 次会锁 LOCKOUT_SEC 秒 —— 没有这道闸的话，
    固定用户名 admin 可以被无限次撞密码。
    """
    row = get_user_by_name(username)
    if not row:
        return None, "用户名或密码错误", False
    now_ms = int(time.time() * 1000)

    locked_until = row["locked_until"] or 0
    if locked_until > now_ms:
        left = (locked_until - now_ms) // 1000 + 1
        return None, f"账号已锁定，请 {left} 秒后再试", True

    if row["disabled"] or not verify_password(password, row["password_hash"]):
        with _conn() as conn:
            conn.execute("UPDATE users SET failed_logins=failed_logins+1 WHERE id=?", (row["id"],))
            n = conn.execute("SELECT failed_logins f FROM users WHERE id=?", (row["id"],)).fetchone()["f"]
            if n >= MAX_FAILED_LOGINS:
                conn.execute("UPDATE users SET locked_until=? WHERE id=?",
                             (now_ms + LOCKOUT_SEC * 1000, row["id"]))
                return None, f"连续输错 {MAX_FAILED_LOGINS} 次，账号锁定 {LOCKOUT_SEC // 60} 分钟", True
        return None, "用户名或密码错误", False

    with _conn() as conn:
        conn.execute(
            "UPDATE users SET failed_logins=0, locked_until=NULL, last_login_at=? WHERE id=?",
            (now_ms, row["id"]),
        )
    return public_user(row), "", False


def resolve_token(token: str) -> dict | None:
    """把 token 换成当前仍然有效的用户（校验签名 + 过期 + epoch + 是否禁用）。"""
    p = parse_token(token)
    if not p:
        return None
    row = get_user_by_name(p.get("u", ""))
    if not row or row["id"] != p.get("uid"):
        return None
    if row["disabled"]:
        return None
    if int(row["token_epoch"]) != int(p.get("e", -1)):
        return None       # 改过密码或被踢下线
    return public_user(row)


# ------------------------------------------------------- 首次启动的引导 ------

def ensure_bootstrap_admin() -> tuple[str, str] | None:
    """库里一个用户都没有时，建一个管理员。返回 (用户名, 初始密码) 供日志提示。"""
    init_users_table()
    if count_users() > 0:
        return None
    username = os.getenv("INIT_ADMIN_USER", "admin")
    password = os.getenv("INIT_ADMIN_PASSWORD") or ACCESS_TOKEN or "photovault"
    # 初始密码等于 ACCESS_TOKEN，是公开的默认值，必须逼着改掉
    create_user(username, password, ROLE_ADMIN, "管理员", must_change=True)
    return username, password

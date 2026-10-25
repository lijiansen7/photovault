"""安全回归：把几个"改一次就容易被改回去"的点钉住。

用法：python tools/security_check.py
全部通过返回 0，任何一条不过返回 1。
"""

from __future__ import annotations

import io
import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

# 必须在 import app 之前设好：模块一导入就会建库、建管理员
_TMP = Path(tempfile.mkdtemp(prefix="pv-sec-"))
os.environ["DATA_DIR"] = str(_TMP)
os.environ["INIT_ADMIN_USER"] = "admin"
os.environ["INIT_ADMIN_PASSWORD"] = "photovault"
os.environ["ALLOW_LEGACY_TOKEN"] = "0"
os.environ.setdefault("MIN_PASSWORD_LEN", "8")

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

fails: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("  [OK]   " if ok else "  [FAIL] ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        fails.append(name)


def jpeg() -> bytes:
    from PIL import Image

    buf = io.BytesIO()
    Image.new("RGB", (64, 48), (200, 60, 60)).save(buf, "JPEG")
    return buf.getvalue()


def main() -> int:
    c = TestClient(app)

    # --- 1. 旧版单一 Token 通道默认关闭 --------------------------------
    r = c.get("/api/photos", headers={"X-Token": "photovault"})
    check("旧 X-Token 通道默认不可用", r.status_code == 401, f"HTTP {r.status_code}")

    # --- 2. 初始管理员必须改密 ----------------------------------------
    tok = c.post("/api/auth/login", json={"username": "admin", "password": "photovault"}).json()["token"]
    r = c.get("/api/auth/me", headers={"Authorization": f"Bearer {tok}"})
    check("初始管理员被标记必须改密", r.json()["user"]["must_change_password"] is True, str(r.json()))
    H = {"Authorization": f"Bearer {tok}"}

    # --- 3. 密码强度 ---------------------------------------------------
    r = c.post("/api/auth/password", headers=H,
               json={"old_password": "photovault", "new_password": "1234"})
    check("4 位密码被拒", r.status_code == 400 and "至少" in r.json()["detail"], str(r.json()))

    r = c.post("/api/auth/password", headers=H,
               json={"old_password": "photovault", "new_password": "Str0ng-Pass-2026"})
    check("8 位以上可以改", r.status_code == 200, str(r.json())[:120])
    tok = r.json()["token"]
    H = {"Authorization": f"Bearer {tok}"}

    # --- 4. 用户名字符白名单（防存储型 XSS / 命令行注入） ---------------
    bad = [
        "<img src=x onerror=alert(1)>",
        "a');alert(1);//",
        "../etc/passwd",
        "has space",
        'back`tick',
    ]
    for name in bad:
        r = c.post("/api/admin/users", headers=H,
                   json={"username": name, "password": "Str0ng-Pass-2026"})
        check(f"拒绝非法用户名 {name[:18]!r}", r.status_code == 400, str(r.json())[:120])

    # 中文用户名要能建（只禁危险字符，不禁语言）
    r = c.post("/api/admin/users", headers=H,
               json={"username": "阿黎", "password": "Str0ng-Pass-2026"})
    check("中文用户名可以创建", r.status_code == 200, str(r.json())[:120])

    r = c.post("/api/admin/users", headers=H,
               json={"username": "alice", "password": "Str0ng-Pass-2026",
                     "display_name": "<script>alert(1)</script>张三"})
    check("合法用户名可创建", r.status_code == 200, str(r.json())[:120])
    dn = r.json()["user"]["display_name"]
    check("昵称里的控制字符被剥掉（<script> 原样保留但会按文本渲染）",
          "\n" not in dn and len(dn) <= 64, repr(dn))

    # --- 5. 只能看自己的照片 -------------------------------------------
    up = c.post("/api/upload", headers=H, data={"meta": "{}"},
                files={"file": ("a.jpg", jpeg(), "image/jpeg")})
    pid = up.json()["id"]
    atok = c.post("/api/auth/login", json={"username": "alice", "password": "Str0ng-Pass-2026"}).json()["token"]
    AH = {"Authorization": f"Bearer {atok}"}
    r = c.get(f"/api/photos/{pid}", headers=AH)
    check("普通用户读不到别人的照片", r.status_code == 404, f"HTTP {r.status_code}")
    r = c.get(f"/api/photos/{pid}/thumb?token={atok}")
    check("普通用户读不到别人的缩略图", r.status_code == 404, f"HTTP {r.status_code}")
    r = c.get(f"/api/photos/{pid}/file?token={atok}")
    check("普通用户读不到别人的原文件", r.status_code == 404, f"HTTP {r.status_code}")

    # --- 6. 缩略图不能被共享缓存存下来 ---------------------------------
    r = c.get(f"/api/photos/{pid}/thumb?token={tok}")
    cc = r.headers.get("cache-control", "")
    check("缩略图 Cache-Control 是 private", r.status_code == 200 and cc.startswith("private"), f"{r.status_code} {cc}")

    # --- 7. 上传只收媒体文件 -------------------------------------------
    r = c.post("/api/upload", headers=H, data={"meta": "{}"},
               files={"file": ("evil.sh", b"#!/bin/sh\nrm -rf /\n", "text/plain")})
    check("拒绝非媒体扩展名", r.status_code == 415, f"HTTP {r.status_code}")

    # --- 8. 连续输错会锁号 ---------------------------------------------
    for _ in range(5):
        c.post("/api/auth/login", json={"username": "alice", "password": "wrong-pass"})
    r = c.post("/api/auth/login", json={"username": "alice", "password": "Str0ng-Pass-2026"})
    check("连续输错 5 次后锁定（429）", r.status_code == 429, f"HTTP {r.status_code} {r.text[:80]}")

    # --- 9. 安全响应头 --------------------------------------------------
    r = c.get("/")
    h = {k.lower(): v for k, v in r.headers.items()}
    check("有 CSP", "content-security-policy" in h, str(h.get("content-security-policy"))[:80])
    check("有 nosniff", h.get("x-content-type-options") == "nosniff", str(h.get("x-content-type-options")))
    check("Referrer-Policy 是 no-referrer", h.get("referrer-policy") == "no-referrer",
          str(h.get("referrer-policy")))
    check("默认不允许被 iframe 套", h.get("x-frame-options") == "DENY", str(h.get("x-frame-options")))

    print()
    if fails:
        print(f"失败 {len(fails)} 项：")
        for f in fails:
            print("  -", f)
        return 1
    print("安全回归全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

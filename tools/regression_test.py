"""针对已修复问题的回归测试（进程内，不占端口）。

覆盖：
- 密码最短长度
- MIME 只按扩展名（.dng + text/html 不得变成可执行 HTML）
- 回收站非空时巡检不再误报孤儿
- 删除用户时“两人持有同一份字节”不再撞唯一约束
- 悬空记录（记录在、文件没了）重传后能恢复
- 粗筛键与上传 meta 一致时能命中
- 退出登录只吊销当前会话
- 媒体 cookie / 短期签名鉴权；API 不接受 cookie-only
"""
from __future__ import annotations

import hashlib
import io
import os
import shutil
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

TOKEN = "regression-token-123"
PASSWORD = "user-password-123"


def jpeg(color=(30, 90, 160)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (160, 120), color).save(buf, "JPEG")
    return buf.getvalue()


def main() -> int:
    data_dir = Path(tempfile.mkdtemp(prefix="pv-reg-"))
    os.environ["DATA_DIR"] = str(data_dir)
    os.environ["ACCESS_TOKEN"] = TOKEN
    os.environ["ALLOW_LEGACY_TOKEN"] = "0"
    os.environ["PBKDF2_ITERS"] = "120000"      # 测试别把时间花在哈希上

    from fastapi.testclient import TestClient
    from app.main import app

    c = TestClient(app)
    r = c.post("/api/auth/login", json={"username": "admin", "password": TOKEN})
    assert r.status_code == 200, r.text
    tok = r.json()["token"]
    H = {"Authorization": f"Bearer {tok}"}

    print("[1] 密码策略")
    r = c.post("/api/admin/users", headers=H,
               json={"username": "shortpw", "password": "1234567"})
    assert r.status_code == 400, r.text

    print("[2] MIME 不可由客户端伪造")
    r = c.post("/api/upload", headers=H,
               data={"meta": "{}"},
               files={"file": ("evil.dng", b"<html><script>alert(1)</script></html>", "text/html")})
    assert r.status_code == 200, r.text
    evil_id = r.json()["id"]
    r = c.get(f"/api/photos/{evil_id}/file", headers=H)
    assert r.headers["content-type"].startswith("application/octet-stream"), r.headers
    assert r.headers["content-disposition"].startswith("attachment"), r.headers

    print("[3] 回收站非空时巡检仍健康")
    c.delete(f"/api/photos/{evil_id}", headers=H)
    d = c.get("/api/admin/integrity", headers=H).json()
    assert d["healthy"] is True and d["orphan_count"] == 0, d

    print("[4] 用户共享同一份字节后删账号")
    raw = jpeg()
    sha = hashlib.sha256(raw).hexdigest()
    files = {"file": ("shared.jpg", raw, "image/jpeg")}
    a = c.post("/api/admin/users", headers=H,
               json={"username": "alpha", "password": PASSWORD}).json()["user"]
    b = c.post("/api/admin/users", headers=H,
               json={"username": "beta", "password": PASSWORD}).json()["user"]
    ta = c.post("/api/auth/login", json={"username": "alpha", "password": PASSWORD}).json()["token"]
    tb = c.post("/api/auth/login", json={"username": "beta", "password": PASSWORD}).json()["token"]
    ra = c.post("/api/upload", headers={"Authorization": f"Bearer {ta}"},
                data={"meta": "{}"}, files=files).json()
    rb = c.post("/api/upload", headers={"Authorization": f"Bearer {tb}"},
                data={"meta": "{}"}, files=files).json()
    assert ra["status"] == "created" and rb["status"] == "created", (ra, rb)
    before = c.get("/api/photos?limit=1", headers=H).json()["total"]
    r = c.delete(f"/api/admin/users/{b['id']}", headers=H)
    assert r.status_code == 200, r.text
    after = c.get("/api/photos?limit=1", headers=H).json()["total"]
    assert after == before, (before, after)

    print("[5] 悬空记录（文件被手工删掉）重传可恢复")
    rel = c.get(f"/api/photos/{ra['id']}", headers=H).json()["rel_path"]
    (data_dir / "photos" / rel).unlink()
    r = c.post("/api/upload", headers={"Authorization": f"Bearer {ta}"},
               data={"meta": "{}"}, files=files)
    assert r.json()["status"] == "created", r.text
    assert c.get(f"/api/photos/{r.json()['id']}/file", headers=H).status_code == 200

    print("[6] 粗筛键与上传 meta 一致时命中")
    photo_id = r.json()["id"]
    meta = c.get(f"/api/photos/{photo_id}", headers=H).json()
    payload = {"items": [{"key": "k", "size": meta["size"],
                          "taken_at": meta["taken_at"], "name": meta["filename"]}]}
    d = c.post("/api/photos/prescreen", headers=H, json=payload).json()
    assert d["known"] == ["k"], d

    print("[7] 手机删除同步：按设备打墓碑，另一台设备仍保留")
    raw2 = jpeg((10, 200, 90))
    files2 = {"file": ("del.jpg", raw2, "image/jpeg")}
    H_A = {"Authorization": f"Bearer {ta}"}
    r = c.post("/api/upload", headers=H_A,
               data={"meta": "{}", "device_id": "phoneA"}, files=files2)
    pid2 = r.json()["id"]
    assert c.get("/api/photos?limit=10&viewer_device=phoneA", headers=H_A).json()["total"] == 1
    r = c.post("/api/photos/forget", headers=H_A,
               json={"ids": [pid2], "device_id": "phoneA"})
    assert r.json()["trashed"] == 1, r.text
    assert c.get("/api/photos?limit=10&viewer_device=phoneA", headers=H_A).json()["total"] == 0
    assert c.get("/api/trash?limit=10", headers=H_A).json()["total"] >= 1
    # 从回收站恢复，再用另一台设备“备份”同一张 → 两台设备都有映射
    c.post(f"/api/photos/{pid2}/restore", headers=H)
    c.post("/api/upload", headers=H_A,
           data={"meta": "{}", "device_id": "phoneB"}, files=files2)
    r = c.post("/api/photos/forget", headers=H_A,
               json={"ids": [pid2], "device_id": "phoneA"})
    assert r.json()["trashed"] == 0, r.text
    assert c.get("/api/photos?limit=10&viewer_device=phoneA", headers=H_A).json()["total"] == 0
    assert c.get("/api/photos?limit=10&viewer_device=phoneB", headers=H_A).json()["total"] == 1

    print("[8] 退出登录只吊销当前会话")
    t1 = c.post("/api/auth/login", json={"username": "alpha", "password": PASSWORD}).json()["token"]
    t2 = c.post("/api/auth/login", json={"username": "alpha", "password": PASSWORD}).json()["token"]
    c.post("/api/auth/logout", headers={"Authorization": f"Bearer {t1}"})
    assert c.get("/api/auth/me", headers={"Authorization": f"Bearer {t1}"}).status_code == 401
    assert c.get("/api/auth/me", headers={"Authorization": f"Bearer {t2}"}).status_code == 200

    print("[9] 媒体 cookie 与短期签名")
    plain = TestClient(app)
    r = plain.post("/api/auth/login", json={"username": "admin", "password": TOKEN})
    assert r.status_code == 200 and plain.cookies.get("pv_token"), "登录应下发 HttpOnly cookie"
    # 只带 cookie 访问 API 必须 401（避免 CSRF），但媒体 GET 可以用
    assert plain.get("/api/photos?limit=1").status_code == 401
    assert plain.get(f"/api/photos/{photo_id}/thumb").status_code == 200
    link = c.get(f"/api/photos/{photo_id}/link", headers=H).json()["url"]
    s = link.split("sig=", 1)[1]
    r = TestClient(app).get(f"/api/photos/{photo_id}/file?sig={s}")
    assert r.status_code == 200, r.text
    r = TestClient(app).get(f"/api/photos/{photo_id}/file?sig=bad.bad.bad")
    assert r.status_code == 401, r.text

    print("[10] 缩略图缓存头是 private")
    r = c.get(f"/api/photos/{photo_id}/thumb", headers=H)
    assert "private" in r.headers.get("cache-control", ""), r.headers

    print("\n回归测试全部通过。")
    shutil.rmtree(data_dir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

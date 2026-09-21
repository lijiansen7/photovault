"""安全加固 / 防丢数据 / 备份加速 三项的验收测试。

自己拉起真实 uvicorn 打 HTTP，测完清理。
用法：python tools/e2e_hardening.py
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server"
sys.path.insert(0, str(Path(__file__).parent))

from e2e_live import make_photo, wait_port, free_port  # noqa: E402

BOOT = "boot-token-xyz"
PW_NEW = "new-admin-pw-99"
PW_C = "carol-pw-1234"
PW_V = "victim-pw-1234"

OK = "[OK]"
BAD = "[!!]"
_n = 0


def step(msg: str) -> None:
    global _n
    _n += 1
    print(f"  {OK} {_n:>2}. {msg}")


def bad(msg: str) -> Exception:
    print(f"  {BAD} {msg}")
    return AssertionError(msg)


def disk_files(data_dir: Path) -> int:
    photos = data_dir / "photos"
    return sum(1 for p in photos.rglob("*") if p.is_file()) if photos.exists() else 0


def main() -> int:
    data_dir = Path(tempfile.mkdtemp(prefix="pv-hard-"))
    port = free_port(0)
    env = dict(os.environ)
    env["DATA_DIR"] = str(data_dir)
    env["ACCESS_TOKEN"] = BOOT
    env["MAX_FAILED_LOGINS"] = "5"
    env["LOCKOUT_SEC"] = "600"
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app",
         "--host", "127.0.0.1", "--port", str(port), "--log-level", "warning"],
        cwd=str(SERVER), env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not wait_port(port):
            proc.kill()
            print(f"  {BAD} 服务没起来")
            return 1
        return run(f"http://127.0.0.1:{port}", data_dir)
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
        shutil.rmtree(data_dir, ignore_errors=True)


def run(base: str, data_dir: Path) -> int:
    c = httpx.Client(base_url=base, timeout=60.0)

    def login(u, p):
        return c.post("/api/auth/login", json={"username": u, "password": p})

    def hdr(t):
        return {"Authorization": f"Bearer {t}"}

    print(f"\n目标服务：{base}\n")

    # ---------- 1~4 强制改密 ----------
    r = login("admin", BOOT)
    admin_tok = r.json()["token"]
    assert c.get("/api/auth/me", headers=hdr(admin_tok)).json()["user"]["must_change_password"] is True, \
        bad("初始管理员应被标记必须改密")
    step("初始管理员登录后被标记「必须改密」")

    r = c.post("/api/auth/password", headers=hdr(admin_tok),
               json={"old_password": BOOT, "new_password": PW_NEW})
    assert r.status_code == 200, bad(f"改密失败 {r.text[:160]}")
    fresh = r.json()
    assert fresh.get("token"), bad("改密码应顺手补发一个新 token，否则用户改完立刻被登出")
    assert c.get("/api/auth/me", headers=hdr(admin_tok)).status_code == 401, bad("改密后旧 token 应失效")
    assert c.get("/api/auth/me", headers=hdr(fresh["token"])).json()["user"]["must_change_password"] is False, \
        bad("改密后 must_change 标记没清掉")
    admin_tok = fresh["token"]
    step("改密后旧 token 失效、新 token 直接可用、标记清除")

    assert login("admin", PW_NEW).status_code == 200, bad("新密码登录失败")
    step("新密码可以正常登录")

    # ---------- 5~6 登录限流 ----------
    c.post("/api/admin/users", headers=hdr(admin_tok),
           json={"username": "victim", "password": PW_V})
    for i in range(4):
        r = login("victim", "wrong-pw")
        assert r.status_code == 401, bad(f"第 {i+1} 次错密码应返回 401，实际 {r.status_code}")
    r = login("victim", "wrong-pw")
    assert r.status_code == 429, bad(f"第 5 次错密码应触发锁定（429），实际 {r.status_code}")
    assert "锁定" in r.json().get("detail", ""), bad(f"锁定提示不对：{r.text[:120]}")
    step("连续输错：前 4 次 401，第 5 次直接锁定（429）")

    r = login("victim", PW_V)
    assert r.status_code == 429, bad(f"锁定期间用正确密码也应被拦，实际 {r.status_code}")
    step("锁定期内即使密码正确也登不上")

    r = login("admin", PW_NEW)
    assert r.status_code == 200, bad("限流不应影响其他账号")
    step("限流只锁单个账号，不影响他人")

    # ---------- 7~9 粗筛加速 ----------
    c.post("/api/admin/users", headers=hdr(admin_tok),
           json={"username": "carol", "password": PW_C})
    carol_tok = login("carol", PW_C).json()["token"]

    photo = data_dir / "IMG_carol.jpg"
    make_photo(photo, tint=30)
    raw = photo.read_bytes()
    r = c.post("/api/upload", headers=hdr(carol_tok),
               data={"device_id": "phone-carol", "meta": "{}"},
               files={"file": (photo.name, raw, "image/jpeg")})
    assert r.json()["status"] == "created", bad("carol 上传失败")
    pid = r.json()["id"]
    meta = c.get(f"/api/photos/{pid}", headers=hdr(carol_tok)).json()

    prescreen = {
        "items": [
            {"key": "already", "size": meta["size"], "taken_at": meta["taken_at"], "name": meta["filename"]},
            {"key": "brand-new", "size": 999_999_123, "taken_at": 1234567890000, "name": "NEW.jpg"},
        ]
    }
    d = c.post("/api/photos/prescreen", headers=hdr(carol_tok), json=prescreen).json()
    assert "already" in d["known"], bad(f"已备份的应被粗筛认出：{d}")
    assert "brand-new" in d["unknown"], bad(f"新照片不该被认成已备份：{d}")
    step("粗筛：已备份的命中 known（不必读文件算 SHA256）")

    # 管理员视角看得到全部，同一份内容算已存在（本来就不用重传）
    d2 = c.post("/api/photos/prescreen", headers=hdr(admin_tok), json=prescreen).json()
    assert "already" in d2["known"], bad("管理员看得到全部，已存在的内容应命中 known")
    step("管理员粗筛覆盖所有人（内容已在盘上就不必重传）")

    # 另一个普通用户看不到 carol 的照片，对他来说就是新文件
    c.post("/api/admin/users", headers=hdr(admin_tok),
           json={"username": "dave", "password": "dave-pw-1234"})
    dave_tok = login("dave", "dave-pw-1234").json()["token"]
    d3 = c.post("/api/photos/prescreen", headers=hdr(dave_tok), json=prescreen).json()
    assert "already" in d3["unknown"], bad(f"别人的照片不该算 dave 已备份：{d3}")
    step("普通用户粗筛只认自己名下的（不会漏备份）")

    # ---------- 10~12 回收站 ----------
    r = c.delete(f"/api/photos/{pid}", headers=hdr(carol_tok))
    assert r.json().get("trashed") is True, bad(f"删除应进回收站：{r.text[:120]}")
    assert c.get("/api/photos?limit=50", headers=hdr(carol_tok)).json()["total"] == 0, \
        bad("删完正常列表应为空")
    trash = c.get("/api/trash", headers=hdr(carol_tok)).json()["items"]
    assert any(p["id"] == pid for p in trash), bad("回收站里找不到刚删的照片")
    step("删除先进回收站，正常列表已清空")

    c.post(f"/api/photos/{pid}/restore", headers=hdr(carol_tok))
    assert c.get("/api/photos?limit=50", headers=hdr(carol_tok)).json()["total"] == 1, \
        bad("恢复后应回到正常列表")
    assert disk_files(data_dir) == 1, bad("恢复后文件应在")
    step("一键恢复：照片回到列表，文件完好")

    # ---------- 13~14 清空回收站 ----------
    c.delete(f"/api/photos/{pid}", headers=hdr(carol_tok))
    before = disk_files(data_dir)
    d = c.delete("/api/trash", headers=hdr(carol_tok)).json()
    assert d["removed"] >= 1, bad(f"清空回收站没删掉东西：{d}")
    assert disk_files(data_dir) == before - 1, bad("清空后磁盘文件应真的被删掉")
    step("清空回收站：索引记录删除，磁盘文件也真正释放")

    # ---------- 删掉之后再传同一张：必须能复活 ----------
    p3 = data_dir / "IMG_revive.jpg"
    make_photo(p3, tint=90)
    raw3 = p3.read_bytes()
    r = c.post("/api/upload", headers=hdr(carol_tok),
               data={"device_id": "phone-carol", "meta": "{}"},
               files={"file": (p3.name, raw3, "image/jpeg")})
    rid = r.json()["id"]
    c.delete(f"/api/photos/{rid}", headers=hdr(carol_tok))
    assert c.get("/api/photos?limit=50", headers=hdr(carol_tok)).json()["total"] == 0, \
        bad("删除后列表应为空")

    r2 = c.post("/api/upload", headers=hdr(carol_tok),
                data={"device_id": "phone-carol", "meta": "{}"},
                files={"file": (p3.name, raw3, "image/jpeg")})
    assert r2.status_code == 200, bad(f"重传失败 {r2.status_code}：{r2.text[:180]}")
    assert r2.json()["status"] == "restored", bad(f"应复活原记录，实际 {r2.json()['status']}")
    assert r2.json()["id"] == rid, bad("复活应复用原来那条记录的 id")
    assert c.get("/api/photos?limit=50", headers=hdr(carol_tok)).json()["total"] == 1, \
        bad("复活后应该能在列表里看到")
    assert disk_files(data_dir) >= 1, bad("复活时应把文件重新落盘")
    step("删除后重传同一张：复活原记录（不会撞唯一约束，文件重新落盘）")

    # ---------- 15~16 完整性巡检 ----------
    d = c.get("/api/admin/integrity", headers=hdr(admin_tok)).json()
    assert d["healthy"] is True, bad(f"空库巡检应健康：{d}")
    step("完整性巡检：干净状态下 healthy=true")

    # 再传一张，然后从磁盘偷偷删掉，模拟文件丢失
    photo2 = data_dir / "IMG_lost.jpg"
    make_photo(photo2, tint=60)
    r = c.post("/api/upload", headers=hdr(carol_tok),
               data={"device_id": "phone-carol", "meta": "{}"},
               files={"file": (photo2.name, photo2.read_bytes(), "image/jpeg")})
    lost_id = r.json()["id"]
    rel = c.get(f"/api/photos/{lost_id}", headers=hdr(carol_tok)).json()
    target = data_dir / "photos" / rel["rel_path"]
    os.remove(target)

    d = c.get("/api/admin/integrity", headers=hdr(admin_tok)).json()
    assert d["healthy"] is False, bad("文件被删了巡检还说健康")
    assert d["missing_count"] >= 1, bad(f"应报出缺失文件：{d}")
    step("人为删掉磁盘文件后，巡检立刻报出缺失（能发现静默丢数据）")

    print("\n全部通过 —— 加固与防丢这三块都验过了。\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

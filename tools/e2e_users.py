"""多用户隔离专项测试：登录、越权、管理员视角、改密踢下线、账号删除。

自己拉起真实 uvicorn 打 HTTP，测完清理。
用法：python tools/e2e_users.py
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server"
sys.path.insert(0, str(Path(__file__).parent))

from e2e_live import make_photo, wait_port, free_port  # noqa: E402

BOOT_TOKEN = "boot-token-xyz"      # 初始管理员密码 = ACCESS_TOKEN
PW_A = "alice-pw-1234"
PW_B = "bob-pw-1234"
PW_A2 = "alice-new-5678"

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
    data_dir = Path(tempfile.mkdtemp(prefix="pv-users-"))
    port = free_port(0)
    env = dict(os.environ)
    env["DATA_DIR"] = str(data_dir)
    env["ACCESS_TOKEN"] = BOOT_TOKEN
    env["PYTHONIOENCODING"] = "utf-8"

    cmd = [sys.executable, "-m", "uvicorn", "app.main:app",
           "--host", "127.0.0.1", "--port", str(port), "--log-level", "warning"]
    print(f"启动临时服务：端口 {port}，数据目录 {data_dir}")
    proc = subprocess.Popen(cmd, cwd=str(SERVER), env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
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

    def login(u: str, p: str):
        return c.post("/api/auth/login", json={"username": u, "password": p})

    def hdr(tok: str):
        return {"Authorization": f"Bearer {tok}"}

    print(f"\n目标服务：{base}\n")

    # 1-3 管理员引导登录
    r = login("admin", BOOT_TOKEN)
    assert r.status_code == 200, bad(f"初始管理员登录失败 {r.status_code} {r.text[:160]}")
    admin_tok = r.json()["token"]
    assert r.json()["user"]["role"] == "admin", bad("初始管理员角色不对")
    step("初始管理员 admin 登录成功（密码即 ACCESS_TOKEN）")

    r = login("admin", "wrong-password")
    assert r.status_code == 401, bad("错误密码居然通过了")
    step("错误密码被拒")

    r = c.get("/api/auth/me", headers=hdr(admin_tok))
    assert r.json()["is_admin"] is True, bad("admin 身份不对")
    step("token 换取身份成功（is_admin=true）")

    # 4-5 建两个普通用户
    for name, pw in (("alice", PW_A), ("bob", PW_B)):
        r = c.post("/api/admin/users", headers=hdr(admin_tok),
                   json={"username": name, "password": pw, "display_name": name.capitalize()})
        assert r.status_code == 200, bad(f"创建 {name} 失败 {r.text[:160]}")
    step("管理员创建 alice / bob 两个普通用户")

    r = c.post("/api/admin/users", headers=hdr(admin_tok),
               json={"username": "alice", "password": "whatever"})
    assert r.status_code == 400, bad("重名用户居然创建成功")
    step("重名用户被拒")

    # 6 普通用户登录
    r = login("alice", PW_A)
    alice_tok = r.json()["token"]
    bob_tok = login("bob", PW_B).json()["token"]
    step("alice / bob 各自登录拿到 token")

    # 7-8 两人上传同一张照片
    photo = data_dir / "IMG_shared.jpg"
    make_photo(photo)
    raw = photo.read_bytes()
    sha = hashlib.sha256(raw).hexdigest()
    files = {"file": (photo.name, raw, "image/jpeg")}

    r = c.post("/api/upload", headers=hdr(alice_tok),
               data={"device_id": "phone-alice", "meta": "{}"}, files=files)
    assert r.json()["status"] == "created", bad(f"alice 上传失败 {r.text[:160]}")
    alice_pid = r.json()["id"]
    step("alice 上传成功")

    r = c.post("/api/upload", headers=hdr(bob_tok),
               data={"device_id": "phone-bob", "meta": "{}"}, files=files)
    assert r.json()["status"] == "created", bad(f"bob 上传同一份字节失败 {r.text[:160]}")
    bob_pid = r.json()["id"]
    assert bob_pid != alice_pid, bad("bob 应该有自己的一条记录")
    step("bob 上传同一份字节：各自一条记录")

    n_files = disk_files(data_dir)
    assert n_files == 1, bad(f"内容应只落盘一份，实际 {n_files} 份")
    step(f"磁盘上只存了 {n_files} 份文件（跨用户复用，没浪费空间）")

    # 9 隔离：alice 只看得到自己的
    r = c.get("/api/photos?limit=100", headers=hdr(alice_tok))
    items = r.json()["items"]
    assert r.json()["total"] == 1, bad(f"alice 应只看到 1 张，实际 {r.json()['total']}")
    assert items[0]["id"] == alice_pid, bad("alice 看到的不是自己的照片")
    step("alice 的列表只含自己的照片（隔离生效）")

    # 10 越权访问
    r = c.get(f"/api/photos/{bob_pid}", headers=hdr(alice_tok))
    assert r.status_code == 404, bad(f"alice 竟然能读到 bob 的照片：{r.status_code}")
    r = c.get(f"/api/photos/{bob_pid}/file", headers=hdr(alice_tok))
    assert r.status_code == 404, bad("alice 竟然能下载 bob 的原文件")
    r = c.get(f"/api/photos/{bob_pid}/thumb", headers=hdr(alice_tok))
    assert r.status_code == 404, bad("alice 竟然能拿到 bob 的缩略图")
    step("alice 读 bob 的照片 / 原文件 / 缩略图 全部 404")

    r = c.delete(f"/api/photos/{bob_pid}", headers=hdr(alice_tok))
    assert r.status_code == 404, bad("alice 竟然能删 bob 的照片")
    step("alice 删 bob 的照片也被拒")

    # 11 管理员看全部
    r = c.get("/api/photos?limit=100", headers=hdr(admin_tok))
    assert r.json()["total"] == 2, bad(f"管理员应看到 2 条，实际 {r.json()['total']}")
    step("管理员看到全部 2 条记录")

    r = c.get(f"/api/photos?owner={bob_tok and ''}", headers=hdr(admin_tok))
    assert r.status_code == 200, bad("owner 过滤参数出错")

    # 12 非管理员不能进用户管理
    r = c.get("/api/admin/users", headers=hdr(alice_tok))
    assert r.status_code == 403, bad(f"alice 竟能访问用户管理：{r.status_code}")
    step("alice 访问用户管理接口被拒（403）")

    # 13 改密码 → 旧 token 失效
    r = c.post("/api/auth/password", headers=hdr(alice_tok),
               json={"old_password": PW_A, "new_password": PW_A2})
    assert r.status_code == 200, bad(f"alice 改密失败 {r.text[:160]}")
    r = c.get("/api/auth/me", headers=hdr(alice_tok))
    assert r.status_code == 401, bad("改密后旧 token 还能用")
    step("alice 改密码后，已发出的旧 token 立即失效")

    r = login("alice", PW_A2)
    assert r.status_code == 200, bad("新密码登录失败")
    alice_tok = r.json()["token"]
    step("alice 用新密码重新登录成功")

    # 14 禁用账号 → 踢下线
    bob_uid = next(u["id"] for u in c.get("/api/admin/users", headers=hdr(admin_tok)).json()["items"]
                   if u["username"] == "bob")
    c.patch(f"/api/admin/users/{bob_uid}", headers=hdr(admin_tok), json={"disabled": True})
    assert login("bob", PW_B).status_code == 401, bad("禁用后仍能登录")
    assert c.get("/api/auth/me", headers=hdr(bob_tok)).status_code == 401, bad("禁用后旧 token 仍有效")
    step("管理员禁用 bob 后，其 token 与登录立即失效")

    # 15 删除账号 → 照片转给管理员，不丢数据
    r = c.delete(f"/api/admin/users/{bob_uid}", headers=hdr(admin_tok))
    assert r.status_code == 200, bad(f"删除 bob 失败 {r.text[:160]}")
    r = c.get("/api/photos?limit=100", headers=hdr(admin_tok))
    assert r.json()["total"] == 2, bad(f"删账号后照片应转给管理员，实际 {r.json()['total']}")
    assert disk_files(data_dir) == 1, bad("删除账号把文件也删了")
    step("删除 bob 账号：照片转给管理员，文件完好")

    # 16 兼容通道
    r = c.post("/api/ping", headers={"X-Token": BOOT_TOKEN})
    assert r.status_code == 200, bad("旧版单 Token 兼容失效")
    step("旧版单 Token 仍能连（已装 App 不会突然失效）")

    # 17 无 token
    assert c.get("/api/photos").status_code == 401, bad("无 token 竟然能访问")
    step("不带 token 一律 401")

    print("\n全部通过 —— 用户隔离与权限这块没问题。\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

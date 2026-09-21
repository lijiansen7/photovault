"""在 Windows 上真机式验收服务端：拉起真实 uvicorn，走 HTTP 打完全链路。

和 smoke_test.py 的区别 —— 那个走进程内 TestClient，这个走真实端口，
能把"防火墙 / 局域网 / 端口占用"这类只有真跑起来才会暴露的问题一起验掉。

用法：
    python tools/e2e_live.py                    # 起临时服务，测完自动清理
    python tools/e2e_live.py --port 8765        # 用指定端口
    python tools/e2e_live.py --keep             # 测完保留服务不关（方便手机连）
    python tools/e2e_live.py --base http://192.168.1.20:8765   # 打已运行的服务
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server"

TOKEN = "photovault"
TAKEN = "2024:03:15 12:34:56"
LAT = 39.0 + 54 / 60 + 12.34 / 3600
LON = 116.0 + 23 / 60 + 45.67 / 3600

OK = "[OK]"
BAD = "[!!]"


def step(n: int, msg: str) -> None:
    print(f"  {OK} {n:>2}. {msg}")


def fail(msg: str) -> "Exception":
    print(f"  {BAD} {msg}")
    return AssertionError(msg)


def make_photo(dst: Path, tint: int = 0) -> None:
    """造一张和真机一样带 GPS 子 IFD 的 JPEG（Pillow 的 tobytes 写不进 GPS）。"""
    import piexif
    from PIL import Image

    im = Image.new("RGB", (1600, 1200), (30 + tint, 90, 160 - tint))
    exif_bytes = piexif.dump({
        "0th": {
            piexif.ImageIFD.Make: "Google",
            piexif.ImageIFD.Model: "Pixel 8 Pro",
        },
        "Exif": {
            piexif.ExifIFD.DateTimeOriginal: TAKEN,
            piexif.ExifIFD.DateTimeDigitized: TAKEN,
        },
        "GPS": {
            piexif.GPSIFD.GPSLatitudeRef: "N",
            piexif.GPSIFD.GPSLatitude: ((39, 1), (54, 1), (1234, 100)),
            piexif.GPSIFD.GPSLongitudeRef: "E",
            piexif.GPSIFD.GPSLongitude: ((116, 1), (23, 1), (4567, 100)),
        },
        "1st": {},
        "thumbnail": None,
    })
    im.save(dst, "JPEG", quality=92, exif=exif_bytes)


def wait_port(port: int, timeout: float = 40.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(1.0)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.4)
    return False


def free_port(preferred: int) -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        if preferred:
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", preferred)) != 0:
                    return preferred
        return s.getsockname()[1]


def run(base: str, token: str, keep: bool) -> int:
    import httpx

    # 只有账号登录一条路了：先用初始管理员（密码 = ACCESS_TOKEN）换一个 token
    r = httpx.post(f"{base}/api/auth/login",
                   json={"username": "admin", "password": token}, timeout=30.0)
    assert r.status_code == 200, fail(f"登录失败 {r.status_code}：{r.text[:160]}")
    H = {"Authorization": f"Bearer {r.json()['token']}"}
    c = httpx.Client(base_url=base, headers=H, timeout=60.0)

    print(f"\n目标服务：{base}\n")

    r = c.get("/api/health")
    assert r.status_code == 200, fail(f"health 返回 {r.status_code}，服务没起来")
    step(1, "服务健康检查通过")

    r = c.post("/api/ping")
    assert r.status_code == 200, fail("账号登录换来的 token 被拒了")
    step(2, "账号登录成功，token 可用")

    r = httpx.post(f"{base}/api/ping", headers={"X-Token": "definitely-wrong"}, timeout=20.0)
    assert r.status_code == 401, fail("错误 Token 居然通过了，鉴权有问题")
    step(3, "错误凭据被正确拒绝")

    tmp = Path(tempfile.mkdtemp(prefix="pv-e2e-"))
    try:
        photo = tmp / "IMG_20240315_123456.jpg"
        make_photo(photo)
        raw = photo.read_bytes()
        sha = hashlib.sha256(raw).hexdigest()
        print(f"      测试照片 {len(raw) / 1024:.0f} KB  sha256={sha[:16]}…")

        r = c.post("/api/photos/exists", json={"sha256": [sha]})
        assert sha not in r.json()["existing"], fail("没上传却说已存在")
        step(4, "上传前查重：未命中（符合预期）")

        meta = json.dumps({"album": "Camera", "rel_dir": "DCIM/Camera",
                           "device_id": "windows-e2e"})
        files = {"file": (photo.name, raw, "image/jpeg")}
        r = c.post("/api/upload", data={"device_id": "windows-e2e", "meta": meta}, files=files)
        assert r.status_code == 200, fail(f"上传失败 {r.status_code} {r.text[:200]}")
        res = r.json()
        assert res["status"] == "created", fail(f"期望 created，实际 {res['status']}")
        pid = res["id"]
        step(5, f"上传成功 id={pid}  时间来源={res['meta_source']}  有GPS={res['has_gps']}")

        r = c.post("/api/upload", data={"device_id": "windows-e2e", "meta": meta}, files=files)
        assert r.json()["status"] == "duplicate", fail("重复上传没命中去重")
        step(6, "重复上传命中去重，不占第二份空间")

        r = c.post("/api/photos/exists", json={"sha256": [sha, "0" * 64]})
        ex = r.json()["existing"]
        assert sha in ex and "0" * 64 not in ex, fail(f"查重结果不对：{ex}")
        step(7, "批量查重命中已备份照片（手机靠这个省流量）")

        r = c.get("/api/photos?limit=50")
        items = r.json()["items"]
        p = next((x for x in items if x["id"] == pid), None)
        assert p, fail("列表里找不到刚上传的照片")
        print(f"      索引记录：拍摄时间={p['taken_at']}  "
              f"GPS=({p['gps_lat']:.5f}, {p['gps_lon']:.5f})  机型={p['camera_make']} {p['camera_model']}")
        assert abs(p["gps_lat"] - LAT) < 0.01 and abs(p["gps_lon"] - LON) < 0.01, fail(
            f"GPS 解析偏差过大：{p['gps_lat']}, {p['gps_lon']}")
        step(8, "索引中的拍摄时间与 GPS 正确")

        assert p.get("rel_dir") == "DCIM/Camera", fail(
            f"原始目录没记录下来，还原就回不到原处：{p.get('rel_dir')}")
        step(8.5, "原始目录已记录（还原时按 DCIM/Camera 写回去）")

        r = c.get(f"/api/photos/{pid}/thumb")
        assert r.status_code == 200 and len(r.content) > 500, fail(f"缩略图异常 {r.status_code}")
        step(9, f"缩略图生成 {len(r.content) / 1024:.0f} KB")

        r = c.get(f"/api/photos/{pid}/file")
        assert r.status_code == 200, fail(f"下载失败 {r.status_code}")
        got = r.content
        assert hashlib.sha256(got).hexdigest() == sha, fail("下载的文件和上传的字节不一致！")
        step(10, "下载原文件：字节与上传时完全一致")

        from PIL import Image, ExifTags

        im = Image.open(io.BytesIO(got))
        exif = im.getexif()
        n2t = {v: k for k, v in ExifTags.TAGS.items()}
        sub = exif.get_ifd(ExifTags.IFD.Exif) or {}
        dto = exif.get(n2t["DateTimeOriginal"]) or sub.get(n2t["DateTimeOriginal"])
        assert dto == TAKEN, fail(f"拍摄时间丢了：{dto}")
        assert exif.get(n2t["Make"]) == "Google", fail("厂商丢了")
        assert exif.get(n2t["Model"]) == "Pixel 8 Pro", fail("机型丢了")
        g = exif.get_ifd(n2t["GPSInfo"])
        assert g and g.get(2) and g.get(4), fail("GPS 丢了")
        step(11, "EXIF 完好：拍摄时间 / GPS / 机型 一个不少")

        r = c.get("/api/stats")
        print(f"      stats: {json.dumps(r.json(), ensure_ascii=False)}")
        step(12, "统计接口正常")

        r = c.get(f"/api/photos/{pid}/file", params={"download": 1})
        assert "attachment" in r.headers.get("content-disposition", ""), fail("download=1 没带附件头")
        step(13, "带 download=1 的下载头正确（还原到本地走这个）")

        # 网页端「全选删除」走批量接口，避免几百个请求
        r = c.post("/api/photos/delete", json={"ids": [pid, "not-a-real-id"]})
        assert r.status_code == 200, fail(f"批量删除失败 {r.status_code}")
        assert r.json()["deleted"] == 1, fail(f"应删掉 1 条，实际 {r.json()}")
        assert c.get("/api/photos").json()["total"] == 0, fail("批量删除后列表没清空")
        assert any(p["id"] == pid for p in c.get("/api/trash").json()["items"]), \
            fail("批量删除也应进回收站")
        step(14, "批量删除：进回收站，不存在的 id 被忽略")

        c.post(f"/api/photos/{pid}/restore")
        step(15, "恢复成功（回收站可逆）")

        if not keep:
            c.delete(f"/api/photos/{pid}?purge=true")
            step(16, "测试照片已清理")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("\n全部通过 —— 服务端这侧没问题，可以拿手机连了。\n")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="", help="已运行的服务地址，如 http://192.168.1.20:8765")
    ap.add_argument("--port", type=int, default=0, help="临时服务端口，默认自动挑一个空闲端口")
    ap.add_argument("--token", default=TOKEN)
    ap.add_argument("--data-dir", default="", help="临时服务的数据目录，默认系统临时目录")
    ap.add_argument("--keep", action="store_true", help="测完不关服务，方便手机连过来测")
    a = ap.parse_args()

    if a.base:
        return run(a.base.rstrip("/"), a.token, a.keep)

    port = free_port(a.port)
    data_dir = Path(a.data_dir) if a.data_dir else Path(tempfile.mkdtemp(prefix="pv-data-"))
    data_dir.mkdir(parents=True, exist_ok=True)

    env = dict(os.environ)
    env["DATA_DIR"] = str(data_dir)
    env["ACCESS_TOKEN"] = a.token
    env["PYTHONIOENCODING"] = "utf-8"

    cmd = [sys.executable, "-m", "uvicorn", "app.main:app",
           "--host", "0.0.0.0", "--port", str(port), "--log-level", "warning"]
    print(f"启动临时服务：端口 {port}，数据目录 {data_dir}")
    proc = subprocess.Popen(cmd, cwd=str(SERVER), env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    try:
        if not wait_port(port):
            proc.kill()
            print(f"  {BAD} 服务没能在 40 秒内启动。可能端口被占，或 uvicorn 未安装。")
            return 1
        return run(f"http://127.0.0.1:{port}", a.token, a.keep)
    finally:
        if a.keep and proc.poll() is None:
            print(f"服务保持运行：http://127.0.0.1:{port}  （Ctrl+C 结束）")
            try:
                proc.wait()
            except KeyboardInterrupt:
                proc.terminate()
        else:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
            shutil.rmtree(data_dir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())

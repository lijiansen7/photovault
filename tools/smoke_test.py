"""端到端冒烟测试：上传 -> 索引 -> 查重 -> 缩略图 -> 下载原文件 -> EXIF 是否完好。

不需要真的起服务，直接用 FastAPI 的进程内 TestClient 跑。
运行：python tools/smoke_test.py
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import tempfile
from pathlib import Path

import sys

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server"
sys.path.insert(0, str(SERVER))
sys.path.insert(0, str(ROOT / "server"))

TOKEN = "smoke-token-123"
TAKEN = "2024:03:15 12:34:56"
LAT = 39.0 + 54 / 60 + 12.34 / 3600
LON = 116.0 + 23 / 60 + 45.67 / 3600


def make_photo(dst: Path, tint: int = 0) -> None:
    """用 piexif 造一张带完整 EXIF（含 GPS 子 IFD）的照片。

    注意：Pillow 的 Exif.tobytes() 不序列化 GPS 子目录，必须用 piexif 才能造出
    和真机一样的带位置信息的 JPEG。
    """
    import piexif
    from PIL import Image

    im = Image.new("RGB", (1600, 1200), (30 + tint, 90, 160 - tint))
    zeroth = {
        piexif.ImageIFD.Make: "Google",
        piexif.ImageIFD.Model: "Pixel 8 Pro",
    }
    exif_ifd = {
        piexif.ExifIFD.DateTimeOriginal: TAKEN,
        piexif.ExifIFD.DateTimeDigitized: TAKEN,
        piexif.ExifIFD.PixelXDimension: 1600,
        piexif.ExifIFD.PixelYDimension: 1200,
    }
    gps = {
        piexif.GPSIFD.GPSLatitudeRef: "N",
        piexif.GPSIFD.GPSLatitude: ((39, 1), (54, 1), (1234, 100)),
        piexif.GPSIFD.GPSLongitudeRef: "E",
        piexif.GPSIFD.GPSLongitude: ((116, 1), (23, 1), (4567, 100)),
    }
    exif_bytes = piexif.dump({"0th": zeroth, "Exif": exif_ifd, "GPS": gps, "1st": {}, "thumbnail": None})
    im.save(dst, "JPEG", quality=92, exif=exif_bytes)


def main() -> int:
    data_dir = Path(tempfile.mkdtemp(prefix="pv-smoke-"))
    os.environ["DATA_DIR"] = str(data_dir)
    os.environ["ACCESS_TOKEN"] = TOKEN

    import logging

    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("photovault").setLevel(logging.WARNING)

    try:
        # 环境变量必须在 import 之前设置
        from fastapi.testclient import TestClient
        from app.main import app

        client = TestClient(app)
        H = {"X-Token": TOKEN}

        photo = data_dir / "IMG_20240315_123456.jpg"
        make_photo(photo)
        raw = photo.read_bytes()
        sha = hashlib.sha256(raw).hexdigest()
        print(f"[1] 造测试照片 {len(raw)} bytes sha256={sha[:16]}…")

        r = client.post("/api/ping", headers=H)
        assert r.status_code == 200, r.text
        print("[2] 鉴权 ping OK")

        r = client.post("/api/ping", headers={"X-Token": "wrong"})
        assert r.status_code == 401, r.text
        print("[3] 错误 Token 被拒 OK")

        meta = json.dumps({"taken_at": 1710491696000, "album": "Camera"})
        files = {"file": (photo.name, raw, "image/jpeg")}
        r = client.post("/api/upload", data={"device_id": "android-smoke", "meta": meta},
                        files=files, headers=H)
        assert r.status_code == 200, r.text
        res = r.json()
        assert res["status"] == "created", res
        pid = res["id"]
        print(f"[4] 上传成功 id={pid} meta_source={res['meta_source']} has_gps={res['has_gps']}")

        r = client.post("/api/upload", data={"device_id": "android-smoke", "meta": meta},
                        files=files, headers=H)
        assert r.json()["status"] == "duplicate", r.text
        print("[5] 重复上传命中去重 OK")

        r = client.post("/api/photos/exists", json={"sha256": [sha, "0" * 64]}, headers=H)
        exist = r.json()["existing"]
        assert sha in exist and "0" * 64 not in exist, exist
        print("[6] 批量查重 OK")

        r = client.get("/api/photos?limit=50", headers=H)
        lst = r.json()
        assert lst["total"] == 1, lst
        p = lst["items"][0]
        print(f"[7] 列表 OK 拍摄时间={p['taken_at']} GPS=({p['gps_lat']:.5f},{p['gps_lon']:.5f}) "
              f"机型={p['camera_make']} {p['camera_model']}")

        assert abs(p["gps_lat"] - LAT) < 0.01 and abs(p["gps_lon"] - LON) < 0.01, p
        print("[8] GPS 解析精度 OK")

        r = client.get(f"/api/photos/{pid}/thumb", headers=H)
        assert r.status_code == 200 and len(r.content) > 500, (r.status_code, len(r.content))
        print(f"[9] 缩略图 OK {len(r.content)} bytes")

        r = client.get(f"/api/photos/{pid}/file", headers=H)
        assert r.status_code == 200, r.status_code
        assert hashlib.sha256(r.content).hexdigest() == sha, "下载的文件与上传的不一致！"
        print("[10] 下载原文件字节完全一致 OK")

        from PIL import Image, ExifTags

        im = Image.open(io.BytesIO(r.content))
        ex = im.getexif()
        n2t = {v: k for k, v in ExifTags.TAGS.items()}
        ex_sub = ex.get_ifd(ExifTags.IFD.Exif) or {}
        dto = ex.get(n2t["DateTimeOriginal"]) or ex_sub.get(n2t["DateTimeOriginal"])
        assert dto == TAKEN, dto
        assert ex.get(n2t["Make"]) == "Google"
        assert ex.get(n2t["Model"]) == "Pixel 8 Pro"
        g = ex.get_ifd(n2t["GPSInfo"])
        assert g and g.get(1) == "N" and g.get(3) == "E" and g.get(2) and g.get(4)
        print("[11] EXIF 拍摄时间 / GPS / 机型 全部原样保留 OK")

        # 不依赖客户端元数据时，服务端能否自己从 EXIF 解出时间
        photo2 = data_dir / "PXL_20240315_123456.jpg"
        make_photo(photo2, tint=40)
        r = client.post("/api/upload",
                        data={"device_id": "android-smoke", "meta": "{}"},
                        files={"file": (photo2.name, photo2.read_bytes(), "image/jpeg")},
                        headers=H)
        res3 = r.json()
        assert res3["status"] == "created", res3
        assert res3["meta_source"] == "exif", res3
        assert res3["taken_at"] == 1710506096000, res3
        print(f"[13] 纯服务端解析拍摄时间 OK -> {res3['taken_at']}")

        r = client.get("/api/stats", headers=H)
        print("[12/14] stats:", json.dumps(r.json(), ensure_ascii=False))

        r = client.delete(f"/api/photos/{res3['id']}", headers=H)
        assert r.status_code == 200, r.text
        r = client.get("/api/photos?limit=50", headers=H)
        assert r.json()["total"] == 1, r.json()
        print("[15] 删除（软删）后列表正确 OK")

        print("\n全部通过 ✅")
        return 0
    finally:
        shutil.rmtree(data_dir, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())

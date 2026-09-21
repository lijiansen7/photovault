"""元数据抽取与缩略图生成。

铁律：只"读"原文件，绝不改写原文件。原文件字节 100% 保持手机上传时的样子，
所以 EXIF（拍摄时间 / GPS / 相机型号 / 方向）天然完整保留，还原时天然带回去。
"""

from __future__ import annotations

import io
import logging
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from .config import THUMB_DIR, THUMB_SIZE, guess_mime, kind_of

log = logging.getLogger("photovault.media")

# HEIC/HEIF 支持（安卓主流格式）
try:  # pragma: no cover
    import pillow_heif

    pillow_heif.register_heif_opener()
    _HEIF = True
except Exception:  # pragma: no cover
    _HEIF = False

from PIL import Image, ExifTags  # noqa: E402

_EXIF = {v: k for k, v in ExifTags.TAGS.items()}
_GPS = {v: k for k, v in ExifTags.GPSTAGS.items()}

# 从文件名兜底解析时间：IMG_20240315_123456 / PXL_20240315_123456789 / Screenshot_20240315-123456 / VID_20240315_123456
_FN_PATTERNS = [
    re.compile(r"(?P<y>20\d{2})(?P<m>\d{2})(?P<d>\d{2})[_-]?(?P<H>\d{2})(?P<M>\d{2})(?P<S>\d{2})"),
    re.compile(r"(?P<y>20\d{2})(?P<m>\d{2})(?P<d>\d{2})"),
]


def _exif_dt(value: str) -> int | None:
    """'2024:03:15 12:34:56' -> ms epoch。EXIF 存的是本地时间，无时区，按本地时区解析。"""
    if not value:
        return None
    value = str(value).strip().replace("-", ":")
    for fmt in ("%Y:%m:%d %H:%M:%S", "%Y:%m:%d %H:%M", "%Y:%m:%d"):
        try:
            dt = datetime.strptime(value[:len(fmt) + 2].strip(), fmt)
            return int(dt.replace(tzinfo=timezone.utc).timestamp() * 1000)
        except ValueError:
            continue
    return None


def _sub_ifd(exif) -> dict:
    """取 Exif 子 IFD（0x8769），取不到就返回空字典。"""
    try:
        return exif.get_ifd(ExifTags.IFD.Exif) or {}
    except Exception:
        return {}


def _first_not_none(*values):
    for v in values:
        if v:
            return v
    return None


def _rational_to_float(v) -> float | None:
    try:
        if isinstance(v, tuple):
            num, den = v
            return float(num) / float(den) if den else None
        return float(v)
    except Exception:
        return None


def _gps_to_deg(dms, ref: str) -> float | None:
    """度分秒 -> 十进制度。"""
    if not dms or len(dms) < 3:
        return None
    d = _rational_to_float(dms[0])
    m = _rational_to_float(dms[1])
    s = _rational_to_float(dms[2])
    if None in (d, m, s):
        return None
    val = d + m / 60.0 + s / 3600.0
    if ref and ref.upper() in ("S", "W"):
        val = -val
    return val


def _from_filename(filename: str) -> int | None:
    for pat in _FN_PATTERNS:
        m = pat.search(filename or "")
        if not m:
            continue
        g = m.groupdict()
        try:
            dt = datetime(
                int(g["y"]), int(g["m"]), int(g["d"]),
                int(g.get("H") or 0), int(g.get("M") or 0), int(g.get("S") or 0),
            )
            return int(dt.replace(tzinfo=timezone.utc).timestamp() * 1000)
        except ValueError:
            continue
    return None


def extract_metadata(path: Path, filename: str) -> dict:
    """从文件本体抽取元数据。失败时静默降级，绝不抛异常中断上传。"""
    out: dict = {}
    mime = guess_mime(filename)
    kind = kind_of(filename, mime)

    try:
        with Image.open(path) as im:
            out["width"], out["height"] = im.size
            exif = im.getexif()
            if exif:
                out["orientation"] = exif.get(_EXIF.get("Orientation"))

                # DateTimeOriginal 在 Exif 子 IFD 里，不同相机/Pillow 版本可能出现在顶层或子层
                sub_ifd = _sub_ifd(exif)
                dt_raw = _first_not_none(
                    exif.get(_EXIF.get("DateTimeOriginal")),
                    sub_ifd.get(_EXIF.get("DateTimeOriginal")),
                    exif.get(_EXIF.get("DateTimeDigitized")),
                    sub_ifd.get(_EXIF.get("DateTimeDigitized")),
                    exif.get(_EXIF.get("DateTime")),
                )
                taken = _exif_dt(dt_raw)
                if taken:
                    out["taken_at"] = taken
                    out["meta_source"] = "exif"

                out["camera_make"] = exif.get(_EXIF.get("Make"))
                out["camera_model"] = exif.get(_EXIF.get("Model"))

                gps_ifd = exif.get_ifd(_EXIF.get("GPSInfo")) if _EXIF.get("GPSInfo") in exif else None
                if gps_ifd:
                    lat = _gps_to_deg(gps_ifd.get(_GPS.get("GPSLatitude")),
                                      gps_ifd.get(_GPS.get("GPSLatitudeRef")))
                    lon = _gps_to_deg(gps_ifd.get(_GPS.get("GPSLongitude")),
                                      gps_ifd.get(_GPS.get("GPSLongitudeRef")))
                    if lat is not None and lon is not None:
                        out["gps_lat"], out["gps_lon"] = lat, lon
                    alt = _rational_to_float(gps_ifd.get(_GPS.get("GPSAltitude")))
                    if alt is not None:
                        out["gps_alt"] = alt
    except Exception as e:
        log.debug("exif parse failed for %s: %s", filename, e)

    if "taken_at" not in out:
        fn_t = _from_filename(filename)
        if fn_t:
            out["taken_at"] = fn_t
            out["meta_source"] = "filename"

    out.setdefault("meta_source", "unknown")
    return out


def merge_client_metadata(base: dict, client: dict) -> dict:
    """客户端（安卓 MediaStore / ExifInterface）提供的元数据优先级更高：
    手机拿到的 DATE_TAKEN 与 GPS 往往比服务端解析更准，且视频只有客户端才有。
    """
    merged = dict(base)
    for key in ("taken_at", "gps_lat", "gps_lon", "gps_alt", "camera_make",
                "camera_model", "width", "height", "orientation", "album",
                "original_path", "rel_dir"):
        v = client.get(key)
        if v not in (None, "", 0, 0.0):
            merged[key] = v
            if key in ("taken_at", "gps_lat", "gps_lon") and merged.get("meta_source") != "client":
                merged["meta_source"] = "client"
    return merged


# --------------------------------------------------------------- 缩略图 ------

def thumbnail_path(photo_id: str) -> Path:
    return THUMB_DIR / f"{photo_id[:2]}" / f"{photo_id}.jpg"


def save_thumbnail_bytes(photo_id: str, data: bytes) -> bool:
    """直接落一张客户端（手机）抽好的封面帧。

    服务端没 ffmpeg，解不了视频帧，所以视频本来是没有缩略图的。
    但**手机本地有原视频**，抽一张首帧代价极小 —— 上传时顺手带上来，
    存成文件后 get_or_make_thumbnail 会先命中它直接返回，视频也就有封面了。
    """
    if not data:
        return False
    dst = thumbnail_path(photo_id)
    try:
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_suffix(".jpg.tmp")
        tmp.write_bytes(data)
        tmp.replace(dst)          # 原子替换，避免读到写了一半的图
        return True
    except OSError:
        log.warning("保存封面失败 %s", photo_id, exc_info=True)
        return False


def get_or_make_thumbnail(photo_id: str, src: Path, filename: str, size: int = THUMB_SIZE) -> bytes | None:
    dst = thumbnail_path(photo_id)
    if dst.exists():
        try:
            return dst.read_bytes()
        except OSError:
            pass

    try:
        if kind_of(filename) == "video" or not _HEIF and filename.lower().endswith((".heic", ".heif")):
            return None
        with Image.open(src) as im:
            im = im.copy()
            try:
                from PIL import ImageOps
                im = ImageOps.exif_transpose(im)
            except Exception:
                pass
            im.thumbnail((size, size), Image.LANCZOS)
            if im.mode not in ("RGB", "L"):
                im = im.convert("RGB")
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=82, optimize=True)
            data = buf.getvalue()
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(data)
        return data
    except Exception as e:
        log.debug("thumbnail failed for %s: %s", photo_id, e)
        return None

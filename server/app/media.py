"""元数据抽取与缩略图生成。

铁律：只"读"原文件，绝不改写原文件。原文件字节 100% 保持手机上传时的样子，
所以 EXIF（拍摄时间 / GPS / 相机型号 / 方向）天然完整保留，还原时天然带回去。
"""

from __future__ import annotations

import io
import logging
import os
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .config import THUMB_DIR, THUMB_SIZE, guess_mime, kind_of

log = logging.getLogger("photovault.media")

# 解一张图要吃掉 宽×高×4 字节内存。有人丢一张 50000×50000 的图上来，
# PIL 会老老实实去解，NAS 上那点内存当场就没了 —— 超过这个尺寸直接跳过处理。
MAX_PIXELS = 150_000_000

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


def _parse_exif_offset(value) -> timezone | None:
    """EXIF OffsetTimeOriginal 形如 '+08:00' / '-0500'，转成 timezone。无效返回 None。"""
    if not value:
        return None
    m = re.match(r"\s*([+-])(\d{2}):?(\d{2})\s*$", str(value))
    if not m:
        return None
    # 有的相机写 '+08:00'，时区偏移允许到 ±14 小时；超出范围的直接丢弃
    hh, mm = int(m.group(2)), int(m.group(3))
    if hh > 14 or mm > 59:
        return None
    delta = timedelta(hours=hh, minutes=mm)
    return timezone(-delta if m.group(1) == "-" else delta)


_TZ = None


def _tz():
    """EXIF_TZ 优先，否则容器系统时区。结果缓存，避免每张照片都查一次。"""
    global _TZ
    if _TZ is not None:
        return _TZ
    env = os.getenv("EXIF_TZ", "").strip()
    if env:
        try:
            from zoneinfo import ZoneInfo

            _TZ = ZoneInfo(env)
            return _TZ
        except Exception:  # 时区名写错不该让上传失败
            log.warning("EXIF_TZ=%r 无效，回落到系统时区", env)
    _TZ = datetime.now().astimezone().tzinfo or timezone.utc
    return _TZ


def _exif_dt(value: str, offset: str | None = None) -> int | None:
    """'2024:03:15 12:34:56' -> ms epoch。

    EXIF 的 DateTimeOriginal 是**拍摄地的墙上时间**，不带时区。按 UTC 解析会让
    所有照片整体偏移（UTC+8 的用户固定晚 8 小时），在按 DATE_TAKEN 排序的相册
    （PixelOS、Google Photos）里尤其明显。

    时区优先级：
      1. EXIF 自带的 OffsetTimeOriginal（0x9011），最准 —— 出国拍的照片也对
      2. EXIF_TZ 环境变量
      3. 容器系统时区（compose 里已设 TZ=Asia/Shanghai）
    """
    if not value:
        return None
    value = str(value).strip().replace("-", ":")
    tz = _parse_exif_offset(offset) or _tz()
    for fmt in ("%Y:%m:%d %H:%M:%S", "%Y:%m:%d %H:%M", "%Y:%m:%d"):
        try:
            dt = datetime.strptime(value[:len(fmt) + 2].strip(), fmt)
            return int(dt.replace(tzinfo=tz).timestamp() * 1000)
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
    """文件名里的时间同样按**本地时区**理解（截图、录屏没有 EXIF，只能靠这个）。

    IMG_20240315_123456 里的 123456 是手机当时的墙上时间，不是 UTC。
    """
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
            return int(dt.replace(tzinfo=_tz()).timestamp() * 1000)
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
            w, h = im.size
            if w * h > MAX_PIXELS:
                log.warning("跳过超大图元数据：%s %dx%d", filename, w, h)
                return {"meta_source": "unknown"}
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
                # EXIF 3.0 起会带时区偏移（OffsetTimeOriginal），有就优先用它，
                # 这样出国拍的照片也能还原出正确的绝对时刻
                offset_raw = _first_not_none(
                    sub_ifd.get(_EXIF.get("OffsetTimeOriginal", 0x9011)),
                    sub_ifd.get(_EXIF.get("OffsetTimeDigitized", 0x9012)),
                    exif.get(_EXIF.get("OffsetTimeOriginal", 0x9011)),
                    exif.get(_EXIF.get("OffsetTime", 0x9010)),
                )
                taken = _exif_dt(dt_raw, offset_raw)
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
            if im.size[0] * im.size[1] > MAX_PIXELS:
                log.warning("跳过超大图缩略图：%s %s", photo_id, im.size)
                return None
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

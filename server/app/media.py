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

from PIL import Image, ExifTags

from .config import THUMB_DIR, THUMB_SIZE, kind_of

log = logging.getLogger("photovault.media")

# 防解压炸弹：一张声称 10 万×10 万像素的图片能把内存吃光。
Image.MAX_IMAGE_PIXELS = 80_000_000

# HEIC/HEIF 支持（安卓主流格式）
try:  # pragma: no cover
    import pillow_heif

    pillow_heif.register_heif_opener()
    _HEIF = True
except Exception:  # pragma: no cover
    _HEIF = False

_EXIF = {v: k for k, v in ExifTags.TAGS.items()}
_GPS = {v: k for k, v in ExifTags.GPSTAGS.items()}

# 从文件名兜底解析时间：IMG_20240315_123456 / PXL_20240315_123456789 / Screenshot_20240315-123456 / VID_20240315_123456
_FN_PATTERNS = [
    re.compile(r"(?P<y>20\d{2})(?P<m>\d{2})(?P<d>\d{2})[_-]?(?P<H>\d{2})(?P<M>\d{2})(?P<S>\d{2})"),
    re.compile(r"(?P<y>20\d{2})(?P<m>\d{2})(?P<d>\d{2})"),
]

_OFFSET_RE = re.compile(r"^\s*([+-])(\d{2}):?(\d{2})\s*$")


def _parse_offset(value) -> timezone | None:
    """EXIF 的 OffsetTimeOriginal，如 '+08:00'。没有就返回 None。"""
    if not value:
        return None
    m = _OFFSET_RE.match(str(value))
    if not m:
        return None
    sign = -1 if m.group(1) == "-" else 1
    delta = timedelta(hours=int(m.group(2)), minutes=int(m.group(3)))
    return timezone(sign * delta)


def _exif_dt(value: str, offset: timezone | None = None) -> int | None:
    """'2024:03:15 12:34:56' -> ms epoch。

    EXIF 里的 DateTimeOriginal 是**拍摄地的本地时间，没有时区**。
    以前这里按 UTC 解析，东八区会整整偏 8 小时：轻则时间显示错，
    重则还原时把错误的 DATE_TAKEN 写回系统相册，凌晨的照片还会跑到前一天。
    优先用 OffsetTimeOriginal；没有就按服务端本地时区（TZ）解释。
    """
    if not value:
        return None
    value = str(value).strip().replace("-", ":")
    for fmt in ("%Y:%m:%d %H:%M:%S", "%Y:%m:%d %H:%M", "%Y:%m:%d"):
        try:
            dt = datetime.strptime(value[:len(fmt) + 2].strip(), fmt)
        except ValueError:
            continue
        if offset is not None:
            dt = dt.replace(tzinfo=offset)
            return int(dt.timestamp() * 1000)
        # naive datetime.timestamp() 按系统本地时区解释，正是 EXIF 的语义
        return int(dt.timestamp() * 1000)
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
            return int(dt.timestamp() * 1000)   # 同样按本地时间
        except ValueError:
            continue
    return None


def extract_metadata(path: Path, filename: str) -> dict:
    """从文件本体抽取元数据。失败时静默降级，绝不抛异常中断上传。"""
    out: dict = {}

    try:
        with Image.open(path) as im:
            out["width"], out["height"] = im.size
            exif = im.getexif()
            if exif:
                out["orientation"] = exif.get(_EXIF.get("Orientation"))

                # DateTimeOriginal 在 Exif 子 IFD 里，不同相机/Pillow 版本可能出现在顶层或子层
                sub_ifd = _sub_ifd(exif)
                offset = _parse_offset(
                    _first_not_none(
                        sub_ifd.get(_EXIF.get("OffsetTimeOriginal")),
                        exif.get(_EXIF.get("OffsetTimeOriginal")),
                        sub_ifd.get(_EXIF.get("OffsetTime")),
                        exif.get(_EXIF.get("OffsetTime")),
                    )
                )
                dt_raw = _first_not_none(
                    exif.get(_EXIF.get("DateTimeOriginal")),
                    sub_ifd.get(_EXIF.get("DateTimeOriginal")),
                    exif.get(_EXIF.get("DateTimeDigitized")),
                    sub_ifd.get(_EXIF.get("DateTimeDigitized")),
                    exif.get(_EXIF.get("DateTime")),
                )
                taken = _exif_dt(dt_raw, offset)
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

    注意 `taken_at` 的时区语义必须和客户端一致（都是"本地墙钟时间对应的 epoch"），
    否则粗筛键会永远对不上，每次同步都要重新哈希整库。
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

def thumbnail_path(photo_id: str, size: int = THUMB_SIZE) -> Path:
    """按尺寸分目录存，网格只要小图，别让 512 的图占满手机流量。"""
    size = _norm_size(size)
    name = f"{photo_id}.jpg" if size == THUMB_SIZE else f"{photo_id}@{size}.jpg"
    return THUMB_DIR / f"{photo_id[:2]}" / name


def _norm_size(size: int) -> int:
    try:
        size = int(size)
    except Exception:
        return THUMB_SIZE
    return size if 64 <= size <= 1024 else THUMB_SIZE


def _atomic_write(dst: Path, data: bytes) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_name(dst.name + ".tmp")
    try:
        tmp.write_bytes(data)
        os.replace(tmp, dst)
    finally:
        tmp.unlink(missing_ok=True)


def save_thumbnail_bytes(photo_id: str, data: bytes, size: int = THUMB_SIZE) -> bool:
    """直接落一张客户端（手机）抽好的封面帧。

    服务端没 ffmpeg，解不了视频帧，所以视频本来是没有缩略图的。
    但**手机本地有原视频**，抽一张首帧代价极小 —— 上传时顺手带上来，
    存成文件后 get_or_make_thumbnail 会先命中它直接返回，视频也就有封面了。
    """
    if not data:
        return False
    dst = thumbnail_path(photo_id, size)
    try:
        _atomic_write(dst, data)
        return True
    except OSError:
        log.warning("保存封面失败 %s", photo_id, exc_info=True)
        return False


def get_or_make_thumbnail(photo_id: str, src: Path, filename: str,
                          size: int = THUMB_SIZE) -> bytes | None:
    size = _norm_size(size)
    dst = thumbnail_path(photo_id, size)
    if dst.exists():
        try:
            return dst.read_bytes()
        except OSError:
            pass

    # 视频封面是手机按 512 存的；请求小尺寸时从已有封面缩放，别直接 404
    if size != THUMB_SIZE:
        base = thumbnail_path(photo_id, THUMB_SIZE)
        if base.exists():
            try:
                with Image.open(base) as im:
                    im = im.copy()
                    im.thumbnail((size, size), Image.LANCZOS)
                    buf = io.BytesIO()
                    im.convert("RGB").save(buf, "JPEG", quality=82, optimize=True)
                    data = buf.getvalue()
                _atomic_write(dst, data)
                return data
            except Exception:
                pass

    try:
        if kind_of(filename) == "video":
            return None
        if not _HEIF and filename.lower().endswith((".heic", ".heif")):
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
        _atomic_write(dst, data)      # 并发请求也不能读到写了一半的图
        return data
    except Exception as e:
        log.debug("thumbnail failed for %s: %s", photo_id, e)
        return None


def cleanup_thumb_cache(valid_ids: set[str]) -> int:
    """删掉索引里已经不存在的照片缩略图（清空回收站后尤其需要）。

    只碰 THUMB_DIR，任何异常都吞掉：缓存清理失败不能影响服务启动。
    """
    removed = 0
    try:
        for f in THUMB_DIR.rglob("*.jpg"):
            pid = f.stem.split("@", 1)[0]
            if pid not in valid_ids:
                try:
                    f.unlink()
                    removed += 1
                except OSError:
                    pass
    except OSError:
        pass
    return removed

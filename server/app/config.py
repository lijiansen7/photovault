"""全局配置：全部可通过环境变量覆盖。"""

from __future__ import annotations

import os
from pathlib import Path

# ---- 存储位置 ----------------------------------------------------------------
DATA_DIR = Path(os.getenv("DATA_DIR", "/data")).resolve()
PHOTO_DIR = DATA_DIR / "photos"          # 原文件（字节不做任何改写）
THUMB_DIR = DATA_DIR / "cache" / "thumb"  # 缩略图缓存（可随时删除，不影响原图）
TMP_DIR = DATA_DIR / "tmp"
DB_PATH = DATA_DIR / "index.db"

for _d in (PHOTO_DIR, THUMB_DIR, TMP_DIR):
    _d.mkdir(parents=True, exist_ok=True)

# ---- 安全 --------------------------------------------------------------------
ACCESS_TOKEN = os.getenv("ACCESS_TOKEN", "photovault")

# ---- 传输 --------------------------------------------------------------------
# 0 = 不限制大小（默认）。想兜底就设成正数，例如 2048 表示单文件最多 2GB。
MAX_UPLOAD_MB = int(os.getenv("MAX_UPLOAD_MB", "0"))
CHUNK_SIZE = 1024 * 1024

# ---- 缩略图 ------------------------------------------------------------------
THUMB_SIZE = int(os.getenv("THUMB_SIZE", "512"))
ENABLE_THUMB = os.getenv("ENABLE_THUMB", "1") not in ("0", "false", "False")

# ---- 支持的文件类型 ----------------------------------------------------------
# 相机 RAW。Pillow 解不了，但原文件能照常存下来，还原也照常
RAW_EXT = {".dng", ".cr2", ".cr3", ".nef", ".arw", ".orf", ".raf", ".rw2", ".pef", ".srw"}

IMAGE_EXT = {
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp", ".tif", ".tiff", ".avif",
} | RAW_EXT

VIDEO_EXT = {
    ".mp4", ".mov", ".m4v", ".mkv", ".webm", ".avi", ".3gp", ".hevc",
}

# 只接受媒体文件。想往照片库里塞别的东西（PDF、压缩包…）再打开这个开关，
# 但要知道那意味着 photos/ 目录里会混进非图片文件
ALLOW_ANY_FILE = os.getenv("ALLOW_ANY_FILE", "0") not in ("0", "false", "False", "")

EXT_MIME = {
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png",
    ".gif": "image/gif", ".webp": "image/webp", ".heic": "image/heic",
    ".heif": "image/heif", ".bmp": "image/bmp", ".tif": "image/tiff",
    ".tiff": "image/tiff", ".avif": "image/avif",
    ".mp4": "video/mp4", ".mov": "video/quicktime", ".m4v": "video/mp4",
    ".mkv": "video/x-matroska", ".webm": "video/webm", ".avi": "video/x-msvideo",
    ".3gp": "video/3gpp", ".hevc": "video/hevc",
}


def guess_mime(filename: str, fallback: str = "application/octet-stream") -> str:
    ext = os.path.splitext(filename)[1].lower()
    return EXT_MIME.get(ext, fallback)


def kind_of(filename: str, mime: str = "") -> str:
    ext = os.path.splitext(filename)[1].lower()
    if ext in VIDEO_EXT or mime.startswith("video/"):
        return "video"
    return "image"

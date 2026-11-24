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

# 登录密码最短长度。以前是 4，太容易被撞开；老账号的密码不受影响。
MIN_PASSWORD_LEN = max(4, int(os.getenv("MIN_PASSWORD_LEN", "8")))

# ---- 传输 --------------------------------------------------------------------
# 默认 4GB 兜底（防止一个超大文件把 NAS 磁盘写满）。真要传更大的视频，
# 显式把 MAX_UPLOAD_MB 调大；0 仍然表示不限制，但不推荐。
MAX_UPLOAD_MB = int(os.getenv("MAX_UPLOAD_MB", "4096"))
CHUNK_SIZE = 1024 * 1024

# 单用户最多占用多少 GB；0 = 不限制。开启后上传前会按已用量核对。
USER_QUOTA_GB = float(os.getenv("USER_QUOTA_GB", "0"))

# 磁盘剩余空间低于这个值时拒绝上传，避免把 NAS 塞满导致系统故障。
MIN_FREE_MB = int(os.getenv("MIN_FREE_MB", "512"))

# ---- 反向代理 / 统一网关 ------------------------------------------------------
# 被挂在子路径下时用（飞牛统一网关会把应用挂在 /app/photovault）。
# 服务端会把进来的这个前缀剥掉再路由，前端也会用它在页面上拼 API 地址——
# 少了它，页面里的 /api/... 会请求到网关根上，全是 404。
URL_PREFIX = os.getenv("URL_PREFIX", "").rstrip("/")

# ---- 缩略图 ------------------------------------------------------------------
THUMB_SIZE = int(os.getenv("THUMB_SIZE", "512"))
ENABLE_THUMB = os.getenv("ENABLE_THUMB", "1") not in ("0", "false", "False")

# ---- 文档 --------------------------------------------------------------------
# 默认关掉 /docs 与 /openapi.json：它们不需要登录，会暴露完整 API 结构。
ENABLE_DOCS = os.getenv("ENABLE_DOCS", "0") not in ("0", "false", "False", "")

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
    """只按扩展名推断 MIME。

    **永远不要用客户端上传时声明的 Content-Type**：那是攻击者可控的。
    以前 RAW 文件会回退到客户端 MIME，等于让人把 .dng 声明成 text/html，
    再通过 /file 以内联方式返回 → 同源存储型 XSS。
    """
    ext = os.path.splitext(filename)[1].lower()
    return EXT_MIME.get(ext, fallback)


def is_raw(filename: str) -> bool:
    return os.path.splitext(filename)[1].lower() in RAW_EXT


def kind_of(filename: str, mime: str = "") -> str:
    ext = os.path.splitext(filename)[1].lower()
    if ext in VIDEO_EXT or mime.startswith("video/"):
        return "video"
    return "image"

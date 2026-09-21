"""把服务端的数据目录增量备份到另一个位置（NAS 的话通常是另一块盘 / 另一个共享文件夹）。

照片只有一份、服务端是单点，这个脚本就是那层保险。
只备份 photos/（原文件）、index.db（索引）和 .secret（签名密钥）；
cache/thumb 与 tmp/ 是缓存和临时文件，不备份，丢了会自动重建。

用法：
    python tools/backup_data.py D:\\photo-backup
    python tools/backup_data.py /volume2/photo-backup --source /volume1/photo
    python tools/backup_data.py D:\\photo-backup --dry-run     # 只看看要拷什么

建议挂到计划任务里定期跑（Windows 计划任务 / NAS 的 cron）。
"""

from __future__ import annotations

import argparse
import os
import shutil
import sqlite3
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f}{unit}" if unit != "B" else f"{n}B"
        n /= 1024.0
    return f"{n:.1f}GB"


def same_file(a: str, b: str) -> bool:
    """大小 + 修改时间都相同就认为没变。备份场景够用，而且快。"""
    try:
        sa, sb = os.stat(a), os.stat(b)
    except OSError:
        return False
    return sa.st_size == sb.st_size and int(sa.st_mtime) == int(sb.st_mtime)


def backup_db(src: Path, dst: Path, dry: bool) -> None:
    """用 SQLite 的在线备份 API —— 直接拷文件可能拿到写了一半的 WAL 状态。"""
    if dry:
        print(f"    索引：{src.name} -> {dst}")
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(f"file:{src.as_posix()}?mode=ro", uri=True)
    out = sqlite3.connect(str(dst))
    try:
        with out:
            conn.backup(out)
    finally:
        out.close()
        conn.close()


def sync_tree(src: Path, dst: Path, dry: bool) -> tuple[int, int, int]:
    copied = skipped = bytes_copied = 0
    for root, _dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        out_dir = dst if rel == "." else dst / rel
        if not dry:
            out_dir.mkdir(parents=True, exist_ok=True)
        for name in files:
            s = Path(root) / name
            d = out_dir / name
            if same_file(str(s), str(d)):
                skipped += 1
                continue
            if not dry:
                shutil.copy2(s, d)
            copied += 1
            bytes_copied += s.stat().st_size
    return copied, skipped, bytes_copied


def main() -> int:
    ap = argparse.ArgumentParser(description="PhotoVault 数据目录增量备份")
    ap.add_argument("target", help="备份到哪里，例如 D:\\photo-backup")
    ap.add_argument("--source", default="", help="数据目录，默认 server/data")
    ap.add_argument("--dry-run", action="store_true", help="只看要拷什么，不实际写")
    a = ap.parse_args()

    src = Path(a.source).resolve() if a.source else (ROOT / "server" / "data").resolve()
    dst = Path(a.target).resolve()

    if not src.exists():
        print(f"[错误] 数据目录不存在：{src}")
        return 1
    if dst == src or str(dst).startswith(str(src) + os.sep):
        print("[错误] 备份目标不能在数据目录里面，那样等于没备份。")
        return 1

    print(f"源   ：{src}")
    print(f"目标 ：{dst}")
    print(f"模式 ：{'试运行（不写文件）' if a.dry_run else '实际备份'}\n")

    photos = src / "photos"
    if photos.exists():
        print("  正在同步原文件 …")
        c, s, b = sync_tree(photos, dst / "photos", a.dry_run)
        print(f"    新增/更新 {c} 个，已是最新跳过 {s} 个，共 {human(b)}")
    else:
        print("  [警告] 没有 photos 目录，可能还没上传过照片。")

    db = src / "index.db"
    if db.exists():
        print("  正在备份索引 …")
        backup_db(db, dst / "index.db", a.dry_run)
        print("    index.db 已备份（一致性快照）")

    secret = src / ".secret"
    if secret.exists():
        print("  正在备份签名密钥 …")
        if not a.dry_run:
            shutil.copy2(secret, dst / ".secret")
        print("    .secret 已备份（丢了的话所有人需要重新登录）")

    print("\n完成。")
    print("提示：定期跑一次才叫备份。可以挂到计划任务里，例如每天凌晨执行本脚本。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""检查网页控制台里内嵌的 JS 语法。

CONSOLE_HTML 是 Python 里的字符串常量，手写 JS 时很容易踩转义的坑：
比如 Python 会先把 `\n` 解释成真实换行，JS 字符串就跨行了 —— 语法错误，
整个脚本不执行，浏览器里表现为**整页白屏**，而且不看控制台完全猜不到。

这个脚本把 <script> 抠出来交给 `node --check`，改完立刻能发现。

用法：python tools/check_web.py
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

# 导入 app.main 会初始化数据目录，别污染真实数据
os.environ.setdefault("DATA_DIR", str(Path(tempfile.gettempdir()) / "pv-webcheck"))

REQUIRED_BITS = [
    "id=\"login\"",
    "id=\"app\"",
    "id=\"viewer\"",
    "id=\"g\"",
    "renderByDay",
    "selectAll",
    "'/api/auth/login'",
]


def find_node() -> str | None:
    versions = Path.home() / ".workbuddy" / "binaries" / "node" / "versions"
    if versions.is_dir():
        for p in sorted(versions.glob("*/node.exe")) + sorted(versions.glob("*/bin/node")):
            if p.exists():
                return str(p)
    return shutil.which("node")


def main() -> int:
    try:
        from app.main import CONSOLE_HTML
    except Exception as e:
        print(f"[错误] 导入 app.main 失败：{e}")
        return 1

    print(f"CONSOLE_HTML {len(CONSOLE_HTML)} 字节")

    missing = [b for b in REQUIRED_BITS if b not in CONSOLE_HTML]
    if missing:
        print(f"[错误] 缺少关键元素：{missing}")
        return 1
    print("  关键元素齐全")

    m = re.search(r"<script>(.*?)</script>", CONSOLE_HTML, re.S)
    if not m:
        print("[错误] 找不到 <script> 段")
        return 1
    js = m.group(1)

    # 先做个针对性提示：单个反斜杠的 \n 会被 Python 变成真实换行，
    # JS 字符串就跨行了 —— 报错信息不直观，这里直接点出来
    for i, line in enumerate(js.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("'") and stripped.count("'") % 2 == 1:
            print(f"[提示] JS 第 {i} 行单引号没闭合，很可能是 \\n 少写了一个反斜杠"
                  f"（Python 里要写 \\\\n）：")
            print("       " + stripped[:80])
            break

    node = find_node()
    if not node:
        print("[跳过] 没找到 node，无法做语法检查")
        return 0

    tmp = Path(tempfile.gettempdir()) / "pv_console_check.js"
    tmp.write_text(js, encoding="utf-8")
    r = subprocess.run([node, "--check", str(tmp)], capture_output=True, text=True)
    if r.returncode != 0:
        print("[错误] 内嵌 JS 语法有问题（浏览器里会白屏）：\n")
        print(r.stderr.strip())
        return 1

    print(f"  JS 语法 OK（{len(js)} 字节，用 {Path(node).name} 校验）")
    print("\n通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

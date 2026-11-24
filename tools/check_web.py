"""检查网页控制台的静态资源与 JS 语法。

网页已经拆成独立文件（server/app/static/index.html + console.js），不再和
Python 字符串转义纠缠。这个脚本做三件事：
1. 关键元素是否还在；
2. JS 交给 `node --check` 验语法；
3. 回归检查：用户管理按钮、token 拼 URL、未定义变量这些曾经踩过的坑。

用法：python tools/check_web.py
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "server" / "app" / "static"

REQUIRED_HTML = [
    'id="login"',
    'id="app"',
    'id="viewer"',
    'id="g"',
    'id="moreBtn"',
    'id="modalInput"',
    'src="__BASE__/static/console.js',
]

REQUIRED_JS = [
    "renderByDay",
    "selectAll",
    "loadMore",
    "toggleUser",
    "resetPw",
    "'/api/auth/login'",
]

# 这些写法会导致运行期才暴露的问题，必须在静态检查阶段拦住
FORBIDDEN_JS = [
    (r"\?token=", "URL 里带 token（应改用 HttpOnly cookie / Authorization 头）"),
    (r"\bUSERS\[", "引用了未定义的 USERS"),
    (r"onclick=\\\"toggleUser\(\\'\\\"", "toggleUser 的引号又写错了"),
]


def find_node() -> str | None:
    versions = Path.home() / ".workbuddy" / "binaries" / "node" / "versions"
    if versions.is_dir():
        for p in sorted(versions.glob("*/node.exe")) + sorted(versions.glob("*/bin/node")):
            if p.exists():
                return str(p)
    return shutil.which("node")


def main() -> int:
    html_path = STATIC / "index.html"
    js_path = STATIC / "console.js"
    if not html_path.exists() or not js_path.exists():
        print(f"[错误] 静态文件缺失：{html_path} / {js_path}")
        return 1

    html = html_path.read_text(encoding="utf-8")
    js = js_path.read_text(encoding="utf-8")
    print(f"index.html {len(html)} 字节；console.js {len(js)} 字节")

    missing = [b for b in REQUIRED_HTML if b not in html]
    if missing:
        print(f"[错误] index.html 缺少关键元素：{missing}")
        return 1
    missing = [b for b in REQUIRED_JS if b not in js]
    if missing:
        print(f"[错误] console.js 缺少关键函数：{missing}")
        return 1
    print("  关键元素齐全")

    for pattern, why in FORBIDDEN_JS:
        if re.search(pattern, js):
            print(f"[错误] console.js 命中禁用写法：{why}")
            return 1

    node = find_node()
    if not node:
        print("[跳过] 没找到 node，无法做语法检查")
        return 0

    tmp = Path(tempfile.gettempdir()) / "pv_console_check.js"
    tmp.write_text(js, encoding="utf-8")
    try:
        r = subprocess.run([node, "--check", str(tmp)], capture_output=True, text=True)
    except OSError:
        # 某些受限环境不允许创建匿名管道；退回直接继承 stdout/stderr
        r = subprocess.run([node, "--check", str(tmp)])
    if r.returncode != 0:
        print("[错误] console.js 语法有问题（浏览器里会白屏）：\n")
        print(r.stderr.strip() if r.stderr else "（node 未提供错误输出）")
        return 1

    print(f"  JS 语法 OK（用 {Path(node).name} 校验）")
    print("\n通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

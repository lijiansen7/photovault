"""打印本机局域网 IPv4，供 start_server.bat 显示"手机该填什么地址"。

优先用 UDP connect 探测出口网卡（最贴近"能对外通信的那个地址"），
失败时退回解析 ipconfig，这样没外网的纯局域网环境也能用。
"""

from __future__ import annotations

import re
import socket
import subprocess
import sys


def via_udp() -> str | None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        s.connect(("223.5.5.5", 80))       # 阿里 DNS，只握手不发包
        ip = s.getsockname()[0]
        s.close()
        return ip if ip and not ip.startswith("127.") else None
    except Exception:
        return None


def via_ipconfig() -> list[str]:
    try:
        out = subprocess.run(["ipconfig"], capture_output=True, timeout=15)
        text = out.stdout.decode("gbk", errors="replace") or out.stdout.decode("utf-8", errors="replace")
    except Exception:
        return []
    ips = []
    for m in re.finditer(r"IPv4[^:]*:\s*([\d.]+)", text):
        ip = m.group(1)
        if not ip.startswith("127.") and not ip.startswith("169.254."):
            ips.append(ip)
    return ips


def main() -> int:
    ip = via_udp()
    if ip:
        print(ip)
        return 0
    ips = via_ipconfig()
    if ips:
        print(ips[0])
        return 0
    print("", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

"""PhotoVault 原生应用启动器。

等价于 Docker 版 entrypoint.sh，但不需要 socat / su-exec / 容器网络：

    飞牛网关 ──HTTPS──> target/app.sock ─┐
    局域网   ──HTTP──>  :8766            ─┼──> 127.0.0.1:8765 (uvicorn)
    手机 App ──HTTP──>  :8765（uvicorn 直接接）

环境变量全部由 cmd/main 从 $TRIM_PKGVAR/app.env 导出。
"""

from __future__ import annotations

import logging
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import uvicorn  # noqa: E402

from relay import start_relay  # noqa: E402

LOG_LEVEL = (os.getenv("LOG_LEVEL") or "info").strip().lower()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("photovault")


def main() -> None:
    bind_host = os.getenv("BIND_HOST", "0.0.0.0")
    bind_port = int(os.getenv("BIND_PORT", "8765"))

    gateway_socket = (os.getenv("GATEWAY_SOCKET") or "").strip()
    relay_port = int(os.getenv("RELAY_TCP_PORT", "8766") or 0)

    start_relay(gateway_socket, relay_port)

    log.info("PhotoVault 启动：%s:%s（网关 socket=%s）", bind_host, bind_port, gateway_socket or "未启用")

    uvicorn.run(
        "app.main:app",
        host=bind_host,
        port=bind_port,
        proxy_headers=True,
        forwarded_allow_ips="*",
        log_level=LOG_LEVEL,
        access_log=True,
    )


if __name__ == "__main__":
    main()

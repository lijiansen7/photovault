"""把飞牛统一网关的 Unix Socket 与 8766 兼容口，都转发到本机 uvicorn。

原生应用里没有 socat（飞牛系统不预装、也不想为它多带一个二进制），
所以用纯标准库的 asyncio 做转发，零额外依赖。

链路：
    飞牛网关  --HTTPS-->  /var/apps/photovault/target/app.sock  ─┐
    局域网    --HTTP-->   :8766                                 ─┼--> 127.0.0.1:8765 (uvicorn)
    手机 App  --HTTP-->   :8765（由 uvicorn 自己接）
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import threading

log = logging.getLogger("photovault.relay")

UPSTREAM_HOST = "127.0.0.1"
# 必须跟随 uvicorn 实际监听的端口（BIND_PORT），否则改了端口转发就断。
UPSTREAM_PORT = int(os.getenv("BIND_PORT", "8765"))
BUF = 64 * 1024


async def _pump(src: asyncio.StreamReader, dst: asyncio.StreamWriter) -> None:
    try:
        while True:
            chunk = await src.read(BUF)
            if not chunk:
                break
            dst.write(chunk)
            await dst.drain()
    except (ConnectionResetError, BrokenPipeError, asyncio.IncompleteReadError, OSError):
        pass
    finally:
        # 关掉写端即可触发对端 EOF；不要直接 close 另一条管子，
        # 否则 HTTP keep-alive 的响应尾部可能被截断。
        with contextlib.suppress(Exception):
            if dst.can_write_eof():
                dst.write_eof()


async def _handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        up_r, up_w = await asyncio.open_connection(UPSTREAM_HOST, UPSTREAM_PORT)
    except OSError as exc:
        log.warning("上游连接失败: %s", exc)
        with contextlib.suppress(Exception):
            writer.close()
        return
    try:
        await asyncio.gather(_pump(reader, up_w), _pump(up_r, writer))
    finally:
        for w in (up_w, writer):
            with contextlib.suppress(Exception):
                w.close()


async def _serve(sock_path: str, tcp_port: int) -> None:
    servers: list[asyncio.AbstractServer] = []

    if sock_path:
        os.makedirs(os.path.dirname(sock_path), exist_ok=True)
        with contextlib.suppress(FileNotFoundError):
            os.unlink(sock_path)
        servers.append(await asyncio.start_unix_server(_handle, path=sock_path))
        # 网关进程以 root 运行，但 socket 属主是本应用用户；放开权限最稳妥
        with contextlib.suppress(OSError):
            os.chmod(sock_path, 0o666)
        log.info("relay: unix %s -> %s:%s", sock_path, UPSTREAM_HOST, UPSTREAM_PORT)

    if tcp_port:
        servers.append(
            await asyncio.start_server(
                _handle, "0.0.0.0", tcp_port, reuse_address=True
            )
        )
        log.info("relay: tcp :%s -> %s:%s", tcp_port, UPSTREAM_HOST, UPSTREAM_PORT)

    if not servers:
        return

    await asyncio.gather(*(s.serve_forever() for s in servers))


def start_relay(sock_path: str = "", tcp_port: int = 0) -> threading.Thread | None:
    """在后台线程里跑转发，返回线程对象（守护线程，主进程退出即结束）。"""
    if not sock_path and not tcp_port:
        return None

    def _runner() -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(_serve(sock_path, tcp_port))
        except Exception:  # pragma: no cover - 只在异常退出时记录
            log.exception("relay 线程异常退出")
        finally:
            loop.close()

    t = threading.Thread(target=_runner, name="pv-relay", daemon=True)
    t.start()
    return t

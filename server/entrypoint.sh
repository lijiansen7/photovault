#!/bin/sh
# 容器入口。
#
# 两件事：
# 1) 常规监听 8765 端口 —— 手机 App、浏览器直连都用它；
# 2) 如果设了 GATEWAY_SOCKET（飞牛统一网关会设），再起一个 socat，
#    把 Unix Socket 转发到本机 8765。
#
# 为什么需要 2)：飞牛桌面是 HTTPS 的，应用入口如果嵌 http://IP:8765，
# 浏览器会以「混合内容」为由直接拦掉（表现为点开应用一片空白/打不开）。
# 走统一网关后，入口是 https://飞牛/app/photovault，由飞牛网关代理到
# 这个 Socket，全程 HTTPS，就没有混合内容问题了。
#
# 用 socat 桥接而不是再起一个 uvicorn，是为了只保留一个应用进程，
# 避免两个进程同时写 SQLite。

set -e

if [ -n "$GATEWAY_SOCKET" ]; then
  mkdir -p "$(dirname "$GATEWAY_SOCKET")"
  rm -f "$GATEWAY_SOCKET"
  socat UNIX-LISTEN:"$GATEWAY_SOCKET",fork,reuseaddr TCP:127.0.0.1:8765 &
fi

exec uvicorn app.main:app --host 0.0.0.0 --port 8765 --proxy-headers

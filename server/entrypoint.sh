#!/bin/sh
# 容器入口。
#
# 1) 修正 /data、证书目录与网关 socket 目录的属主（宿主机 bind mount 可能属于 root）；
# 2) 可选：/data/certs 下放了域名证书时，uvicorn 直接以 HTTPS 监听 8765，
#    不再需要 nginx / Caddy 反向代理；
# 3) 可选：用 socat 把飞牛统一网关的 Unix Socket 转到本机 8765；
# 4) 以非 root 用户运行 uvicorn。
#
# 只保留一个 uvicorn 进程，避免两个进程同时写 SQLite。

set -e

if [ "$(id -u)" = "0" ]; then
  # 只在根目录属主不对时才递归 chown：照片目录可能有几十万文件，
  # 每次启动都 chown -R 会慢到不可接受。
  if [ "$(stat -c '%u' /data 2>/dev/null || echo 0)" != "1000" ] \
     || [ "$(stat -c '%u' /app/target 2>/dev/null || echo 0)" != "1000" ]; then
    chown -R photovault:photovault /data /app/target 2>/dev/null || true
  fi
  chown -R photovault:photovault /data/certs 2>/dev/null || true
  RUN_AS="su-exec photovault:photovault"
else
  RUN_AS=""
fi

# 支持两种命名：通用 fullchain/privkey，或按域名命名（如 photo.example.com.crt/key）。
TLS_CERT="/data/certs/fullchain.pem"
TLS_KEY="/data/certs/privkey.pem"
if [ ! -r "$TLS_CERT" ] || [ ! -r "$TLS_KEY" ]; then
  TLS_CERT="$(ls /data/certs/*.crt 2>/dev/null | head -1)"
  TLS_KEY="${TLS_CERT%.crt}.key"
fi

TLS_ARGS=""
GATEWAY_TARGET="TCP:127.0.0.1:8765"
HTTPS_ON=0
if [ -n "$TLS_CERT" ] && [ -r "$TLS_CERT" ] && [ -r "$TLS_KEY" ]; then
  HTTPS_ON=1
fi
if [ -f /data/certs/https.enabled ]; then
  HTTPS_FLAG="$(cat /data/certs/https.enabled 2>/dev/null | tr -d '[:space:]')"
  if [ "$HTTPS_FLAG" = "0" ]; then
    HTTPS_ON=0
  elif [ "$HTTPS_FLAG" = "1" ]; then
    HTTPS_ON=1
  fi
fi
if [ "$HTTPS_ON" = "1" ] && [ -n "$TLS_CERT" ] && [ -r "$TLS_CERT" ] && [ -r "$TLS_KEY" ]; then
  TLS_ARGS="--ssl-certfile $TLS_CERT --ssl-keyfile $TLS_KEY"
  # 本机回环连接，verify=0；SNI 用 localhost 即可，避免 socat 拒绝空 SNI。
  GATEWAY_TARGET="OPENSSL:127.0.0.1:8765,verify=0,snihost=${TLS_SNI:-localhost}"
fi

if [ -n "$GATEWAY_SOCKET" ]; then
  mkdir -p "$(dirname "$GATEWAY_SOCKET")"
  rm -f "$GATEWAY_SOCKET"
  $RUN_AS socat UNIX-LISTEN:"$GATEWAY_SOCKET",fork,reuseaddr "$GATEWAY_TARGET" &
fi

# 局域网 HTTP 兼容口：内网地址可以继续用 http://IP:8766，
# 外网仍用 https://域名:8765；内部只有一个 uvicorn 进程。
$RUN_AS socat TCP-LISTEN:8766,fork,reuseaddr "$GATEWAY_TARGET" 2>/dev/null &

exec $RUN_AS uvicorn app.main:app --host 0.0.0.0 --port 8765 --proxy-headers $TLS_ARGS

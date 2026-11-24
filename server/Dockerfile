# syntax=docker/dockerfile:1

# 用 alpine 而不是 slim：基础镜像小一半多，
# docker load 时要展开写入的数据少很多，飞牛上安装明显更快。
FROM python:3.12-alpine

# 时区数据 + 统一网关用的 socat + 降权用的 su-exec
RUN apk add --no-cache tzdata socat su-exec

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    DATA_DIR=/data

WORKDIR /srv

COPY requirements.txt ./
# pip 默认走清华源（国内构建快很多）；海外或想用官方源就：
#   docker build --build-arg PIP_INDEX_URL=https://pypi.org/simple .
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
# musl 环境下没有预编译 wheel 就只能现场编译，那会极慢甚至失败。
# 用 --only-binary 让它在缺 wheel 时**立刻失败**，而不是耗十几分钟编译。
RUN pip install --upgrade pip -i "$PIP_INDEX_URL" \
    && pip install --only-binary=:all: -r requirements.txt -i "$PIP_INDEX_URL"

COPY app ./app
COPY entrypoint.sh ./entrypoint.sh
# 不用 root 跑应用；/data 和网关 socket 目录在 entrypoint 里再 chown 一次，
# 兼容宿主机 bind mount 本来属于 root 的情况。
RUN chmod +x ./entrypoint.sh \
    && adduser -D -u 1000 -h /home/photovault photovault \
    && mkdir -p /data /app/target \
    && chown -R photovault:photovault /data /app/target /srv

VOLUME ["/data"]
EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/api/health', timeout=3)"

CMD ["./entrypoint.sh"]

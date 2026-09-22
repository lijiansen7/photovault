# syntax=docker/dockerfile:1

FROM python:3.12-slim

# slim 镜像不带时区数据的话 TZ=Asia/Shanghai 不生效，服务端日志和时间戳会全按 UTC 走
# 国内网络下 apt 也慢，顺手走清华镜像
RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || true \
    && apt-get update \
    && apt-get install -y --no-install-recommends tzdata socat \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    DATA_DIR=/data

WORKDIR /srv

COPY requirements.txt ./
# pip 默认走清华源（国内构建快很多）；海外或想用官方源就：
#   docker build --build-arg PIP_INDEX_URL=https://pypi.org/simple .
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
RUN pip install --upgrade pip -i "$PIP_INDEX_URL" \
    && pip install -r requirements.txt -i "$PIP_INDEX_URL"

COPY app ./app
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh

VOLUME ["/data"]
EXPOSE 8765

CMD ["./entrypoint.sh"]

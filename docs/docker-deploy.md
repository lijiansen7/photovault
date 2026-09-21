# PhotoVault · Docker 部署教程

把 NAS 服务端跑在 Docker 里，手机 App 通过局域网把照片备份上去。
全程只需要一个 NAS（群晖 / 威联通 / UnRAID / 任意 Linux + Docker）。

---

## 0. 部署前先搞清楚三件事

| 项目 | 说明 |
|---|---|
| **照片存在哪** | 容器里的 `/data`，由你自己挂载决定。挂载到哪就存在哪 |
| **网页管理端** | `http://<NAS的IP>:8765` |
| **手机怎么连** | App「设置」里填 `http://<NAS的IP>:8765`，用**账号密码**登录 |

> ⚠️ 手机 App 早就不用「接入 Token」了，改成账号密码登录。
> 网上/旧文档里让你填 Token 的说法都已过时。

---

## 1. 准备数据目录

**群晖举例**：在 File Station 里建一个共享文件夹，比如 `photo`（实际路径 `/volume1/photo`）。
**威联通**：`/share/Container/photo`。
**普通 Linux**：随便挑个盘大的目录，比如 `/srv/photovault`。

这个目录就是容器里的 `/data`，启动后里面会长出：

```
/data/
├── photos/        ← 原文件，字节不改写（EXIF/GPS 全保留）。这是你的命根子
├── cache/thumb/   ← 缩略图缓存，删了会自动重建，无所谓
├── tmp/           ← 上传中的临时文件（.part），正常会自动清理
└── index.db       ← SQLite 索引（文件名/时间/位置/相册…）
```

**备份时只要保住 `photos/` 和 `index.db` 就够**（`cache/` 不用备份）。

---

## 2. 准备 compose 文件

把项目里 `server/` 整个目录传到 NAS（比如 `/volume1/docker/photovault/`），
里面应该有 `Dockerfile`、`docker-compose.yml`、`.dockerignore`、`requirements.txt`、`app/`。

然后 SSH 登录 NAS：

```bash
cd /volume1/docker/photovault
```

### 改两处（必须）

编辑 `docker-compose.yml`：

```yaml
    environment:
      # ① 初始管理员密码 —— 务必改成你自己的强密码
      INIT_ADMIN_USER: "admin"
      INIT_ADMIN_PASSWORD: "改成你的强密码"     # ← 改这里

    volumes:
      # ② 照片存哪 —— 换成你在第 1 步建的真实路径
      - /volume1/photo:/data                  # ← 改这里（群晖）
      # 威联通：- /share/Container/photo:/data
```

> **关于 `INIT_ADMIN_PASSWORD`**：
> 它只在**首次启动、库里还没有任何用户**时创建管理员。
> 已经跑起来之后改这个变量**不会**重建管理员，改密码请登录网页端「用户」页操作。

---

## 3. 启动

```bash
docker compose up -d          # 构建镜像并后台启动
docker compose logs -f photovault   # 看日志，Ctrl+C 退出
```

首次启动日志里会打印一行类似：

```
已创建初始管理员：admin / <你设的密码>
```

看到 `PhotoVault ready. photo dir = /data` 就成了。

---

## 4. 验证服务真的活着

```bash
# 健康检查接口是 GET 且不需要登录，正合适
curl http://<NAS的IP>:8765/api/health
# → {"ok":true,"ts":1768...}

docker compose ps
# STATUS 显示 (healthy) 才算正常
```

> 注意别拿 `/api/ping` 探活 —— 那个是 POST 且**需要登录**。

浏览器打开 `http://<NAS的IP>:8765`，会看到登录框：

- 用户名：`admin`
- 密码：你设的 `INIT_ADMIN_PASSWORD`
- **首次登录会强制要求改密码**（这是故意的，防止你一直用初始弱口令）

---

## 5. 手机 App 配置

1. 手机和 NAS 在**同一个 WiFi** 下；
2. 打开 App → 「设置」：
   - **NAS 地址**填 `http://<NAS的IP>:8765`（不写端口默认就是 8765）
   - 填账号密码 → 点「登录」
3. 回首页：打开「自动备份」开关 → 「选择要备份的相册」勾选 → 点「立即同步」。

> 现在**一次点击就会把全库传完**：内部按 800 张一批自动接续，
> 进度条会一直走，中途想停点「取消」即可（已经传上去的不会丢）。

---

## 6. 外网访问 / HTTPS（可选）

只在家里用可以跳过这节。想在外面也备份，建议**反代 + HTTPS**，别直接把 8765 端口暴露到公网。

### 群晖（最省事）
控制面板 → 登录门户 → 高级 → 反向代理服务器，新增一条：
- 来源：你的域名 + 443
- 目的地：`http://127.0.0.1:8765`
证书在「安全性 → 证书」用 Let's Encrypt 申请。

### 自建 nginx

```nginx
server {
    listen 443 ssl http2;
    server_name nas.example.com;
    ssl_certificate     /path/fullchain.pem;
    ssl_certificate_key /path/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8765;

        # 服务端默认不限制上传大小；反代这里也必须放开，否则大视频传一半就断
        client_max_body_size 0;           # 0 = 不检查大小
        proxy_read_timeout   3600s;       # 大文件别被中途掐断
        proxy_send_timeout   3600s;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

配好后手机端地址改成完整的 `https://nas.example.com`。

---

## 7. 备份与升级

### 备份
- **最稳的做法**：整个 `/data` 目录定期 rsync / 快照到另一个盘；
- 也可以用项目自带的 `tools/backup_data.py`，它会做增量备份，
  并且 `index.db` 走 **SQLite 在线备份 API**（不会拷到写了一半的状态）。

### 升级

```bash
cd /volume1/docker/photovault
docker compose build       # 重新构建镜像（源码有更新时）
docker compose up -d       # 滚动替换容器
```

数据目录在宿主机上，重建容器**不会**动你的照片。

---

## 8. 排错表

| 现象 | 排查 |
|---|---|
| 手机连不上 | ① 同一 WiFi？② NAS 防火墙放行 8765？③ `docker compose ps` 是不是 healthy？④ 地址有没有多写/少写端口 |
| 网页打不开 | `docker compose logs -f` 看有没有报错；确认端口没被别的容器占用 |
| 登录总失败 | 密码错了会锁：连续错 5 次锁 10 分钟（`MAX_FAILED_LOGINS` / `LOCKOUT_SEC` 可调） |
| 上传传到一半就断 | 反代的话检查 `client_max_body_size` / `proxy_read_timeout`；单个文件超过 `MAX_UPLOAD_MB`（默认 1024MB）会被拒 |
| 照片时间/日期不对 | 确认 `TZ: Asia/Shanghai`；镜像里已装 tzdata |
| 磁盘满了 | 缩略图缓存 `/data/cache` 可以整个删掉，不影响原图 |
| **忘了管理员密码** | 见下面的应急办法 |

### 🔧 应急：忘了管理员密码

临时开一下兼容 Token 通道，用它调管理员接口把密码重置掉，**用完记得关回去**：

```bash
# 1) 在 docker-compose.yml 里临时加两行，然后 up -d
#    ALLOW_LEGACY_TOKEN: "1"
#    ACCESS_TOKEN: "临时密钥"

# 2) 用 token 调管理员接口重置密码
curl -X POST http://<NAS的IP>:8765/api/admin/users/<admin的用户ID>/password \
     -H "X-Token: 临时密钥" \
     -H "Content-Type: application/json" \
     -d '{"new_password":"新密码"}'

# 3) 改完把 ALLOW_LEGACY_TOKEN 改回 "0"，重启容器
```

这个通道默认是**关闭**的（开着等于一个公开字符串直通管理员），只应急用。

---

## 附：常用环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `INIT_ADMIN_USER` / `INIT_ADMIN_PASSWORD` | `admin` / `photovault` | 首次启动自动创建的管理员 |
| `DATA_DIR` | `/data` | 照片 + 索引目录（容器内） |
| `MAX_UPLOAD_MB` | `0` | 单文件大小上限（MB）；**`0` = 不限制**。设正数才会拒掉超限文件 |
| `THUMB_SIZE` | `512` | 缩略图边长 |
| `ENABLE_THUMB` | `1` | 置 0 关闭缩略图 |
| `TZ` | — | 建议 `Asia/Shanghai` |
| `SECRET_KEY` | 自动生成存 `/data/.secret` | token 签名密钥；多副本部署要显式指定同一个值 |

完整列表见项目根目录 `README.md` 的「环境变量」表。

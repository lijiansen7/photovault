# PhotoVault 项目文档 · 架构与实现

> 面向开发者。讲「为什么这么设计」和「代码在哪里」。
> 部署、安装、日常使用请看 [README.md](../README.md)；接口细节看 README 的「服务端 API」一节。

---

## 一、这个项目是什么

一句话：**把手机里的原图，按字节完整地存到自己的 NAS 上，并且能原样还原回手机相册。**

和「网盘同步」的差别在于三个硬约束：

| 约束 | 含义 | 对实现的影响 |
|---|---|---|
| 原文件字节级不变 | 不做转码、不重压缩、不改 EXIF | 服务端只读原文件；缩略图单独存缓存目录，可随时删 |
| 拍摄时间与地点不能丢 | 还原后相册里排序、地图位置和原来一致 | 元数据从 EXIF 抽取并入库；还原时写回 MediaStore |
| 完全自托管 | 不依赖任何云服务 | 单容器、SQLite、无外部中间件 |

`taken_at` 的取值有优先级：**EXIF 原始拍摄时间 > 客户端上报 > 文件名里的日期**。这个顺序在 `server/app/media.py` 的 `extract_metadata()` 和 `merge_client_metadata()` 里。

---

## 二、系统架构

```
┌─────────────────────────────┐
│  Android App (Kotlin/Compose)│
│  ┌──────────┐  ┌──────────┐ │
│  │UploadWorker│ │RestoreWorker│
│  └─────┬────┘  └─────┬────┘ │
│        │ WorkManager │      │
└────────┼─────────────┼──────┘
         │ HTTP(S)     │
         ▼             ▼
┌─────────────────────────────┐
│  FastAPI 服务端 (8765)       │
│  auth / db / media / main   │
│         │                    │
│    ┌────▼─────┐  ┌─────────┐│
│    │ index.db │  │ photos/ ││
│    │ (SQLite) │  │ cache/  ││
│    └──────────┘  └─────────┘│
└─────────────────────────────┘
         ▲
         │ /app/photovault 前缀
┌────────┴────────────────────┐
│  飞牛 fnOS 统一网关 (5667)    │
│  或 独立反代 / 直连 8765      │
└─────────────────────────────┘
```

三层之间只有一种协议：**HTTP + JSON + multipart**。没有 WebSocket、没有长连接、没有消息队列。这是刻意的——NAS 上的服务越简单越不容易坏。

### 2.1 为什么服务端无状态

除 SQLite 和文件系统之外，服务端不保存任何运行时状态。所有会话信息都在客户端持有的签名 token 里。这样做的收益：

- 容器可以直接 `--force-recreate`，不丢登录态
- 不需要 Redis / session 存储
- 备份只需要拷 `data/` 两个东西：`index.db` 和 `photos/`

代价是 token 无法单独吊销——所以引入了 `token_epoch`（见 3.4）。

---

## 三、服务端

### 3.1 模块划分

| 文件 | 行数 | 职责 | 不该做的事 |
|---|---|---|---|
| `config.py` | ~72 | 环境变量、目录布局、文件类型白名单 | 不做 IO |
| `auth.py` | ~357 | 密码哈希、token 签发/校验、用户 CRUD | 不碰照片 |
| `db.py` | ~579 | 建表、迁移、查询、分页、统计 | 不碰文件 |
| `media.py` | ~249 | EXIF 抽取、缩略图生成 | 不改原文件 |
| `main.py` | ~1607 | 路由、上传流水线、内嵌网页控制台 | 不写复杂 SQL |

依赖方向是单向的：`main → {auth, db, media} → config`。`db` 不 import `media`，`media` 不 import `db`。

### 3.2 磁盘布局

```
$DATA_DIR/                      (默认 /data)
├── index.db                    SQLite 索引
├── .secret                     token 签名密钥（首次启动生成，0600）
├── photos/                     原文件，字节不变
│   └── ab/cd/abcd…ef.jpg       按 sha256 前 4 位分两级
├── cache/thumb/                缩略图，可随时删
│   └── ab/abcd….jpg
└── tmp/                        上传暂存（*.part）
```

分片规则在 `main.py:_dest_path()`：

```python
rel = f"{sha256[:2]}/{sha256[2:4]}/{sha256}{ext}"
```

**为什么按哈希分片而不是按日期分目录**：同一张照片无论哪天传、从哪台设备传，落在同一个位置。这让「跨用户复用同一份字节」成为可能（见 3.3），也让备份是纯增量的。

代价是人肉浏览目录时看不出时间顺序——但那是网页端的职责，不是文件系统的。

### 3.3 上传流水线与两级去重

`POST /api/upload` 的处理顺序（`main.py:386` 起）：

```
1. 边读边算 SHA256，落到 tmp/*.part        ← 不占内存，支持超大视频
2. 查 (owner_id, sha256) 是否已存在
   ├─ 存在且未删除 → 直接返回 duplicate，删临时文件
   └─ 不存在 → 继续
3. 查是否有**其他用户**传过相同字节
   ├─ 有，且文件确实在盘上 → 复用别人的 rel_path，自己不落盘
   └─ 没有 → move 到 photos/xx/yy/
4. 抽 EXIF，与客户端上报的元数据合并（客户端优先）
5. 插入 photos 记录
```

几个容易踩的点：

- **第 3 步必须验证文件真的存在**。记录可能是悬空的（用户清空了回收站，物理文件已删），只查库会得到一个 404 的 `rel_path`。
- **`shutil.move` 在同一卷内是原子改名**，所以 tmp 和 photos 必须在同一个文件系统上。跨卷会退化成拷贝。
- **`UNIQUE(owner_id, sha256)` 而不是全局唯一 sha256**。早期版本是全局唯一，那样 B 用户传了 A 已经传过的照片，会直接撞约束。现在改成按用户隔离，磁盘上仍然只有一份字节。

`/api/photos/exists` 和 `/api/photos/prescreen` 是给客户端做「上传前先问」用的，减少无效传输。

### 3.4 认证：自研签名 token

`auth.py` 刻意不引入 PyJWT 之类的依赖，用 `hmac` + `hashlib` 手写。

**密码**：`pbkdf2_hmac('sha256', pw, salt, iters)`，存储格式 `pbkdf2_sha256$iters$b64(salt)$b64(hash)`。校验用 `hmac.compare_digest` 防时序攻击。

**Token**：`base64(payload).base64(hmac_sha256(SECRET, payload))`

payload 结构：

```json
{"uid": "...", "u": "admin", "r": "admin", "e": 3, "exp": 1797490758}
```

`e` 是 `token_epoch`。用户改密码、被禁用、被删除时，数据库里的 `token_epoch + 1`，所有旧 token 立即失效——这是无状态 token 唯一的吊销手段。校验分两步：`parse_token()` 只验签名和过期（不查库），`resolve_token()` 再查库比对 epoch（`auth.py:141`）。

**SECRET 的来源**优先级：`SECRET_KEY` 环境变量 > `data/.secret` 文件 > 首次生成并写盘。所以容器重建不会让所有人掉线。

`ALLOW_LEGACY_TOKEN=0` 关闭了早期的单一 `ACCESS_TOKEN` 通道，现在只认 Bearer。

### 3.5 多用户与隔离

| 角色 | 能做什么 |
|---|---|
| `admin` | 看所有人的照片、管理用户、巡检完整性 |
| `user` | 只看自己的照片（`owner_id` 过滤） |

隔离落实在每一层：`db.py` 的所有查询函数都接受 `owner_id` 参数，`None` 表示不过滤（仅 admin 路径会传）。这不是靠路由层过滤，是在 SQL 的 `WHERE` 里加条件——避免某个接口忘了判断。

用户名有白名单校验（中文名照常支持），防止把 `../` 之类塞进日志或路径。

### 3.6 缩略图

`media.get_or_make_thumbnail()`：

- 尺寸 `THUMB_SIZE`（默认 512），Lanczos 重采样，JPEG q=82
- 首次请求时生成，写到 `cache/thumb/xx/<id>.jpg`
- 生成失败（RAW、损坏文件）返回 `None`，前端回落到占位图
- **缓存目录可以随时整个删掉**，不影响任何照片

RAW 文件（`dng/cr2/cr3/nef/arw…`）Pillow 解不了，所以没有缩略图，但原文件照常备份和还原。这是有意的取舍。

### 3.7 回收站

删除是软删除：`photos.deleted = 1` + `deleted_at`。物理文件不动。

- `GET /api/trash` 列出
- `POST /api/photos/{id}/restore` 恢复
- `DELETE /api/trash` 或 `POST /api/trash/delete` 才真正删文件

物理删除时要小心：如果另一个用户因为字节复用共享了同一个 `rel_path`，删之前必须确认没有其他记录还在引用它。

### 3.8 子路径部署

飞牛统一网关会把应用挂在 `/app/photovault` 下。服务端通过 `URL_PREFIX` 环境变量处理：

- 请求进来先剥掉前缀再路由
- 内嵌网页里拼 API 地址时带上前缀

少了这层，页面里的 `/api/...` 会请求到网关根上，全是 404。这是飞牛环境下最常见的部署故障。

---

## 四、Android App

### 4.1 分层

```
ui/          Compose 界面（home 备份 / browse 相册 / settings 设置 / trash 回收站）
  └─ ViewModel
data/        NasApi（OkHttp）+ SettingsRepo（DataStore）+ MediaRepository（MediaStore 查询）
work/        UploadWorker / RestoreWorker / UploadScheduler / MediaStoreWriter
util/        ExifReader / Hash / Humanize / Notifications / SecretStore
```

界面层不直接碰网络和 MediaStore，全部经 ViewModel 转成状态。这样旋转屏幕、进程被杀都不会丢进度。

### 4.2 备份调度

`UploadScheduler` 用 WorkManager 的周期任务：

```kotlin
PeriodicWorkRequestBuilder<UploadWorker>(cfg.periodMinutes.coerceAtLeast(15), MINUTES)
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED 或 CONNECTED)
            .setRequiresCharging(cfg.chargingOnly)
            .build()
    )
wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
```

- 15 分钟是 WorkManager 的硬下限，配置更小也会被拉回 15
- 用 `UPDATE` 而不是 `REPLACE`：改配置时更新已有任务，不会把正在跑的那次打断
- `runOnce` / `runOnceForced` / `runOnceChained` 是手动触发用的，都走唯一工作名，避免连点堆积

### 4.3 还原

`RestoreWorker` 拿到一批照片的 id/name/mime/takenAt/isVideo/relDir/album/size，逐张：

1. 查本地是否已有同名同大小的文件（`restoreConflict`：`skip` 或 `overwrite`）
2. `api.withOriginal(id)` 流式下载，用 `FilterInputStream` 边读边计字节（进度和速度都从这里来）
3. `MediaStoreWriter.save()` 写回系统相册，带上时间、GPS、相对目录、相册名

进度按**字节**上报而不是张数——照片大小差得多，按张算的进度条会一顿一顿。

**任务去重**：所有还原任务都用「唯一任务名 + `ExistingWorkPolicy.KEEP`」。一键全部用固定名，单张/选中用 id 集合的哈希做名字。这样连点不会堆积。详细原理见 [android-restore-dedup.md](android-restore-dedup.md)。

顶部进度条 `RestoreBanner` 按 `RestoreWorker.TAG` 聚合所有 RUNNING 任务的进度——因为超过 200 张会切批并发，加起来才是真实总进度。

### 4.4 凭据存储

`SecretStore` 用 Android Keystore 加密保存 token，并且关闭了应用备份（`allowBackup=false`），避免 token 被 ADB backup 导出。

---

## 五、飞牛 fnOS 打包

`fnos/photovault/` 是一个标准 fpk 的骨架：

| 文件 | 作用 |
|---|---|
| `manifest` | 应用元信息：`service_port=8765`、`checkport=true`、`platform=all` |
| `cmd/install_init`、`upgrade_init` … | 生命周期的各个钩子 |
| `app/docker/docker-compose.yaml` | 实际的容器编排 |
| `config/privilege`、`config/resource` | 运行权限与资源声明 |
| `ui/` | 应用中心里显示的图标和配置页 |

关键是路径变量的约定：

```
/vol2/@appcenter/photovault   应用包目录（升级会被整体覆盖，不要往里写数据）
/vol2/@appdata/photovault     数据目录（跨升级保留，TRIM_PKGVAR 指向这里）
```

compose 里挂的是 `${TRIM_PKGVAR}/data:/data`。

**这是最容易出事的地方**：想换数据位置时不要改 compose，因为它在 `@appcenter` 下，下次应用中心升级会整个覆盖，路径悄悄变回去，用户看到的现象是「照片全没了」。正确做法是把 `@appdata/<app>/data` 换成指向新位置的符号链接——`@appdata` 跨升级保留，compose 一行不用动，内核在 mount 时解析符号链接，`docker start` 即可生效。

---

## 六、API 一览

全部接口都在 `/api` 下，除 `health`、`ping`、`login` 外都需要 `Authorization: Bearer <token>`。

| 分组 | 端点 | 说明 |
|---|---|---|
| 健康 | `GET /api/health`、`POST /api/ping` | 存活与连通性 |
| 认证 | `POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`、`POST /api/auth/password` | 登录限速在反代层做 |
| 用户管理 | `GET/POST /api/admin/users`、`PATCH/DELETE /api/admin/users/{uid}`、`POST .../password` | 仅 admin |
| 上传 | `POST /api/upload`、`POST /api/photos/exists`、`POST /api/photos/prescreen` | 上传前预判，减少无效传输 |
| 浏览 | `GET /api/photos`、`GET /api/photos/{id}`、`/thumb`、`/file` | 分页、筛选、按时间排序 |
| 回收站 | `GET /api/trash`、`POST /api/photos/{id}/restore`、`DELETE /api/trash` | 软删除 |
| 聚合 | `GET /api/albums`、`/folders`、`/calendar`、`/stats` | 网页端左侧栏 |
| 巡检 | `GET /api/admin/integrity` | 校验索引与磁盘是否一致 |
| 网页 | `GET /` | 内嵌管理控制台（单文件 HTML） |

网页控制台没有构建步骤：HTML/CSS/JS 直接内嵌在 `main.py` 的字符串里。改完必须跑 `python tools/check_web.py` 做 JS 语法自检，否则一个手滑的括号会让整个页面白屏。

---

## 七、安全模型

假设 NAS 会暴露在公网，所以按「不可信网络」设计。

| 威胁 | 对策 | 代码位置 |
|---|---|---|
| 密码泄露（拖库） | pbkdf2_sha256，每用户独立 salt | `auth.py:hash_password` |
| Token 被盗 | HMAC 签名 + 过期时间 + epoch 吊销 | `auth.py` |
| 改密码后旧设备仍有效 | `token_epoch + 1` | `auth.py:reset_secret` 附近 |
| 暴力破解登录 | 反代层 `limit_req`（5r/m，burst 3） | 部署侧 nginx |
| 越权看别人的照片 | SQL 层强制 `owner_id` 过滤 | `db.py` 全部查询 |
| 路径穿越 | 文件名白名单校验 | `main.py` |
| 解压炸弹 / 异常图片 | 缩略图生成失败即回落，不阻塞 | `media.py` |
| 大文件打爆磁盘 | `MAX_UPLOAD_MB`（默认不限，需自行设定） | `config.py` |
| 明文端口暴露 | 只暴露反代端口，8765 由防火墙封在内网 | 部署侧 |
| 响应头缺失 | HSTS、X-Content-Type-Options、缩略图 `private` | 反代 / `main.py` |

**默认管理员密码是 `photovault`**，`INIT_ADMIN_PASSWORD` 只在首次初始化时生效。上线前必须改，`tools/security_check.py` 会检查这一点。

---

## 八、关键设计决策

记录几个当初权衡过、后来没改的点，避免以后有人重复讨论。

**为什么用 SQLite 而不是 Postgres**
单用户到几十用户的量级，SQLite 的读性能完全够（几万张照片的分页在 10ms 级）。引入 Postgres 意味着多一个容器、多一份备份对象、多一个会挂的东西。NAS 场景下「少一个组件」比「多一点性能」值钱得多。

**为什么不做「按内容寻址 + 硬链接」**
技术上可行（同一 sha256 只存一份，用户之间硬链接），但会让回收站的语义变得很复杂：删一个用户的照片不能删字节，要检查引用计数。当前的「跨用户复用 rel_path、记录各存一份」已经达到了去重的目的，复杂度低一个量级。

**为什么不支持「修改照片」**
任何编辑都会破坏字节完整性，与第一目标冲突。要修图就在手机上修完再传，服务端只负责存。

**为什么网页端不用框架**
单文件内嵌 HTML 意味着部署时不需要 `npm install`、不需要打包产物、不会出现「源码和构建产物不一致」。代价是没有组件化，改起来要靠纪律。

**为什么还原要用 WorkManager 而不是前台服务自己写**
WorkManager 处理了 Doze、进程被杀、重启恢复这些脏活。自己写前台服务在国产 ROM 上会被杀得很惨。

---

## 九、已知限制

| 限制 | 影响 | 可能的做法 |
|---|---|---|
| RAW 无缩略图 | 网页端显示占位图 | 引入 libraw / exiftool 提取内嵌预览 |
| 单进程 uvicorn | 上传是 CPU 密集（sha256）时其他请求会排队 | 多 worker + 共享 SQLite（WAL） |
| 无端到端加密 | NAS 管理员能看到所有照片 | 客户端加密，代价是服务端无法生成缩略图 |
| 无增量同步协议 | 每次备份都要先问 exists | 引入 sync cursor |
| 回收站不自动清理 | 长期占用磁盘 | 定时任务按 `deleted_at` 清理 |

---

## 十、开发与验证入口

| 想做什么 | 命令 |
|---|---|
| 进程内冒烟 | `python tools/smoke_test.py` |
| 真起服务走 HTTP | `python tools/e2e_live.py` |
| 多用户权限专项 | `python tools/e2e_users.py` |
| 加固/回收站/限流 | `python tools/e2e_hardening.py` |
| 网页 JS 语法自检 | `python tools/check_web.py` |
| 真实浏览器点一遍 | `python tools/browser_check.py` |
| 安全体检 | `python tools/security_check.py` |
| 数据增量备份 | `python tools/backup_data.py` |
| 编译 APK | `cd android && ./gradlew :app:assembleDebug` |

本地编译安卓需要 JDK 17。Windows 上 Android Studio 自带的 JBR 就够：

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
```

服务端没有任何第三方依赖之外的东西，`pip install -r server/requirements.txt` 就能跑。测试依赖单独在 `tools/requirements-dev.txt`。

---

## 十一、代码导览顺序

第一次读这个项目，建议按这个顺序：

1. `server/app/config.py` —— 先搞清楚数据放哪、支持什么格式
2. `server/app/db.py` 的建表语句 —— 数据模型定了，后面都好懂
3. `server/app/main.py` 的 `POST /api/upload` —— 整个项目的核心流水线
4. `server/app/auth.py` 的 token 部分 —— 理解为什么改密码能踢掉所有设备
5. `android/.../work/UploadWorker.kt` —— 客户端怎么扫相册、怎么判重
6. `android/.../work/RestoreWorker.kt` —— 反向流程，以及进度上报
7. `fnos/photovault/manifest` + `docker-compose.yaml` —— 最后看打包，知道路径变量的坑

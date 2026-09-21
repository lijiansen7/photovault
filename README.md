# PhotoVault · NAS 照片备份与还原

安卓手机自动把本地相册备份到自家 NAS，需要时按原文件一键还原回手机，
**拍摄时间、GPS 位置、相机型号完整保留**。

```
安卓手机 (Kotlin / Compose)                        NAS (Docker / FastAPI)
┌─────────────────────────────┐                  ┌────────────────────────────┐
│ 设置：自己填 NAS 地址+Token  │                  │  /api/upload   原样落盘     │
│ WorkManager 周期自动备份     │ ── HTTPS/HTTP ─▶ │  /data/photos  原文件字节    │
│ 读 MediaStore + EXIF        │                  │  index.db      SQLite 索引  │
│ SHA256 指纹去重             │ ◀──────────────  │  /api/photos   列表/缩略图   │
│ 选图还原 / 一键还原          │                  │  /api/…/file   原文件下载    │
└─────────────────────────────┘                  └────────────────────────────┘
```

---

## 一、部署 NAS 服务端

### 1. Docker（推荐）

```bash
cd server
# 编辑 docker-compose.yml，改这两处（详见 docs/docker-deploy.md）：
#   1) INIT_ADMIN_PASSWORD —— 初始管理员密码，务必改掉默认的 photovault
#   2) volumes —— 照片存放目录，默认 ./photovault-data
docker compose up -d
docker compose logs -f photovault     # 日志里会打印初始管理员账号密码
```

浏览器打开 `http://<NAS的IP>:8765`，用**账号密码**登录（用户名 `admin`，
密码就是你设的 `INIT_ADMIN_PASSWORD`），首次登录会强制改密，能看到照片墙就说明成了。

群晖 / 威联通用户：把 `docker-compose.yml` 里的
`- ./photovault-data:/data` 改成 `- /volume1/photo:/data`（群晖）
或 `- /share/Container/photo:/data`（威联通），照片就会落在你自己的共享文件夹里。

> 📘 完整部署教程（目录准备、反代 HTTPS、备份升级、排错、忘记密码的应急办法）
> 见 **[docs/docker-deploy.md](docs/docker-deploy.md)**。
>
> ⚠️ 别再用 `sed -i 's/photovault/你的密钥/' docker-compose.yml` 改密码了 ——
> 它会把 `image: photovault:1.0.0` 一起改坏，镜像名直接失效。手动编辑那一行即可。

### 2. 不用 Docker

```bash
pip install -r server/requirements.txt
DATA_DIR=/volume1/photo ACCESS_TOKEN=你的密钥 python server/run.py
```

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ACCESS_TOKEN` | `photovault` | 旧版单一 Token；同时也是初始管理员 `admin` 的初始密码 |
| `ALLOW_LEGACY_TOKEN` | `0` | 旧版单一 Token 通道，**默认已关闭**。App 只能账号登录；设 1 才会重新接受用 ACCESS_TOKEN 直连（应急用） |
| `INIT_ADMIN_USER` / `INIT_ADMIN_PASSWORD` | `admin` / `ACCESS_TOKEN` | 首次启动自动创建的管理员 |
| `TOKEN_TTL_HOURS` | `720` | 登录 token 有效期 |
| `SECRET_KEY` | 自动生成并存于 `data/.secret` | token 签名密钥，多副本部署要显式指定同一个值 |
| `MAX_FAILED_LOGINS` | `5` | 连续输错几次锁定账号 |
| `LOCKOUT_SEC` | `600` | 锁定时长（秒） |
| `DATA_DIR` | `/data` | 照片 + 索引存放目录 |
| `MAX_UPLOAD_MB` | `0` | 单文件大小上限（MB）。**`0` = 不限制**（默认）；设成正数才会拒掉超限文件 |
| `THUMB_SIZE` | `512` | 缩略图边长 |
| `ENABLE_THUMB` | `1` | 置 0 关闭缩略图 |
| `TZ` | — | 建议 `Asia/Shanghai` |

---

## 三、用户、登录与网页管理

### 网页管理端

浏览器打开 `http://<NAS的IP>:8765`：

- 未登录先看到登录框。**初始管理员**在服务端首次启动时自动创建：
  用户名由 `INIT_ADMIN_USER` 指定（默认 `admin`），
  密码取 `INIT_ADMIN_PASSWORD`；没设的话回退到 `ACCESS_TOKEN`，再没有才是 `photovault`。
  启动时日志里会打印出来（Docker：`docker compose logs -f photovault`）。
- 登录后两个页签：
  - **照片**：顶部显示统计（照片 / 视频各多少、总大小）；**按拍摄日期分组**
    （今天 / 昨天 / 具体日期，每组标数量）；搜索文件名 / 相册 / 设备；
    按用户筛选（管理员）；批量选择 + **全选** + 一键删除；点缩略图看原图
  - **用户**（仅管理员）：新建账号、改角色、禁用 / 启用、重置密码、删除账号
  - **维护**（仅管理员）：完整性巡检、清空回收站、改自己的密码
- 删除是**批量接口**（一次请求搞定，不会因为选了几百张就发几百个请求），且先进回收站可恢复
- 右上角退出登录。

> ⚠️ 首次登录后请立刻改掉初始密码。

### 权限模型

| | 看照片 | 用户管理 |
|---|---|---|
| 普通用户 | 只能看自己备份上来的 | 不行 |
| 管理员 | 全部（可按用户筛选） | 可以 |

越权访问一律返回 404（不给"存在但没权限"的提示）。改密码、禁用账号会让该用户
**已经发出的所有 token 立即失效**，手机端会提示重新登录。

**内容级去重**：同一份字节在 NAS 上只存一份。两个人备份了同一张照片时，
各自有一条记录、互不干扰，但磁盘只占一份空间。

### 手机 App 登录

设置页填 NAS 地址 + 账号密码 → 点「登录」，token 存在手机里。
勾了「记住密码」的话 token 过期会自动重登，没勾就要手动再登录一次。
「NAS 服务器」卡片会显示当前登录身份，可随时退出。

「备份」页顶部的连接卡片只显示**状态**（已连接 / 已登录谁 / 未配置），
不再把服务器地址摆在上面——地址是配置项，要改去「设置」页。

### App 的备份控制

**配好地址和账号 ≠ 同意开始上传。** 自动备份是显式开关，默认关闭：

| 操作 | 行为 |
|---|---|
| 「自动备份」开关（默认关） | 打开后才下发周期任务，按周期检查新照片 |
| 「选择要备份的相册」 | 列出手机上的所有相册（Camera / DCIM / Pictures / WeiXin…）及各自张数，只备份勾选的那些。默认全选 |
| 「立即同步」 | 随时手动跑一次，**不受开关影响** |

相册选择用 `BUCKET_DISPLAY_NAME` 做标识，`RELATIVE_PATH` 只作展示
（用来区分 `DCIM/Camera` 和 `Pictures/Camera` 这种同名目录）。

> 内部用 `null` 和「空集」区分两种状态：`null` = 还没挑过（全部备份），
> 空集 = 用户明确一个都不选。混用的话「全部取消勾选」会被当成「默认全部」。

### 安全须知

**目前是 HTTP 明文**，账号密码和 token 在网络上裸奔。家庭内网可接受，
但如果你的 WiFi 上有不信任的设备，或者要从外网访问，**务必套一层 HTTPS**：

- 群晖 / 威联通：控制面板申请 Let's Encrypt 证书，用反向代理把域名转到 `127.0.0.1:8765`，
  App 地址栏写 `https://照片.你的域名`
- 自建 nginx：
  ```nginx
  server {
    listen 443 ssl;
    server_name photo.example.com;
    ssl_certificate     /etc/letsencrypt/live/photo.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/photo.example.com/privkey.pem;
    client_max_body_size 1024m;        # 不然传大视频会被挡
    location / {
      proxy_pass http://127.0.0.1:8765;
      proxy_set_header Host $host;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      proxy_read_timeout 600s;         # 大文件上传别超时
    }
  }
  ```

已经做了的防护：

- **登录失败限流**：同一账号连续输错 5 次锁 10 分钟，锁定期间即使密码正确也登不进
- **强制改初始密码**：初始管理员首次登录会被要求改密，不改不给用
- **token 可撤销**：改密码、禁用账号会让该用户已发出的所有 token 立即失效
- **越权一律 404**：不返回"存在但没权限"，避免泄露照片存在性
- 密码用 PBKDF2-SHA256（12 万次迭代 + 随机盐）存储，不存明文

### 数据防丢

**照片只有一份，服务端就是单点。** 三件事建议都做：

**1. 定期备份数据目录**

```bash
python tools/backup_data.py D:\photo-backup          # 或 /volume2/photo-backup
python tools/backup_data.py D:\photo-backup --dry-run # 先看看要拷什么
```

增量同步（按大小 + 修改时间判断），只备份 `photos/`、`index.db`（用 SQLite 在线备份 API 取一致性快照）
和 `.secret`；缩略图缓存不备份（会自动重建）。挂到计划任务里每天跑一次。

**2. 删除先进回收站**

网页端删除的照片进回收站，可以随时恢复；照片页右上角有「回收站」开关。

> ⚠️ **「清空回收站」是真删文件，不是清缓存。** 它会把这批照片的**原始文件**
> 从磁盘上永久抹掉，找不回来。照片页的删除按钮只做软删除（进回收站），
> 真正动磁盘的只有「维护」页那一个按钮，点之前会明确告诉你要删多少个文件。
>
> 判断标准很简单：`server/data/photos/` 里还有文件，照片就还在；
> 目录空了，NAS 上就真的没有了（只剩手机相册那一份）。

**3. 定期跑完整性巡检**

网页端「维护」页 → 开始巡检。它会比对索引与磁盘：
文件是否都还在、大小是否一致、有没有没人认领的孤儿文件。
**磁盘静默损坏、误删文件这类问题，只有巡检能发现。**

```bash
# 也可以走接口
curl -H "Authorization: Bearer <token>" http://<NAS>:8765/api/admin/integrity
```

---

## 四、构建安卓 App

### 1. 准备工具链（JDK 17 + Android SDK + Gradle）

```bash
python tools/setup_android_sdk.py
```

脚本会把三件套装到 `~/.workbuddy/binaries/` 下（不动系统环境、不需要管理员权限），
并自动写好 `android/local.properties` 和 gradle wrapper。装完会打印一次 SDK 自检结果。

### 2. 编译

```bash
cd android
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

**版本号跟着构建时间自动生成**，不是写死的常量：

| | 形如 | 说明 |
|---|---|---|
| `versionName` | `1.1.0+0920.1756` | 精确到分钟 |
| `versionCode` | `26092017` | `yyMMddHH`，精确到小时 |

这样装到手机上能在「设置 → 应用 → PhotoVault」里确认装的到底是哪一次编的包，
App 的「设置」页底部也会显示版本号和构建时间。

> 两个坑写在 `build.gradle.kts` 注释里了：`.kts` 里的 `java` 是 Gradle 的扩展，
> 不能写 `java.util.Date`（要先 import）；`versionCode` 上限是 21 亿，
> `yyMMddHHmm` 会到 26 亿直接溢出，`toIntOrNull()` 静默返回 `1`。

### 3. 装到手机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **网络提示**：`services.gradle.org` 与 GitHub 在部分网络下不通。
> 脚本已默认改用国内可直连的源（腾讯镜像的 Gradle、微软的 OpenJDK 17、
> Google CDN 的 Android 命令行工具）。如果你那边 GitHub 通，
> 把 `setup_android_sdk.py` 里的 JDK 地址换成 Adoptium 即可。
> 若 `gradlew` 拉不动 Gradle 发行包，改 `gradle/wrapper/gradle-wrapper.properties`
> 里的 `distributionUrl` 为 `https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip`。

或直接用 Android Studio 打开 `android/` 目录 Run 到手机（Studio 会提示缺 SDK，
把路径指到 `~/.workbuddy/binaries/android-sdk` 即可复用已下好的）。

生成启动图标（仓库里已带，重画时执行）：

```bash
pip install Pillow && python tools/gen_icons.py
```

### 首次使用

1. 授予「照片」「位置（用于读照片内的 GPS）」权限 —— 位置权限不给的话时间还在、地点会丢。
2. 「设置」页填 NAS 地址（`192.168.1.10` / `http://nas.home:8765` 都行，不写端口默认 8765）
   → 填账号密码 → 点「登录」，显示「已登录：xxx」就成了。
   账号在网页端「用户」页创建；也可以先用初始管理员 `admin` / `photovault`。
3. 回「备份」页：**打开「自动备份」开关**（默认是关的，配好地址不等于同意开始传），
   再点「选择要备份的相册」勾掉不想传的（默认全选）。
   「立即同步」随时能手动跑一次，不受开关影响。
4. 「NAS 相册」页顶部显示统计（照片 / 视频各多少、总大小），按拍摄日期分组显示，
   缩略图列表可直接看。**点照片进全屏看原图**（带时间地点、可一键还原），
   **点视频交给系统播放器播放**。长按进多选，可勾选还原，或「一键还原全部」。

---

## 五、拍摄时间 / 地点是怎么保住的

这是本项目的核心，做了三道保险：

1. **原文件字节级原样存储**。服务端不重编码、不压缩、不剥离 EXIF，落盘的就是手机发来的原始字节。
   *冒烟测试第 10 项会比对下载文件的 SHA256 与上传时完全一致。*
2. **元数据双通道写入索引**。服务端用 Pillow + pillow-heif 解 EXIF（支持 HEIC/HEIF），
   同时优先采用手机端 `MediaStore.DATE_TAKEN` 与 `ExifInterface` 读到的值——
   手机拿到的往往比服务端解析更准，视频更是只有手机端才有时间。
3. **还原时写回系统相册三件套**：
   - 写的是原文件字节 → EXIF 里的 `DateTimeOriginal` / `GPSLatitude` / `GPSLongitude` 完好；
   - 同时把 `DATE_TAKEN` 写进 `MediaStore`（系统相册、Google Photos 排序用它）；
   - 再把文件 mtime 调回拍摄时间（照顾按 mtime 排序的老相册 App）。

**还原落点：回到备份前的原目录。**

备份时手机会把照片所在的相对目录（`RELATIVE_PATH`，如 `DCIM/Camera`）一起上报，
还原时按它写回去 —— 相册分组和备份前一模一样，不会挤进一个陌生的文件夹。

- 新备份的照片：精确还原到原目录
- 老数据（没有这个字段）：按相册名推断，`Camera` → `DCIM/Camera`、
  `Screenshots` → `Pictures/Screenshots`、其余 → `Pictures/<相册名>`（如 `Pictures/WeiXin`）
- 两条路都拿不到：才落到 `Pictures/PhotoVault`

> 推断用的是 `BUCKET_DISPLAY_NAME` —— 它本身就是目录名，所以直接拿原样用
> 比硬编码映射表准（微信的目录叫 `WeiXin`，不是 `WeChat`）。

**手机里已经有同一张怎么办**

还原前会先扫一遍本机相册（只比对**文件名 + 字节数**，不读文件内容，
几千张也是一瞬间），再按设置处理：

| 策略 | 行为 | 适合 |
|---|---|---|
| **跳过**（默认） | 本地已有的不动，只补缺的 | 日常。反复点「一键还原」是幂等的，不会越堆越多 |
| 留两份 | 再存一份，MediaStore 自动改名成 `xxx (1).jpg` | 明确想留两份 |
| 覆盖 | 用 NAS 上的替换本地那份 | 本地文件损坏、被修图改过 |

> **覆盖做不到 100%**：Android 10+ 分区存储下，App 只能覆盖**自己创建**的文件。
> 系统相机拍的、微信保存的照片会抛 `SecurityException`，这时会自动降级成"再存一份"
> —— 选了覆盖也不会丢数据，只是那几张会多出一份。
> 还原完成的通知里会写明「已还原 N 个，跳过 M 个手机里已有，失败 K 个」。

> ⚠️ Android 10+ 起 MediaStore 默认抹掉照片里的 GPS，必须在 Manifest 声明并授予
> `ACCESS_MEDIA_LOCATION`，再用 `ExifInterface` 直读文件描述符才拿得到——代码里已经处理。

---

## 六、服务端 API

所有接口需要 `Authorization: Bearer <token>`（登录换取），
或兼容的 `X-Token` 头 / `?token=` 查询参数。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查（无需鉴权） |
| POST | `/api/auth/login` | **账号密码登录**，返回 token（无需鉴权） |
| GET | `/api/auth/me` | 当前身份 |
| POST | `/api/auth/logout` | 让当前 token 失效 |
| POST | `/api/auth/password` | 修改自己的密码（其他设备会被踢下线） |
| GET | `/api/admin/users` | 用户列表（管理员） |
| POST | `/api/admin/users` | 新建用户（管理员） |
| PATCH | `/api/admin/users/{id}` | 改角色 / 禁用（管理员） |
| POST | `/api/admin/users/{id}/password` | 重置某人密码（管理员） |
| DELETE | `/api/admin/users/{id}` | 删除账号，其照片转给操作者（管理员） |
| POST | `/api/ping` | 测试连接 + 鉴权 |
| POST | `/api/photos/exists` | 批量 SHA256 查重，手机上传前跳过已备份 |
| POST | `/api/upload` | 上传（multipart：`file`、`meta` JSON、`device_id`） |
| GET | `/api/photos` | 列表，支持 `limit/offset/kind/album/taken_from/taken_to/has_gps/order/owner` |
| GET | `/api/photos/{id}` | 单条元数据 |
| GET | `/api/photos/{id}/thumb` | 缩略图（按需生成并缓存） |
| GET | `/api/photos/{id}/file` | **原文件**下载 |
| DELETE | `/api/photos/{id}` | 软删除（进回收站） |
| POST | `/api/photos/delete` | **批量删除**，body `{"ids": [...]}`，网页端全选删除用 |
| GET | `/api/trash` | 回收站列表 |
| POST | `/api/photos/{id}/restore` | 从回收站恢复 |
| DELETE | `/api/trash` | 清空回收站（真正删文件） |
| GET | `/api/admin/integrity` | 完整性巡检（管理员） |
| GET | `/api/albums` `/api/calendar` `/api/stats` | 相册分组 / 按天计数 / 统计 |

`meta` 字段：`taken_at`、`gps_lat`、`gps_lon`、`gps_alt`、`width`、`height`、
`orientation`、`camera_make`、`camera_model`、`album`、`original_path`（均可省略）。

---

## 七、目录结构

```
server/
  app/config.py     环境变量与存储布局
  app/auth.py       用户、密码哈希（pbkdf2）、签名 token、用户 CRUD
  app/db.py         SQLite 索引（去重、隔离、列表、分组、统计）
  app/media.py      EXIF 抽取 + 缩略图（只读原文件，绝不改写）
  app/main.py       FastAPI 路由 + 网页管理控制台
  Dockerfile  docker-compose.yml  requirements.txt  run.py

android/
  app/src/main/java/com/photovault/app/
      PhotoVaultApp.kt          通知渠道 + 周期任务注册
      MainActivity.kt           三个 Tab
  data/SettingsRepo.kt      DataStore 配置（含登录态）
  data/NasApi.kt            OkHttp 封装：登录、Bearer token、流式上传
  data/MediaRepository.kt   MediaStore 查询
      util/ExifReader.kt        EXIF 时间/GPS 直读
      work/UploadWorker.kt      后台自动备份
      work/RestoreWorker.kt     还原（支持指定/全部）
      work/MediaStoreWriter.kt  写回系统相册，保留时间地点
      ui/…                      Compose 界面

tools/
  gen_icons.py               生成 launcher 图标
  smoke_test.py              进程内端到端冒烟测试
  e2e_live.py                拉起真实服务，走 HTTP 端到端验收
  e2e_users.py               多用户隔离与权限专项测试
  e2e_hardening.py           限流 / 回收站 / 巡检 / 粗筛 专项测试
  check_web.py               网页控制台内嵌 JS 语法自检（改完网页端必跑）
  browser_check.py           用真实浏览器跑一遍网页端操作，定位前端问题
  backup_data.py             数据目录增量备份（挂计划任务用）
  setup_android_sdk.py       一键装 JDK17 + Android SDK + Gradle
  start_server.bat           Windows 一键起服务（打印局域网地址）
  open_firewall.bat          Windows 放行 8765（需管理员）
  install_apk.bat            Windows 装 APK 到手机（可选 USB 转发）
  requirements-dev.txt       测试用依赖
```

---

## 八、测试

### 8.1 服务端自检（不需要起服务）

```bash
pip install -r server/requirements.txt -r tools/requirements-dev.txt
python tools/smoke_test.py
```

进程内跑完整链路，覆盖：鉴权、去重、查重、EXIF 解析（含 GPS 精度）、缩略图、
**下载原文件字节一致性**、**EXIF 时间/GPS/机型原样保留**、统计、删除。

---

### 8.2 在 Windows 上真跑一遍

不用 NAS、不用手机，也能把服务端验到底。

**① 一键起服务**

双击 `tools\start_server.bat`（或命令行 `start_server.bat 你的Token 8765`）。
它会打印本机局域网 IP，直接告诉你手机该填什么地址，数据落在 `server\data\`。

```bash
# 等价的命令行写法
set DATA_DIR=C:\...\Photo\server\data
set ACCESS_TOKEN=photovault
python server/run.py
```

**② 端到端验收**（会自己拉起 uvicorn、打完再关掉）

```bash
python tools/e2e_live.py                 # 临时端口，测完自动清理
python tools/e2e_live.py --keep          # 测完留着服务，方便手机连过来
python tools/e2e_live.py --base http://192.168.1.20:8765   # 打已运行的服务
```

14 项检查，含「下载文件字节与上传完全一致」和「EXIF 时间/GPS/机型一个不少」。
和 `smoke_test.py` 的区别是它走真实端口，能顺带暴露端口占用、防火墙这类问题。

### 8.3 多用户与权限

```bash
python tools/e2e_users.py
```

20 项检查：引导管理员登录、错误密码被拒、建用户、两人备份同一份字节只落盘一份、
越权访问 404、管理员看全部、非管理员进不了用户管理、改密与禁用让旧 token 失效、
删账号照片不丢、旧 Token 兼容仍在、无 token 一律 401。

### 8.4 加固与防丢

```bash
python tools/e2e_hardening.py
```

13 项检查：初始管理员被要求改密、连续输错 5 次锁定且正确密码也拦、限流只锁单个账号、
粗筛命中与用户隔离、删除进回收站、一键恢复、清空回收站真删文件、
巡检在干净时健康 / 人为删文件后立刻报缺失。

**③ 浏览器验收**

打开 `http://127.0.0.1:8765`，输入 Token，能出照片墙就成了。

---

### 8.5 网页控制台自检

```bash
python tools/check_web.py
```

网页端的 HTML/JS 是内嵌在 `server/app/main.py` 里的字符串常量。
手写 JS 时容易踩 Python 转义的坑 —— 比如 Python 先把 `\n` 解释成真实换行，
JS 字符串就跨行了，**整个脚本不执行，浏览器里表现为整页白屏**，
不看控制台根本猜不到原因。

这个脚本把 `<script>` 抠出来交给 `node --check` 验语法，改完网页端跑一下就能发现。

### 8.6 网页端行为验证（真实浏览器）

```bash
python tools/browser_check.py
```

语法对了不等于行为对。前端出问题时光看代码很难判断到底是"没选中"、"请求失败"
还是"页面卡住"。这个脚本用 Edge/Chrome 的无头模式 + CDP 真的去点按钮，
把每一步的中间状态打出来（设了几个、按钮什么文案、服务端最后剩几条）。

它自己起隔离的测试服务和临时数据目录，**不碰你的真实照片**。

### 8.7 手机连过来测

三种连法，挑一个：

| 方式 | App 里填的地址 | 前提 |
|---|---|---|
| 手机与电脑同一 WiFi | `http://192.168.108.20:8765` | 需放行防火墙（见下） |
| USB 数据线 + 转发 | `http://127.0.0.1:8765` | `adb reverse tcp:8765 tcp:8765` |
| 手机热点给电脑 | `http://电脑在热点里的IP:8765` | 需放行防火墙 |

**装 APK**：双击 `tools\install_apk.bat`（会自动找 SDK 里的 adb），或手动
`adb install -r android\app\build\outputs\apk\debug\app-debug.apk`。
脚本会问要不要设 `adb reverse` 转发——**用 USB 转发不需要动防火墙，实测最省事**。

**放行防火墙**（同一 WiFi 时必须）：右键 `tools\open_firewall.bat` → 以管理员身份运行。
也可以手动：Windows Defender 防火墙 → 高级设置 → 入站规则 → 新建规则 → 端口 TCP 8765 → 允许。
Python 首次监听端口时 Windows 也会弹窗，点「允许访问」同样生效。

**最快的连通性验证**：手机浏览器打开 `http://<电脑IP>:8765/api/health`，
返回 `{"ok":true,...}` 即网络通，再去 App 里填地址就不会白折腾。

---

### 8.8 手机端验收清单

1. 设置页填地址 + Token → 「测试连接」显示成功
2. 备份页「立即备份」→ 通知栏出现结果，NAS 端 `server\data\photos\` 下出现文件
3. 再点一次「立即备份」→ 应提示全部跳过（说明 SHA256 去重生效）
4. NAS 相册页能刷出缩略图网格
5. 勾一张 → 还原 → 到系统相册里看，**拍摄日期应是原日期、地点信息还在**
6. 「一键还原全部」→ 批量写回
7. 在电脑上比对：还原出来的文件与原文件 SHA256 应一致

---

## 九、常见问题

**忘记管理员密码 / 想重设？**
服务端上执行（停服务再跑也行）：
```bash
python -c "import sys; sys.path.insert(0,'server'); \
from app import auth; auth.init_users_table(); \
auth.set_password(auth.get_user_by_name('admin')['id'], '新密码')"
```
注意 `DATA_DIR` 要指向真实数据目录。

**手机上提示「登录已失效」？**
服务端改过这个账号的密码，或账号被禁用了。到设置页重新登录即可。

**还需要老的单一 Token 吗？**
不需要了 —— App 已改成只能账号登录，服务端 `ALLOW_LEGACY_TOKEN` 默认也是关的。
真遇到紧急情况（比如账号全被锁），可以临时设成 `1` 用 `ACCESS_TOKEN` 直连救急，
但别长期开着：那等于留了一个"用公开字符串就能拿管理员权限"的后门。

**手机连不上 NAS？**
先在手机浏览器打开 `http://<IP>:8765/api/health`，能返回 `{"ok":true}` 说明网络通；
再看 App 设置里的地址有没有带端口、Token 是否一致。用 HTTPS 反代时地址要写完整 `https://…`。

- 浏览器也打不开 → 八成是 Windows 防火墙拦了入站，跑 `tools\open_firewall.bat`（管理员）；
- 只想先验证功能、懒得配网络 → 用 USB 线 + `adb reverse tcp:8765 tcp:8765`，
  App 里填 `http://127.0.0.1:8765`，完全绕开防火墙。

**HTTPS 证书是自签的？**
安卓会拒绝。要么在 NAS 上用 Let's Encrypt（群晖自带），要么走局域网 IP + HTTP（已开启明文）。

**还原后相册里时间还是"今天"？**
确认授予了「位置」权限后重新备份一次；EXIF 本身没丢，把文件丢到电脑上用看图软件看属性即知。

**HEIC 照片？**
服务端 `pillow-heif` 已支持解析与缩略图；原文件照常原样保存，还原到手机仍是可直接查看的 HEIC。

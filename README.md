# PhotoVault · NAS 照片备份与还原

安卓手机自动把本地相册备份到自家 NAS，需要时按原文件一键还原回手机，
**拍摄时间、GPS 位置、相机型号完整保留**。

> 全部功能项（手机端 / 服务端 / 部署）见 [docs/功能清单.md](docs/功能清单.md)。

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

## 更新日志

> 服务端（fnOS 原生包 / Docker 镜像）和 Android App 的版本号各自独立，不必相同：
> **服务端当前 2.0.7，App 当前 2.0.2**。
> 每个版本的安装包都在 [Releases](https://github.com/lijiansen7/photovault/releases)。

### 版本对照

| Release | 日期 | 服务端 | App | 一句话 |
|---|---|---|---|---|
| [v2.0.3](https://github.com/lijiansen7/photovault/releases/tag/v2.0.3) | 2026-11-24 | 2.0.7 | 2.0.2 | 修 2.0.6 包缺 `run.py` 装上去起不来（启动脚本失败） |
| [v2.0.2](https://github.com/lijiansen7/photovault/releases/tag/v2.0.2) | 2026-11-24 | 2.0.6 | **2.0.2** | 视频备份报 500；2000+ 张的库只传 800 多张就卡在「正在扫描」 |
| [v2.0.1](https://github.com/lijiansen7/photovault/releases/tag/v2.0.1) | 2026-11-24 | 2.0.5 | **2.0.1** | App 版本号并入 2.x；相册页重做（搜索/筛选/时间轴）、内置播放器、包体砍到 5.9MB |
| v1.3.7 | 2026-11-20 | 1.4.0 | 1.2.3-timefix | 还原到手机相册后拍摄时间晚 8 小时 |
| v1.3.6 | 2026-10-25 | 1.3.6 | 1.2.3 | 安全加固（token 吊销、登录限流、越权校验） |
| v1.3.5 | 2026-10-22 | 1.3.5 | 1.2.2 | 手机端还原进度条 |
| v1.3.4 | 2026-10-21 | 1.3.4 | 1.2.1 | 飞牛桌面点「登录」无反应；页面版本号改用主程序 mtime |
| v1.3.3 | 2026-10-21 | 1.3.3 | 1.2.1 | 回收站缩略图两端空白 |
| v1.3.2 | 2026-10-21 | 1.3.2 | 1.2.0 | 首个带 APK 的发布；App 首页与「关于」页 |
| v1.3.1 | 2026-10-21 | 1.3.1 | — | 网页端照片只显示到 9 月 25 日 |
| v1.3.0 | 2026-09-22 | 1.3.0 | 1.1.0 | 首个正式版：飞牛应用包 + APK + 双架构离线包 |
| v1.2.x | 2026-09-22 | 1.2.x | — | 飞牛统一网关、离线包双架构、alpine 底座瘦身 |
| v1.1.x | 2026-09-22 | 1.1.x | — | 飞牛 fnOS 应用包骨架、生命周期脚本、GHCR 镜像 |

### 安装包怎么选

| 文件 | 用在哪 |
|---|---|
| `photovault-2.0.2.apk` | Android 手机。装上后能覆盖旧版本（同一个调试签名，`versionCode` 跟构建时间走） |
| `photovault-native-2.0.7-x86.fpk` | 飞牛 fnOS **x86** 机器（N100 / J4125 那一类）。原生包，不依赖 Docker，装完直接用系统 `python3` 跑 |
| `photovault-native-2.0.7-arm.fpk` | 飞牛 fnOS **arm** 机器（F2V 等）。由 GitHub Actions 交叉构建，`aarch64` 的 wheel 一起打进包里 |
| `photovault-offline-*.fpk` | 老的 Docker 离线包（镜像 `docker save` 进包），只在不想用原生包时选 |

两个原生包的安装方式一样，`platform` 字段不同，装错机器飞牛会直接拒绝。

### 服务端

**2.0.7** · 2026-11-24
- 修**原生包装上去起不来**：CI 打包只拷了 `server/app` 和 `requirements.txt`，
  漏了原生专用的 `run.py`（`cmd/main` 第 88 行检查它，缺了直接 `return 1`，
  飞牛报「执行应用启动脚本失败 / 应用异常退出」）和 `relay.py`（网关 socket 与
  8766 转发，`run.py` 里 import 它）。两个文件放进 `fnos-native/server/` 单独维护。
- 打包流程加断言：`app/server/{run.py,relay.py,requirements.txt,app/main.py}`
  少一个直接失败，不再产出装不上的包。

**2.0.6** · 2026-11-24
- 修**视频备份 HTTP 500**：`/api/upload` 里读客户端封面帧用的是 `UploadFile.read()`，
  那是 async 方法，在同步端点里拿到的是 coroutine 对象，写盘时炸
  `TypeError: memoryview: a bytes-like object is required, not 'coroutine'`。
  改成读底层 `file` 对象，并把封面写入包在容错里 —— 封面存不下也不该让整次上传失败。
- 版本号统一：`VERSION` 与两份 `manifest` 都是 2.0.6。

**2.0.5** · 2026-11-23
- `/api/photos/{id}/file` 支持 HTTP `Range`：返回 `206` + `Content-Range`（含 `bytes=-N` 后缀区间），
  越界回 `416`。视频从此可以边下边播、拖进度条；没有它播放器连 MP4 尾部的 `moov` 都读不到。
- 原生包（非 Docker）支持 **arm64**：`.github/workflows/build-native-fpk.yml` 按目标架构
  `pip download` 对应的 `manylinux` wheel（`x86_64` / `aarch64`，cp311），改 `manifest` 的 `platform` 后 `fnpack build`，
  两个架构都能在 CI 上一次出包。
- 密码下限与用户名字符集收紧（禁空格、引号、尖括号、斜杠、分号、反引号），显示名过滤不可打印字符。

**2.0.4** · 2026-11-23
- 删掉服务端自签 HTTPS，只保留 HTTP（HTTPS 交给反代或飞牛网关）。
- 移除证书上传/开关接口、HSTS 头、`seed` 逻辑，设置页对应卡片一并删除。

**2.0.3** · 2026-11-23
- 数据目录改为安装时可填（`pv_data_dir`），不再写死到应用目录，避免卸载重装丢照片。

**2.0.2** · 2026-11-23
- 修飞牛网关下 502 Bad Gateway（服务只监听 unix socket / 端口不匹配）。
- 修数据目录指向空库导致「照片全没了」。

**2.0.1** · 2026-11-23
- 修安装失败 `cmd/pv_install_deps: No such file or directory`（rc=127）：
  `install_init` 执行时文件还在 `@appcenter-downloads`，不能按安装后的路径找。

**2.0.0** · 2026-11-23
- **改为飞牛原生应用，不再依赖 Docker**：Python + wheelhouse 随包分发，安装即用。
- 包体 40MB → 15MB；启动不再 `docker load`。

**1.4.0** · 2026-11-22
- 网页控制台：吸顶毛玻璃顶栏、胶囊标签栏、回收站独立页、批量恢复/永久删除、文件夹筛选、关于页教程。
- 设备 ID 管理：管理员可看设备列表、迁移与自定义 ID。
- 支持 uvicorn 直连 HTTPS（2.0.4 已移除）。

**1.3.6** · 2026-10-25
- 安全加固：注销即吊销 token、登录失败限流、越权访问校验、媒体直链过期。

**1.3.5** · 2026-10-22 — 配合 App 1.2.2 的还原进度接口。
**1.3.4** · 2026-10-21 — 桌面「登录」无反应；页面版本号改用主程序 mtime 并显示接口前缀。
**1.3.3** · 2026-10-21 — 修回收站缩略图两端留白。
**1.3.1** · 2026-10-21 — 修网页端照片只显示到 9 月 25 日（时间过滤/分页）。
**1.3.0 / 1.2.x / 1.1.x** · 2026-09-22 — 飞牛应用包骨架、BOM/CRLF 修正、离线包（镜像随包分发）双架构、
走统一网关解决 HTTPS 混合内容、alpine 底座与依赖精简、发布后冒烟测试、子路径部署（`URL_PREFIX`）。

### Android App

**2.0.2** · 2026-11-24
- 修**「备份到 800 多张就一直扫描」**：单次上限 800 打完必须接续下一批，
  而原来接续的条件是「本批零失败」—— 库里有一张传不上去（当时是视频 500），
  后面一千多张就永远排不到。现在打满上限就接续，不看失败。
- 同步水位改为「本批有进展就推进」，不再是「全部成功才推进」。
  否则失败一次就永远停在第一批，每次重试都从头扫全库、重算一遍指纹。
- 失败清单会自动补传：下一轮如果没有新照片、清单里还有 7 天内的失败项，
  自动重试那几张（不再全库重扫），用户不用手动点。
- 部分失败不再触发整批重试，只有「一张都没成功」才整批重来。

**2.0.1** · 2026-11-24
- 版本号从 `1.4.0` 并入 2.x 线，与服务端同一套编号。
- **相册页重做**：搜索（文件名/相册/机型/设备，350ms 防抖）、筛选（文件夹、有定位、照片/视频、年份）、
  日期分组吸顶标签、右侧年份索引、骨架屏、空态/错误态、「更多」菜单收拢刷新与批量还原。
- **选择模式补齐操作条**：`已选 N 张 · 全选 · 还原 · 删除`，删除前确认并提示只进 NAS 回收站。
- **内置播放器**（media3/ExoPlayer）：带鉴权头直接拉原文件、记住播放进度、解不了码时说人话并可重试。
- 首页：存储概况并入 NAS 连接卡、最近备份缩略图、未配置时出三步引导、失败明细可单张/全部重试。
- 错误提示改说人话（超时/连不上/登录失效），「详情」可展开原始异常。
- 开启 R8（minify + shrinkResources），**APK 22.2MB → 5.9MB**。
- 无障碍：缩略图整格一句话描述、日期标题设为 heading、年份索引读作「2026 年」。

**1.4.0** · 2026-11-22
- 首页/导航/相册/回收站/设置统一视觉；内网优先、连不上自动切外网。
- 一键还原进度、可取消；回收站恢复；稳定设备 ID（卸载重装不变）与自定义 ID。

**1.2.3** · 2026-10-25 — 配合服务端安全加固，token 失效自动重登。
**1.2.2** · 2026-10-22 — 还原到手机相册显示进度条。
**1.2.1** · 2026-10-21 — 登录问题修复与设置页细节。
**1.2.0** · 2026-10-21 — 首页改版，设置页「关于」增加项目地址 / Releases / Issues 入口。
**1.1.0** · 2026-09-22 — 首个公开版本：自动备份、原文件还原、EXIF 保留。

---

## 一、部署 NAS 服务端

### 1. Docker（推荐）

> 🐮 **飞牛 fnOS 用户**：有现成的应用包（.fpk）+ GHCR 镜像，
> 见 [docs/fnos-app.md](docs/fnos-app.md)。

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
> 它会把 `image: photovault:1.3.0` 一起改坏，镜像名直接失效。手动编辑那一行即可。

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
| `ALLOW_LEGACY_MEDIA_TOKEN` | `1` | 旧版 App 的 `?token=` 图片直链兼容，只对媒体 GET 生效；全部手机升级后设 0 |
| `INIT_ADMIN_USER` / `INIT_ADMIN_PASSWORD` | `admin` / `ACCESS_TOKEN` | 首次启动自动创建的管理员 |
| `MIN_PASSWORD_LEN` | `8` | 新密码最短长度（老账号不受影响） |
| `TOKEN_TTL_HOURS` | `720` | 登录 token 有效期 |
| `LOGIN_RATE_LIMIT` | `60` | 同一 IP 5 分钟内最多登录尝试次数 |
| `PBKDF2_ITERS` | `600000` | 密码哈希迭代次数 |
| `SECRET_KEY` | 自动生成并存于 `data/.secret` | token 签名密钥，多副本部署要显式指定同一个值 |
| `MAX_FAILED_LOGINS` | `5` | 连续输错几次锁定账号 |
| `LOCKOUT_SEC` | `600` | 锁定时长（秒） |
| `DATA_DIR` | `/data` | 照片 + 索引存放目录 |
| `MAX_UPLOAD_MB` | `4096` | 单文件大小上限（MB）。**`0` = 不限制**；默认 4GB 兜底 |
| `USER_QUOTA_GB` | `0` | 每个账号最多占用多少 GB；0 = 不限制 |
| `MIN_FREE_MB` | `512` | 磁盘剩余低于此值（MB）时拒绝上传 |
| `ENABLE_DOCS` | `0` | 是否开放 `/docs` 与 `/openapi.json` |
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
- 登录后五个页签：
  - **照片**：顶部显示统计（照片 / 视频各多少、总大小）；**按拍摄日期分组**
    （今天 / 昨天 / 具体日期，每组标数量）；搜索文件名 / 相册 / 设备；
    按用户筛选（管理员）；**按文件夹筛选（全部 / DCIM/Camera / Pictures/… / 未分类）**；
    批量选择 + **全选** + 一键删除；点缩略图看原图
  - **回收站**：按日期查看已删照片，支持批量选择、恢复所选、永久删除所选
  - **用户**（仅管理员）：新建账号、改角色、禁用 / 启用、重置密码、删除账号
  - **维护**（仅管理员）：完整性巡检、清空回收站、改自己的密码
  - **关于**：服务端版本、页面构建时间、当前账号、健康状态，以及手机/网页使用教程和常见问题
- 回收站支持**批量选择**：可以多选后「恢复所选」或「永久删除所选」
- 删除是**批量接口**（一次请求搞定，不会因为选了几百张就发几百个请求），且先进回收站可恢复- 缩略图登录后使用 HttpOnly cookie 鉴权，URL 里不再带 token
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

设置页有两个地址：**内网地址（优先）** 和 **外网地址（备用）**，再填账号密码 → 点「登录」，token 存在手机里。
两个都填时先探测内网：2 秒内连不上就自动切外网；只有一条地址时行为和以前一样。
内网建议填 `http://NAS-IP:8766`（服务端内置的局域网 HTTP 兼容口），外网填 `https://域名:8765`。
勾了「记住密码」的话，后台备份/还原在 token 过期时会自动重登一次；没勾就要手动再登录一次。
「NAS 服务器」卡片会显示当前登录身份，可随时退出。

「备份」页顶部的连接卡片显示**状态**、延迟和当前实际走的地址（内网 / 外网）。

### App 的备份控制

**配好地址和账号 ≠ 同意开始上传。** 自动备份是显式开关，默认关闭：

| 操作 | 行为 |
|---|---|
| 「自动备份」开关（默认关） | 打开后才下发周期任务，按周期检查新照片 |
| 「选择要备份的相册」 | 列出手机上的所有相册（Camera / DCIM / Pictures / WeiXin…）及各自张数，只备份勾选的那些。默认全选 |
| 「立即同步」 | 随时手动跑一次，**不受开关影响** |
| 「手机删除后，NAS 也移入回收站」 | **默认关**。打开后，这台手机备份过、且本机已经删除的照片，会在下一次同步时从 NAS 移入回收站；「一键还原」不会再写回来 |

相册选择用 `BUCKET_DISPLAY_NAME` 做标识，`RELATIVE_PATH` 只作展示
（用来区分 `DCIM/Camera` 和 `Pictures/Camera` 这种同名目录）。

> 内部用 `null` 和「空集」区分两种状态：`null` = 还没挑过（全部备份），
> 空集 = 用户明确一个都不选。混用的话「全部取消勾选」会被当成「默认全部」。

### 手机删除同步

手机相册里删了照片，NAS 默认**不会**跟着删。这本来是为了防误删，但会带来一个后果：
下次点「一键还原全部」，那些已经删掉的照片又会被写回手机。

打开「设置 → 自动备份 → 手机删除后，NAS 也移入回收站」后：

- 只处理**这台手机备份过**、且本机相册里已经消失的照片；
- NAS 端只做软删除（进回收站），可以在网页端恢复，不会直接抹磁盘；
- 同一账号有多台手机时，删除只对当前设备生效，另一台手机的备份记录保留；
- 本机相册读不到（SD 卡未挂载）或一次缺失超过 80% 时，整轮自动跳过，
  并在通知里提示，避免把「读不到」误判成「删光了」；
- 从 NAS 回收站恢复某张照片后，各设备的删除标记会被清掉。

> 开启后，第一次同步会用 NAS 上「这台设备上传过」的记录建立本地清单；
> 之前手机删掉的照片如果还在 NAS 上，也会在这一轮被移入回收站。

### 安全须知

**目前默认是 HTTP 明文**，账号密码和 token 在网络上裸奔。家庭内网可接受，
但如果你的 WiFi 上有不信任的设备，或者要从外网访问，**务必套一层 HTTPS**：

### 方式一：服务端直接 HTTPS（不需要反向代理）

`server/entrypoint.sh` 已支持让 uvicorn 自己监听 HTTPS：

1. 把域名证书和私钥放到数据目录的 `certs/` 下，支持两种命名：
   - `fullchain.pem` + `privkey.pem`
   - 或按域名命名，例如 `photo.example.com.crt` + `photo.example.com.key`
2. 重启容器。日志出现 `Uvicorn running on https://0.0.0.0:8765` 即成功。
3. App / 浏览器地址改成 `https://你的域名:8765`；
   内网 App 地址用 `http://NAS-IP:8766`（服务端内置的局域网 HTTP 兼容口）。
   飞牛统一网关的 Unix Socket 会自动改用 TLS 连接本机 8765，不需要改网关。
4. 网页端「关于 → HTTPS 证书」里有 **启用/关闭 HTTPS** 开关。
   切换会写入 `data/certs/https.enabled` 并自动重启服务；
   关闭后 8765 回到 HTTP，App 地址也要改回 `http://`。
5. 证书续期后替换 `certs/` 里的文件并重启容器。

> 切到 HTTPS 后，原来的 `http://IP:8765` 会失效；局域网也统一用域名 HTTPS。
> 证书到期时间可以用 `openssl x509 -in certs/xxx.crt -noout -dates` 查看。

### 方式二：反向代理

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
- 密码用 PBKDF2-SHA256（60 万次迭代 + 随机盐）存储，不存明文；新密码至少 8 位
- 登录按 IP 限流；连续输错锁定账号
- 退出登录只吊销当前设备，改密码才踢掉全部设备
- 浏览器看图的 cookie 是 HttpOnly；API 不接受 URL 里的 token（媒体 GET 仅为旧 APK 保留可关闭的兼容开关）

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
| `versionName` | `1.3.0+0920.1756` | 精确到分钟 |
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
2. 「设置」页填地址：
   - 内网（优先）：`http://192.168.1.10:8766`（服务端为局域网 HTTP 兼容口）
   - 外网（备用）：`https://photo.example.com:8765`
   - 只填一个也可以；两个都填时内网 2 秒不通会自动切外网
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

所有接口需要 `Authorization: Bearer <token>`（登录换取）。
图片/视频的 GET 请求额外支持两种方式：网页端用登录时下发的 HttpOnly cookie；
系统播放器用 `GET /api/photos/{id}/link` 换一个 1 小时的短期签名。
**API 不再接受 `?token=` 查询参数**；只有媒体 GET 为了兼容旧版 APK 保留
`ALLOW_LEGACY_MEDIA_TOKEN=1`（默认开，全部手机升级后建议设 0）。

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
| GET | `/api/photos` | 列表，支持 `limit/offset/kind/album/taken_from/taken_to/has_gps/order/owner/q/viewer_device`；`viewer_device` 用来隐藏该设备已删除的照片 |
| GET | `/api/photos/{id}` | 单条元数据 |
| GET | `/api/photos/{id}/thumb` | 缩略图（按需生成并缓存，支持 `?size=`） |
| GET | `/api/photos/{id}/file` | **原文件**下载 |
| GET | `/api/photos/{id}/link` | 给系统播放器的 1 小时短期签名直链 |
| DELETE | `/api/photos/{id}` | 软删除（进回收站） |
| POST | `/api/photos/delete` | **批量删除**，body `{"ids": [...]}`，网页端全选删除用 |
| POST | `/api/photos/forget` | 手机删除同步：`{"ids": [...], "device_id": "..."}`，按设备打墓碑/进回收站 |
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
  app/main.py       FastAPI 路由 + 鉴权
  app/static/       网页控制台（index.html / console.js）
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
  regression_test.py         已修复缺陷的回归测试（MIME/巡检/删账号/签名）
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
python tools/regression_test.py
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

打开 `http://127.0.0.1:8765`，用账号密码登录，能出照片墙就成了。

---

### 8.5 网页控制台自检

```bash
python tools/check_web.py
```

网页端的 HTML/JS/CSS 在 `server/app/static/` 下（`index.html` + `console.js`），
不再塞在 Python 字符串里，也不再有转义地狱。

这个脚本会：
- 检查关键 DOM/函数是否还在；
- 把 `console.js` 交给 `node --check` 验语法；
- 回归检查禁用写法（URL 带 token、引用未定义变量、写错的引号）。

改完网页端跑一下，能挡住大部分“白屏/按钮点了没反应”。

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

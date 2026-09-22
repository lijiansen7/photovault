# 【第三方应用】PhotoVault —— 手机照片原样备份到飞牛，EXIF / 时间 / GPS 一张不丢

> 自己写的照片备份方案：**飞牛上的服务端 + 安卓手机 App**。
> 照片只落在你自己的硬盘里，不经任何公有云。
> 支持 amd64 / arm64，x86 和 ARM 机型都能装。

---

## 🤔 为什么又造一个轮子

- 云相册要么收费、要么压画质、要么照片在别人服务器上
- 换手机 / 重装系统后，最怕的就是**几千张照片的拍摄时间和定位全变成"导入日期"**
- 我想要的是很朴素的一件事：
  **原文件原样备份 → 随时还原回手机 → 时间、GPS、相机型号都还在**

---

## ✨ 能干什么

**① 原文件字节级备份，EXIF 完整保留**
不做转码、不压缩。还原回手机后，系统相册读到的还是**拍摄当天的日期和原来的定位**。

**② 还原回原相册目录（重点）**
哪备份的还原到哪：`DCIM/Camera`、`Pictures/WeiXin`……
不是统统丢进一个文件夹让你重新整理。

**③ 还原冲突三种策略**
跳过（默认，幂等，重复点不会堆副本）/ 留两份 / 覆盖。

**④ 内容去重，不糟蹋硬盘**
按 SHA256 指纹去重。同一张照片在微信、相机、截图里各存了一份？**只存一份**，也不会重复传。

**⑤ 增量备份 + 粗筛**
只传 NAS 上没有的，绝大多数照片**不用读文件内容**就能判定跳过，第二次备份几乎秒过。

**⑥ 按文件夹浏览 NAS 相册**
`DCIM/Camera`、`Pictures/WeiXin` 这样按真实目录分类查看，滑到底自动加载。

**⑦ 视频也有封面**
手机上传时抽一张首帧，服务端不用装 ffmpeg。

**⑧ 删除先进回收站**
误删能恢复，回收站支持**勾选后只清空选中的**。

**⑨ 多用户**
家里一人一个账号各看各的，管理员可看全部。

**⑩ 网页管理端**
按日期分组、批量选择删除、完整性巡检（索引和磁盘对账）。

**⑪ 取消备份 + 真实进度条**
同步中随时「取消」，已传的不受影响。

**⑫ 上传大小不限制**
几个 G 的长视频照样传（想兜底可自己设上限）。

---

## 📦 安装（两种方式）

### 方式一：应用包 .fpk（推荐）

下载：https://github.com/lijiansen7/photovault/raw/main/fnos/photovault.fpk
（约 10 KB，不含镜像；镜像首次启动时会从 GHCR 拉取）

飞牛 SSH 里执行：

```bash
appcenter-cli install-local photovault.fpk
```

装好后应用中心会出现 **PhotoVault** 卡片，点开就是网页管理端。

### 方式二：Docker Compose（不想装第三方包）

Docker → Compose → 新建项目，粘贴：

```yaml
services:
  photovault:
    image: ghcr.io/lijiansen7/photovault:latest
    container_name: photovault
    restart: unless-stopped
    ports:
      - "8765:8765"
    environment:
      INIT_ADMIN_USER: "admin"
      INIT_ADMIN_PASSWORD: "photovault"
      TZ: "Asia/Shanghai"
      MAX_UPLOAD_MB: "0"
    volumes:
      - ./photovault-data:/data
```

---

## 🚀 怎么用

1. 打开 `http://飞牛IP:8765`
2. 首次登录：**`admin` / `photovault`**，登录后会**强制改密码**（默认的太弱，必须改）
3. 手机装 App（Android 7.0+，17.9 MB），设置里填 `http://飞牛IP:8765`
   → 用刚建的账号登录 → 打开自动备份 → 选相册 → 立即同步
4. 想在外面备份：配合 **FN Connect** 或反代 + HTTPS，手机端地址填完整域名即可

> 手机和飞牛在同一局域网就行，不需要公网 IP。

---

## 💾 数据存在哪

应用数据目录下的 `/data`：

```text
data/
├── photos/       ← 原文件，命根子
├── cache/thumb/  ← 缩略图缓存，删了会自动重建
├── tmp/          ← 上传临时文件
└── index.db      ← SQLite 索引
```

**备份建议**：定期把 `photos/` + `index.db` 用飞牛自带的备份/快照任务复制到另一个盘，
`cache/` 不用备份。

---

## 🙅 它不是什么

先说清楚，免得踩预期：

- **只做备份与还原**，没有 AI 人脸识别、智能相册、地点相册这些
- **不是双向实时同步**，是手机 → NAS 的备份 + 按需还原
- **手机端目前只有安卓**（Android 7.0+），没有 iOS
- 需要手机能访问到飞牛的地址（局域网 / FN Connect / 反代）

---

## 🔗 链接

- 开源仓库：https://github.com/lijiansen7/photovault
- 应用包下载：https://github.com/lijiansen7/photovault/raw/main/fnos/photovault.fpk
- 安卓 APK：https://github.com/lijiansen7/photovault/releases
- 打包/排错文档：仓库 `docs/fnos-app.md`

源码全部公开（MIT），无广告、无埋点、不需要注册任何账号。
欢迎 star、提 issue，也欢迎在帖子里反馈 bug —— 我会持续修。

# PhotoVault v1.3.0 修复说明

对应这次代码审查报告，按“正确性 → 安全 → 性能 → 工程化”逐项修复。
完整改动见 `git diff`；本文只记结论、验证方式与升级注意事项。

## 已验证

| 项目 | 结果 |
|---|---|
| `python -m compileall -q server/app tools` | 通过 |
| `python tools/check_web.py`（DOM + `node --check` + 禁用写法） | 通过 |
| 真实 uvicorn：上传 / 缩略图 / 短期签名直链 / 静态 JS / 巡检 | 分别 200 / 200 / 200 / 200 / `healthy=true` |
| 媒体 cookie 鉴权 | 缩略图 200；只带 cookie 调 API 401（无 CSRF 面） |
| 删除同步（单设备） | `forget` 返回 1；该设备列表 0；回收站 1 |
| 删除同步（双设备） | A 删除后 `forget` 返回 0；A 列表隐藏、B 仍能看到 |
| Android `assembleDebug` | `BUILD SUCCESSFUL`，APK 18,844,545 字节 |

未在本沙箱执行：`smoke_test.py` / `regression_test.py` / `e2e_*`（缺 httpx、piexif，
且 pip 无网络）。这些已接入 `.github/workflows/tests.yml`，CI 会跑。

## 服务端

- **EXIF 时区**：无时区的 `DateTimeOriginal` 按本地时区/`OffsetTimeOriginal` 解析，
  不再当 UTC；`calendar` 也按本地日期分组。
- **上传不再阻塞事件循环**：`/api/upload` 等改同步端点（FastAPI 丢线程池）；
  请求体改用 Pydantic 模型，坏 JSON 返回 422 而不是 500。
- **MIME 只按扩展名**：不再采用客户端的 `Content-Type`；RAW 以
  `application/octet-stream` + `attachment` 返回，堵住存储型 XSS。
- **删用户不再 500**：`transfer_photos` 用 `UPDATE OR IGNORE` 处理
  `UNIQUE(owner_id, sha256)` 冲突，并清理无人引用的文件。
- **巡检误报**：回收站里的文件也算“有人认领”；孤儿计数不再截断。
- **悬空记录**：记录在、文件没了时重传会重建记录，而不是永远返回 duplicate。
- **粗筛**：改为 SQL 行值分块查询，参数顺序修正；键与上传 `taken_at` 同源。
- **上传竞态**：并发重复上传撞唯一约束时按 duplicate 返回；上传前检查配额与磁盘余量。
- **SQLite**：连接用完即关；新增 `sha256`、`(owner_id, deleted, taken_at DESC, id)`、
  `(size, filename)` 等索引；列表 `COUNT` 只在第一页算。
- **批量操作**：批量删除一条 SQL；清空/选择性清空回收站不再逐条 COUNT。
- **缩略图**：支持 `?size=`、原子写入、`private` 缓存 + ETag、启动时清理孤儿缓存。
- **鉴权**：PBKDF2 60 万次；密码至少 8 位；按 IP 登录限流；用户不存在也跑假哈希；
  退出登录只吊销当前会话（新增 `sessions` 表）；改密/禁用踢掉全部会话。
- **媒体鉴权**：网页用 HttpOnly cookie，外部播放器用 1 小时签名直链；
  token 不再出现在缩略图 URL。旧 APK 的 `?token=` 由
  `ALLOW_LEGACY_MEDIA_TOKEN=1`（默认）兼容，全部升级后建议设 0。
- **上传限制**：默认单文件 4GB、磁盘剩余 <512MB 拒绝、可选每用户配额。
- **文档**：`/docs`、`/openapi.json` 默认关闭（`ENABLE_DOCS=1` 打开）。

## 网页控制台

- HTML/JS 拆到 `server/app/static/`，不再和 Python 转义纠缠。
- 修好「禁用/启用」「重置密码」两个坏按钮（错误引号 + 未定义 `USERS`）。
- 用户名/显示名全部 HTML 转义；按钮改用 `data-*`，避免引号注入。
- 服务端搜索 + “加载更多”分页（不再只显示最近 300 张）。
- 文件夹筛选：全部 + 各 `rel_dir`（含「未分类」）。
- 回收站支持批量选择、批量恢复、批量永久删除（新增 `/api/trash/restore`）。
- 新增「关于」页：服务端版本、页面构建、当前账号、健康状态。
- 缩略图鉴权：`/api/auth/me` 会为老会话补发 HttpOnly cookie，修复缩略图 401。
- 视频用 `<video>` 播放，不再塞进 `<img>`。
- 下拉用户列表每次刷新前清空，不再重复堆积。

## Android

- EXIF 时区修复；`takenAt` 以 MediaStore `DATE_TAKEN` 为准，粗筛/上传同源。
- 覆盖还原：先完整落到缓存，失败可降级为副本；`IS_PENDING` 一定复位；
  新建媒体失败会删除空条目。
- 指纹计算失败不再算“跳过”，也不会推进同步水位。
- `querySince` 用 `QUERY_ARG_LIMIT` 限制单次读取量；上传任务仍分批续跑。
- Restore 改 3 路并发；系统播放器改用短期签名 URL。
- 单一共享 OkHttpClient；缩略图/原图走 `Authorization` 头，URL 不带 token，
  Coil 缓存键跨登录稳定。
- 设置页“保存”只合并本页字段，不再覆盖备份页的相册选择/登录态；
  改地址会清掉旧 token。退出登录会调用服务端吊销。
- token/密码用 Android Keystore AES-GCM 加密存 DataStore；`allowBackup=false`。
- 密码/相册相关入口要求已登录；部分照片权限（Android 14）可用。
- 通知结果使用独立 ID/渠道，不再互相覆盖；关键失败补了日志。
- 版本号统一到 `1.3.0`；支持可选 `release.keystore` 签名。

## 手机删除同步（新增）

- 新增 `photo_devices(photo_id, device_id, deleted)`：同一账号多设备时按设备记墓碑，
  A 手机删掉不会连带删掉 B 手机的备份。
- 手机端新增 `UploadManifest`（SQLite）：记录已备份的 `photo_id → 文件名|大小`。
  同步时本机清单里消失、且确实读不到的照片，调用 `POST /api/photos/forget`。
- NAS 端只软删除（进回收站）；其它设备仍可看到；从回收站恢复会清掉墓碑。
- 保护：仅完整相册权限执行；本机索引为空或缺失超过 80% 时整轮跳过并通知。
- 设置项「手机删除后，NAS 也移入回收站」默认关闭，需用户显式打开。
- 列表/一键还原带 `viewer_device`，这台设备已删除的照片不会再被还原回来。

## 部署 / CI

- Docker 非 root 运行（entrypoint 按需 chown），镜像加 HEALTHCHECK。
- 依赖锁版本（`pillow-heif==0.18.0`、httpx/piexif）。
- 新增 `Tests` 工作流：Python 编译 + 冒烟 + 回归 + 三套 e2e + Android 构建。
- 修复手动触发 Docker 工作流没有 tag 的问题；fpk 镜像标签与 compose 统一为 1.3.0。
- 删除仓库里过期且 `cmd/*` 权限为 0666 的 `photovault.fpk`。
- 备份脚本先写临时库并 `quick_check`，成功后才原子替换旧备份。

## 升级注意

1. 首次启动会自动补索引/列；照片文件不动。
2. **v1.3.0 之前由服务端解析 EXIF 写入的 `taken_at` 可能偏 +8 小时**（旧代码按 UTC）。
   新上传/重传会写对；历史数据如需批量修正，可按实际时区对
   `photos.meta_source='exif'` 的行做一次性 `UPDATE`（先备份 `index.db`）。
3. 旧 APK 缩略图依赖 `?token=`；确认所有手机升级后再设
   `ALLOW_LEGACY_MEDIA_TOKEN=0`。
4. 默认单文件上限从“不限”改为 4096MB；要传更大视频请显式调大 `MAX_UPLOAD_MB`。
5. 网页端若仍是旧缓存，强制刷新一次（`/` 本身已 `no-store`）。

## 仍然建议后续做（非阻塞）

- 完整 Paging 3 / 服务端分页游标；超大规模库的列表性能。
- 完整性巡检改后台任务 + 结果缓存（现在是同步请求，几十万文件会久）。
- Android 预哈希与上传合并为一次读盘（目前粗筛命中时已不读文件）。
- 迁移框架（alembic 类）替代手写 `ALTER`；Compose 文案 i18n；依赖注入。
- Release 开启 R8/资源压缩（需要真机回归）。

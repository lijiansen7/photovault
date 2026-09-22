# 飞牛 fnOS 应用包（.fpk）

把 PhotoVault 打包成飞牛应用中心可安装的 `.fpk`。

> 官方文档：https://developer.fnnas.com/docs/core-concepts/docker/
> 打包工具 fnpack 下载：https://developer.fnnas.com/ （当前 1.2.3）

---

## 包结构（本仓库 `fnos/photovault/`）

```text
fnos/photovault/
├── manifest                          # 应用标识 / 版本 / 端口 8765
├── LICENSE                           # fpk 必需
├── ICON.PNG                          # 64×64
├── ICON_256.PNG                      # 256×256
├── app/
│   ├── docker/docker-compose.yaml    # 引用 ghcr.io/lijiansen7/photovault
│   └── ui/
│       ├── config                    # 桌面入口（iframe 打开网页端）
│       └── images/icon_{64,256}.png
├── cmd/main                          # status 检查容器是否在跑
├── config/
│   ├── resource                      # 声明 docker-project
│   └── privilege                     # run-as package
└── wizard/                           # 卸载向导（可选，未实现）
```

## 前置：先把镜像发到 GHCR

飞牛是**按镜像名拉取**的，所以 `fnos/photovault/app/docker/docker-compose.yaml`
里写的是 `ghcr.io/lijiansen7/photovault:latest`，**不再使用本地 `build:`**。

镜像由 GitHub Actions 自动发布：

1. 把仓库推到 GitHub；
2. 打一个 tag 并推送：
   ```bash
   git tag v1.1.0 && git push origin v1.1.0
   ```
3. Actions 页面会跑 `Publish Docker image` 工作流，
   构建出 **amd64 + arm64 双架构**镜像推送到 GHCR
   （飞牛有 ARM 设备，双架构必须做）；
4. 首次发布后到 GitHub 仓库的 Packages 页面把镜像设为 Public
   （否则飞牛拉取会 401）。

## 打包 .fpk

在一台有 fnpack 的机器上：

```bash
cd fnos/photovault
fnpack build          # 生成 photovault.fpk
```

官方 fnpack 下载：https://developer.fnnas.com/docs/cli/fnpack/
（含 **Windows** 版本 `fnpack-1.2.3-windows-amd64`，下载后改名为 `fnpack.exe`）。

### ⚠️ Windows 打包的两个坑

**坑一：脚本会丢可执行权限。**
Windows 文件系统没有 exec 位，fnpack 会把 `cmd/*` 打成 `0666`，
装到飞牛上脚本跑不起来，表现为**应用一直显示"未运行"**。
打包后跑一次这个脚本修正：

```bash
python tools/fix_fpk_mode.py        # 把 fpk 里的 cmd/* 重打包成 0755
```

**坑二：脚本必须是 LF、且不能带 UTF-8 BOM。**
用 PowerShell 的 `Set-Content -Encoding utf8` 写脚本会带 **BOM + CRLF**，
bash 直接拒绝执行，飞牛安装时报「**执行脚本出错原因未知**」。
排查方法：看 `cmd/*` 文件头是不是 `23 21 2F 62 69 6E`（即 `#!/bin`），
出现 `EF BB BF` 开头或内容里有 `\r` 就是这个问题。
仓库已用 `.gitattributes` 把 `fnos/photovault/cmd/*` 固定为 LF。

### 打包检查项（fnpack 会校验）

`manifest`、`config/privilege`(合法 JSON)、`config/resource`(合法 JSON)、
`ICON.PNG`、`ICON_256.PNG`、`app/`、`cmd/`、`wizard/`、`app/{desktop_uidir}/`
必须存在。**`cmd/` 下还要有 9 个脚本**：
`main` + `install_init/callback`、`upgrade_init/callback`、
`uninstall_init/callback`、`config_init/callback`——少一个都会打包失败。

## 安装到飞牛

两种方式：

- **本地安装**：飞牛 SSH 里执行 `appcenter-cli install-local photovault.fpk`
- **应用中心上架**：按官方流程提交第三方应用

## 安装后的验证清单

- [ ] 应用能安装、能启动/停止
- [ ] 镜像能拉取（首次要下载 ~100MB）
- [ ] `cmd/main status` 正确返回运行状态
- [ ] 桌面图标能打开网页端（`http://飞牛IP:8765`）
- [ ] 首次登录 `admin / photovault`，**会强制改密码**
- [ ] 手机 App 能连上（填 `http://飞牛IP:8765`）

## 数据在哪

照片和索引存放在应用数据目录 `${TRIM_PKGVAR}/data` 下：

```text
data/
├── photos/       ← 原文件（命根子）
├── cache/thumb/  ← 缩略图缓存，可随时删
├── tmp/          ← 上传临时文件
└── index.db      ← SQLite 索引
```

想让照片出现在飞牛文件管理器里，可在 `config/resource` 里追加
`data-share` 声明（参考官方文档「应用资源」），把 `photos` 目录共享出来。

## 排错

| 现象 | 处理 |
|---|---|
| 镜像拉取 401 | GHCR 包还没设成 Public，或设备没登录 |
| 应用一直显示未运行 | `cmd/main` 里的 `CONTAINER_NAME` 要和 compose 的 `container_name` 一致 |
| 桌面图标打不开 | 核对 `manifest.service_port`、compose 端口映射、`app/ui/config` 的 `port` 三处一致 |
| ARM 设备起不来 | 镜像必须包含 arm64（工作流已构建双架构） |

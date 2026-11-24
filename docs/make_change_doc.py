from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

OUT = "docs/2026-11-22-修改总结.docx"
MD = "docs/2026-11-22-修改总结.md"

BLUE = RGBColor(0x1F, 0x4F, 0xD0)
DARK = RGBColor(0x19, 0x1C, 0x21)
GRAY = RGBColor(0x5D, 0x64, 0x6F)
LIGHT = "E9F0FF"


def set_east_asia(run_or_style, font="微软雅黑"):
    run_or_style.font.name = font
    rpr = run_or_style._element.get_or_add_rPr()
    rfonts = rpr.find(qn("w:rFonts"))
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.append(rfonts)
    rfonts.set(qn("w:eastAsia"), font)


def shade(cell, color):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:fill"), color)
    tcPr.append(shd)


def add_table(doc, headers, rows, widths=None):
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = table.rows[0].cells
    for i, text in enumerate(headers):
        hdr[i].text = ""
        p = hdr[i].paragraphs[0]
        r = p.add_run(text)
        r.bold = True
        r.font.size = Pt(9.5)
        r.font.color.rgb = BLUE
        shade(hdr[i], LIGHT)
    for row in rows:
        cells = table.add_row().cells
        for i, text in enumerate(row):
            cells[i].text = ""
            p = cells[i].paragraphs[0]
            r = p.add_run(str(text))
            r.font.size = Pt(9)
            r.font.color.rgb = DARK
    if widths:
        for row in table.rows:
            for i, w in enumerate(widths):
                row.cells[i].width = Cm(w)
    doc.add_paragraph()
    return table


def para(doc, text, bold=False, color=DARK, size=10.5, space_after=6):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.line_spacing = 1.25
    r = p.add_run(text)
    r.bold = bold
    r.font.size = Pt(size)
    r.font.color.rgb = color
    return p


def bullet(doc, text):
    p = doc.add_paragraph(style="List Bullet")
    p.paragraph_format.space_after = Pt(2)
    p.paragraph_format.line_spacing = 1.2
    r = p.add_run(text)
    r.font.size = Pt(10)
    r.font.color.rgb = DARK
    return p


def code(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(0.5)
    p.paragraph_format.space_after = Pt(4)
    r = p.add_run(text)
    r.font.name = "Consolas"
    r.font.size = Pt(9)
    r.font.color.rgb = BLUE
    return p


doc = Document()
section = doc.sections[0]
section.top_margin = Cm(2.0)
section.bottom_margin = Cm(1.8)
section.left_margin = Cm(2.2)
section.right_margin = Cm(2.2)

normal = doc.styles["Normal"]
normal.font.name = "微软雅黑"
normal.font.size = Pt(10.5)
set_east_asia(normal)

for name in ("Title", "Heading 1", "Heading 2", "Heading 3"):
    st = doc.styles[name]
    set_east_asia(st)
    st.font.color.rgb = BLUE if name != "Title" else DARK

# 封面
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_before = Pt(110)
r = p.add_run("PhotoVault 今日修改总结")
r.bold = True
r.font.size = Pt(28)
r.font.color.rgb = DARK

p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run("服务端 · Android App · 部署与验证记录")
r.font.size = Pt(13)
r.font.color.rgb = GRAY

p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_before = Pt(26)
r = p.add_run("2026-11-22")
r.font.size = Pt(12)
r.font.color.rgb = BLUE

doc.add_page_break()

# 1 概览
doc.add_heading("1. 今日概览", level=1)
para(doc,
     "今天围绕三条主线推进：网页控制台界面与回收站体验、Android App 视觉与设备身份/双地址能力、"
     "以及服务端直连 HTTPS 与设备 ID 管理。服务端与 App 版本统一到 1.4.0。"
     "外网 8765 在手机流量下仍不可达，已定位到 iKuai DMZ 的 WAN 绑定/端口映射问题，尚未解决。")

add_table(doc, ["方向", "结果"], [
    ["网页控制台", "整体视觉升级；回收站独立顶栏、批量恢复/永久删除；文件夹筛选；关于页教程与 HTTPS 开关；管理员设备 ID 列表"],
    ["Android App", "首页/底部导航/相册/回收站/设置统一视觉；内网优先、外网自动切换；还原进度与取消；回收站恢复；稳定设备 ID 与自定义 ID"],
    ["服务端", "直连 HTTPS（uvicorn TLS）；飞牛网关 TLS 桥接；局域网 HTTP 兼容口；设备 ID 迁移/改名接口；版本 1.4.0"],
    ["部署", "服务端已热更新并 commit；App 1.4.0 已安装；外网 8765 仍受 iKuai DMZ/端口限制"],
], widths=[3.2, 13.6])

# 2 版本与交付物
doc.add_heading("2. 版本与交付物", level=1)
add_table(doc, ["项目", "版本/路径"], [
    ["服务端版本", "1.4.0（/api/health 返回）"],
    ["Android App", "1.4.0+1122.1734，versionCode 26112217"],
    ["APK 路径", "android/app/build/outputs/apk/debug/app-debug.apk"],
    ["服务端补丁", "dist/photovault-server-app-1.4.0.tar.gz"],
    ["服务端镜像", "本地 commit 仍使用 ghcr.io/lijiansen7/photovault:1.3.0 标签，服务端自报 1.4.0"],
    ["离线 fpk", "未完成（今日中途停止打包，x86/arm64 包未生成）"],
], widths=[4.2, 12.6])
para(doc, "说明：App 与网页端显示的是 1.4.0；Docker 镜像标签沿用 1.3.0 是为了不打断当前运行的 compose 配置，"
          "后续正式发布时应统一改为 1.4.0。", color=GRAY, size=9)

# 3 服务端
doc.add_heading("3. 服务端修改", level=1)

doc.add_heading("3.1 网页控制台视觉", level=2)
for t in [
    "顶栏改为吸顶毛玻璃效果，标签栏改为圆角胶囊，新增「回收站」独立标签。",
    "页面背景增加品牌色径向渐变；卡片、按钮、输入框、登录框统一圆角、阴影和渐变主色。",
    "照片网格、文件夹筛选、加载更多在窄屏下自适应；移动端标签栏自动换行。",
    "登录页改为渐变卡片式布局。",
    "HTML/JS 加 no-store 和构建版本参数，升级后不再长期缓存旧页面。",
]:
    bullet(doc, t)

doc.add_heading("3.2 回收站与照片页", level=2)
for t in [
    "回收站从照片工具栏移到顶栏页签：照片 / 回收站 / 用户 / 维护 / 关于。",
    "回收站支持批量选择、恢复所选、永久删除所选；新增 /api/trash/restore 批量恢复接口。",
    "修复回收站缩略图和原图 404：媒体接口允许读取 deleted=1 的回收站照片（include_deleted=True）。",
    "照片页支持「全部 + 各文件夹」筛选；修复「未分类」筛不到 rel_dir 为空照片的问题。",
    "回收站列表按拍摄日期分组，缩略图仍可用，永久删除前有确认弹窗。",
]:
    bullet(doc, t)

doc.add_heading("3.3 关于页与教程", level=2)
for t in [
    "关于页展示服务端版本、页面构建时间、服务地址、当前账号、健康状态。",
    "新增四步使用教程（连接 NAS、选择相册、首次同步、删除同步）。",
    "新增手机端/网页端日常操作卡片和常见问题（缩略图、删除同步、去重、清空回收站、TLS 报错）。",
    "新增 HTTPS 证书卡片：换证书不用 SSH，飞牛文件管理覆盖 certs 后重启。",
    "新增管理员「启用/关闭 HTTPS」开关；切换后写入 data/certs/https.enabled 并自动重启服务。",
    "页面中的域名使用「你的域名」占位，不再写死用户实际域名。",
]:
    bullet(doc, t)

doc.add_heading("3.4 认证与缩略图", level=2)
for t in [
    "/api/auth/me 会为老网页会话补发 HttpOnly cookie，修复缩略图 401。",
    "缩略图/原图 URL 不再携带 token，登录后由 cookie 鉴权。",
    "静态资源与首页加 no-cache/no-store，console.js 带构建版本参数。",
    "回收站照片的缩略图和原图均可预览。",
]:
    bullet(doc, t)

doc.add_heading("3.5 直连 HTTPS 与端口", level=2)
para(doc,
     "没有使用 nginx/Caddy 反向代理。uvicorn 直接监听 8765 并在启动参数里加载 creak.eu.org 的 "
     "TrustAsia 证书；飞牛统一网关的 Unix Socket 由 socat 用 TLS 连回本机 8765。")
add_table(doc, ["项目", "当前值"], [
    ["证书/私钥", "/vol1/1000/PhotoVault/certs/creak.eu.org.crt / .key"],
    ["证书有效期", "2027-02-20（TrustAsia，creak.eu.org）"],
    ["容器主端口", "8765（HTTPS，证书直连）"],
    ["飞牛网关", "Unix Socket → socat TLS → 127.0.0.1:8765"],
    ["HTTPS 开关", "/data/certs/https.enabled（1 开 / 0 关）"],
    ["服务端入口脚本", "server/entrypoint.sh；当前容器 /srv/entrypoint.sh"],
], widths=[4.2, 12.6])
para(doc, "HTTPS 关闭后 8765 回到 HTTP；App 外网地址也要同步改成 http://，否则会报 TLS 错误。", color=GRAY, size=9)

doc.add_heading("3.6 设备 ID 管理", level=2)
for t in [
    "新增 POST /api/device/rename：把当前用户旧设备 ID 下的照片和 photo_devices 映射迁到新 ID。",
    "新增 GET /api/admin/devices：管理员按用户列出设备 ID、存活照片、回收站数量、最近上传时间。",
    "网页「用户」页新增设备 ID 表格，支持复制；用于卸载重装/自定义 ID 后找回旧 ID。",
    "设备 ID 迁移接口做过 round-trip 验证：b25c45ad466d → testtmp → b25c45ad466d，各迁移 2881 条。",
]:
    bullet(doc, t)

# 4 App
doc.add_heading("4. Android App 修改", level=1)

doc.add_heading("4.1 视觉与导航", level=2)
for t in [
    "底部导航改为悬浮圆角栏，选中态使用品牌色指示器。",
    "页面背景增加轻微品牌色渐变；首页增加「照片备份」标题。",
    "NAS 连接卡：渐变背景、圆形 WiFi 图标、状态圆点、延迟徽章、当前线路、服务端版本。",
    "存储概况改为照片/视频/占用三块小卡片，保留本机、NAS、位置、最后备份明细。",
    "NAS 相册增加大标题、统计、文件夹筛选与更清晰的筛选区。",
    "回收站增加大标题、总数、选择操作条；分页 120 张、缩略图 256px、关闭 crossfade。",
    "设置页增加统一标题；回收站/自动备份/高级/关于分区更清楚。",
]:
    bullet(doc, t)

doc.add_heading("4.2 内网/外网双地址", level=2)
for t in [
    "设置页由单地址改为「内网地址（优先）」+「外网地址（备用）」。",
    "EndpointRouter：优先内网；内网 /api/health 2 秒不通即切外网；60 秒内避免反复探测。",
    "API 请求网络失败时自动用外网重试一次；登录、后台备份、静默重登、相册、回收站统一走该逻辑。",
    "首页显示当前实际走的地址（内网 / 外网）和延迟。",
    "当前配置：内网 http://192.168.108.169:8767；外网 https://creak.eu.org:8765。",
]:
    bullet(doc, t)
para(doc, "双地址测试：WiFi 下内网 49ms；关闭 WiFi 后外网 63ms（服务端/线路正常时）。", color=GRAY, size=9)

doc.add_heading("4.3 一键还原进度与取消", level=2)
for t in [
    "RestoreWorker 通过 setProgress 上报 done/total/text；前台通知仍保留。",
    "所有还原请求打上 photovault_restore tag，NAS 相册页观察 WorkManager 状态。",
    "还原进行中显示卡片：进度条、当前文件、完成数/总数、取消按钮。",
    "取消调用 cancelAllWorkByTag，并取消还原通知；已写入相册的文件不撤回。",
    "一键还原全部、选中还原、单张还原都会显示该进度卡片。",
]:
    bullet(doc, t)

doc.add_heading("4.4 回收站恢复", level=2)
for t in [
    "App 回收站新增「恢复」按钮，调用 /api/trash/restore。",
    "恢复只把照片恢复到 NAS 相册，不会自动写回手机；需再去 NAS 相册点「还原选中」。",
    "恢复后提示「已恢复 N 张到 NAS 相册」。",
]:
    bullet(doc, t)

doc.add_heading("4.5 设备 ID", level=2)
for t in [
    "设备 ID 默认由 Settings.Secure.ANDROID_ID 的 SHA-256 前 6 字节派生，格式 android-xxxxxxxxxxxx。",
    "同一手机、同一签名、同一用户下，卸载重装/清数据不变；恢复出厂/换签名包会变。",
    "当前设备 ID：android-b25c45ad466d；NAS 已把旧 2881 条记录迁到该 ID。",
    "关于卡片显示版本、构建时间、设备 ID、当前是自动/自定义，并提供复制/修改。",
    "修改设备 ID 会先调用 /api/device/rename 迁移 NAS 记录，再写入本地；自定义后重装要填回同一 ID。",
]:
    bullet(doc, t)

# 5 部署状态
doc.add_heading("5. 部署与运行状态", level=1)
add_table(doc, ["入口", "地址", "状态"], [
    ["内网 HTTP", "http://192.168.108.169:8767", "正常（200）"],
    ["外网 HTTPS", "https://creak.eu.org:8765", "服务端正常；手机流量超时，DMZ/端口未通"],
    ["外网 HTTPS 备用", "https://creak.eu.org:8766 / :9443", "服务端正常；手机流量仍超时"],
    ["飞牛网关", "https://creak.eu.org:5667", "正常（需飞牛访问码）"],
    ["Web 控制台", "https://creak.eu.org:8765/（网关 /app/photovault）", "服务端已部署"],
], widths=[3.4, 8.6, 5.2])
para(doc, "容器端口：8765 HTTPS；8766 容器内 HTTP 兼容口；宿主机 8767→容器 8766；8766/9443→容器 8765。"
          "具体以 docker ps 为准。", color=GRAY, size=9)

doc.add_heading("5.1 数据库与备份", level=2)
for t in [
    "数据库：/data/index.db（NAS 数据卷 /vol1/1000/PhotoVault）。",
    "设备 ID 迁移前备份：/data/index.db.bak-device-20261122-172506 等。",
    "最初设备归属迁移备份：/data/index.db.bak-20261122-154318。",
    "入口脚本备份：/vol1/1000/PhotoVault/entrypoint.sh.bak。",
]:
    bullet(doc, t)

# 6 验证
doc.add_heading("6. 验证记录", level=1)
add_table(doc, ["验证项", "结果"], [
    ["服务端 /api/health", "200，version=1.4.0"],
    ["内网 8767 /api/health", "200"],
    ["回收站缩略图/原图", "200（修复前 404）"],
    ["设备 rename API", "2881 条 round-trip 成功"],
    ["管理员设备列表", "返回 android-b25c45ad466d：2348 存活 / 534 回收站 / 2882 总计"],
    ["App 双地址", "WiFi 内网 49ms；关 WiFi 外网 63ms（线路可用时）"],
    ["还原进度/取消", "进度卡片出现，取消后卡片消失"],
    ["App 构建", "BUILD SUCCESSFUL；1.4.0+1122.1734 已安装"],
    ["手机流量外网", "8765/8766/9443 超时；5667 可连"],
], widths=[6.0, 11.0])

# 7 问题
doc.add_heading("7. 已知问题与待办", level=1)
doc.add_heading("7.1 外网 8765 仍不可达（最高优先级）", level=2)
para(doc,
     "同一台手机、纯 5G 实测：5667 可连接，8765/8766/9443 全部超时。NAS 本机 8765 在监听，"
     "Docker DNAT 和 conntrack 均能看到外部连接；iKuai DMZ 绑定的是 wan2（专线），"
     "而 creak.eu.org 的 59.36.251.110 很可能属于另一条 WAN。当前判断：DMZ 绑错 WAN，"
     "或该 WAN 只做了 5667 的端口映射。")
for t in [
    "在 iKuai 确认 59.36.251.110 属于哪个 WAN。",
    "把 DMZ 外网地址改成该 WAN；或映射类型选「外网IP」并填 59.36.251.110，内网 192.168.108.169。",
    "如仍不通，在该 WAN 增加 TCP 8765 → 192.168.108.169:8765 的端口映射。",
    "不要长期把 8767（局域网 HTTP）暴露到公网。",
]:
    bullet(doc, t)

doc.add_heading("7.2 其他", level=2)
for t in [
    "x86/arm64 离线 fpk 未打包（用户中途停止）；manifest 版本已改为 1.4.0，镜像标签仍为 1.3.0。",
    "自定义设备 ID 后，卸载重装需手动填回同一 ID；自动 ID 无此负担。",
    "证书 2027-02-20 到期；续期后需替换 /vol1/1000/PhotoVault/certs 并重启容器。",
    "网页端「关于」不展示手机设备 ID；设备 ID 在 App「关于」和管理员「用户 → 设备 ID」表里。",
]:
    bullet(doc, t)

# 8 文件索引
doc.add_heading("8. 关键文件索引", level=1)
add_table(doc, ["文件/目录", "说明"], [
    ["server/app/main.py", "服务端路由、HTTPS 开关、设备 rename、设备列表、VERSION=1.4.0"],
    ["server/app/db.py", "设备迁移、批量恢复、设备汇总、回收站/媒体逻辑"],
    ["server/app/static/index.html / console.js", "网页控制台 UI、回收站、关于、设备表、HTTPS 开关"],
    ["server/entrypoint.sh", "TLS 开关、飞牛网关 TLS、局域网 HTTP 桥接"],
    ["android/.../data/EndpointRouter.kt", "内网优先/外网兜底"],
    ["android/.../data/SettingsRepo.kt", "双地址、稳定设备 ID、自定义 ID"],
    ["android/.../ui/settings/SettingsScreen.kt", "设置/关于/设备 ID/双地址"],
    ["android/.../ui/browse/BrowseScreen.kt", "还原进度卡片、相册 UI"],
    ["android/.../work/RestoreWorker.kt", "还原进度上报与取消"],
    ["fnos/photovault/manifest", "版本已改为 1.4.0"],
    ["dist/photovault-server-app-1.4.0.tar.gz", "当前服务端补丁"],
], widths=[7.2, 9.8])

# 9 回滚
doc.add_heading("9. 恢复与回滚要点", level=1)
for t in [
    "误删照片：先不要清空 NAS 回收站；App 设置→回收站→恢复，再到 NAS 相册→还原选中。",
    "数据库异常：停止容器，用 /data/index.db.bak-* 恢复 index.db，再启动。",
    "HTTPS 切换异常：把 /data/certs/https.enabled 改回 1 或 0，重启容器；证书文件在 /vol1/1000/PhotoVault/certs。",
    "入口脚本异常：用 /vol1/1000/PhotoVault/entrypoint.sh.bak 覆盖 /srv/entrypoint.sh 后重启。",
    "卸载 App 前：先备份 device_id / preferences，或确认已改用稳定 Android ID。",
]:
    bullet(doc, t)

# footer
footer = section.footer
fp = footer.paragraphs[0]
fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = fp.add_run("PhotoVault 修改总结 · 2026-11-22 · 服务端/App 1.4.0")
r.font.size = Pt(8)
r.font.color.rgb = GRAY

doc.save(OUT)
print(OUT)

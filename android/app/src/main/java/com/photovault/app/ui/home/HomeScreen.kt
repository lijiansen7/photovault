package com.photovault.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import com.photovault.app.data.AlbumInfo
import com.photovault.app.data.NasConfig
import com.photovault.app.data.NasStats
import com.photovault.app.util.photoDesc
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: HomeViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenBrowse: () -> Unit = {},
) {
    val cfg by vm.config.collectAsState()
    val stats by vm.stats.collectAsState()
    val local by vm.localCount.collectAsState()
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()
    val errDetail by vm.errorDetail.collectAsState()
    val recent by vm.recent.collectAsState()
    val failures by vm.failures.collectAsState()
    val work by vm.workInfo.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val lastResult by vm.lastSyncResult.collectAsState()
    val progress by vm.syncProgress.collectAsState()
    val latency by vm.latencyMs.collectAsState()
    val serverVersion by vm.serverVersion.collectAsState()
    val activeUrl = vm.activeUrl
    val activeIsLan = vm.activeIsLan

    var showAlbumPicker by remember { mutableStateOf(false) }

    // 地址或登录态一变就重新拉一次：以前只靠 ViewModel 初始化，
    // 在设置页填完地址登录回来，首页还挂着上一次的「还没配置」。
    LaunchedEffect(cfg.lanUrl, cfg.wanUrl, cfg.authToken) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部多留一截：页面是滚动的，悬浮导航会把内容切在半行上
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
            Text(
                "照片备份",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "原图直存 NAS · EXIF、拍摄时间与位置完整保留",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!cfg.configured) {
            OnboardingCard(onOpenSettings = onOpenSettings)
        } else {
            ConnectionCard(
                cfg = cfg,
                err = err,
                errDetail = errDetail,
                loading = loading,
                latencyMs = latency,
                serverVersion = serverVersion,
                activeUrl = activeUrl,
                activeIsLan = activeIsLan,
                stats = stats,
                localCount = local,
                recent = recent,
                onRefresh = { vm.refresh() },
                onOpenBrowse = onOpenBrowse,
            )
        }

        BackupCard(
            cfg = cfg,
            running = syncing,
            progress = progress,
            lastResult = lastResult,
            onToggle = { vm.setBackupEnabled(it) },
            onSyncNow = { vm.syncNow() },
            onCancel = { vm.cancelSync() },
            onPickAlbums = {
                vm.loadAlbums(force = true)
                showAlbumPicker = true
            },
            albumHint = albumSummary(cfg),
            onOpenSettings = onOpenSettings,
            failures = failures,
            onRetryOne = vm::retryOne,
            onRetryAll = vm::retryAllFailures,
            onClearFailures = vm::clearFailures,
        )
    }

    if (showAlbumPicker) {
        AlbumPickerDialog(vm, cfg) { showAlbumPicker = false }
    }
}

/** 相册选择的摘要文案 */
private fun albumSummary(cfg: NasConfig): String {
    val picked = cfg.selectedAlbums ?: return "全部相册"
    if (picked.isEmpty()) return "一个都没选（不会备份任何照片）"
    return "已选 ${picked.size} 个相册"
}

/**
 * 首次使用引导。
 *
 * 以前没配置地址时首页只有一张写着「未配置」的卡，用户不知道该去哪。
 * 现在直接把三步摆出来，一个按钮跳到设置页。
 */
@Composable
private fun OnboardingCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "先连上 NAS，再开始备份",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            listOf(
                "1" to ("填地址" to "内网填 NAS 的 IP:8766，外网填你的域名:8765"),
                "2" to ("登录" to "用网页端那组账号密码"),
                "3" to ("打开自动备份" to "之后新照片会自动传上去"),
            ).forEach { (n, pair) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            n,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pair.first, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            pair.second,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("去设置")
            }
        }
    }
}

@Composable
private fun BackupCard(
    cfg: NasConfig,
    running: Boolean,
    progress: SyncProgress?,
    lastResult: String?,
    onToggle: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onCancel: () -> Unit,
    onPickAlbums: () -> Unit,
    albumHint: String,
    onOpenSettings: () -> Unit,
    failures: List<com.photovault.app.data.UploadFailure>,
    onRetryOne: (com.photovault.app.data.UploadFailure) -> Unit,
    onRetryAll: () -> Unit,
    onClearFailures: () -> Unit,
) {
    var showFailures by remember { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动备份", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        if (cfg.backupEnabled) "每 ${cfg.periodMinutes} 分钟检查一次${if (cfg.wifiOnly) "，仅 WiFi" else ""}"
                        else "已关闭 —— 不会自动上传任何照片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = cfg.backupEnabled,
                    onCheckedChange = onToggle,
                    enabled = cfg.configured && cfg.loggedIn,
                )
            }

            if (!cfg.loggedIn) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "还没登录，自动备份打不开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("去登录") }
                }
            }

            HorizontalDivider()

            Text("要备份的相册：$albumHint", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onPickAlbums, enabled = cfg.configured && cfg.loggedIn,
                modifier = Modifier.fillMaxWidth()) {
                Text("选择要备份的相册")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSyncNow,
                    enabled = cfg.configured && cfg.loggedIn && !running,
                    modifier = Modifier.weight(1f),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("正在同步…", Modifier.padding(start = 8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.padding(end = 8.dp))
                        Text("立即同步")
                    }
                }
                // 只在跑的时候出现：点了就停掉还没传的，已经传成功的不受影响
                if (running) {
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
            }

            if (!cfg.syncDeletions && cfg.loggedIn) {
                Text(
                    "提示：手机里删除的照片目前不会同步到 NAS，「一键还原」可能把它们写回来。" +
                        "可在「设置 → 自动备份」里打开删除同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (running) {
                val p = progress
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (p != null && p.total > 0) {
                        // 有总数：真进度条 + N/M
                        LinearProgressIndicator(
                            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Text(
                            (p.text ?: "正在同步") + "  ${p.done}/${p.total}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        // 还在扫描/分析本机相册，总数未知：转圈 + worker 的阶段文案
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(p?.text ?: "正在扫描本机相册…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (lastResult != null) {
                Text(lastResult, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            } else if (!cfg.backupEnabled && cfg.configured) {
                Text(
                    "打开上面的开关后才会按周期自动备份；「立即同步」随时可用，不受开关影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 失败明细：只说「失败 N 张」用户没法处理，这里给到文件名和原因
            if (failures.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "有 ${failures.size} 张没传上去",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showFailures = true }) { Text("查看") }
                }
            }
        }
    }

    if (showFailures) {
        FailureDialog(
            failures = failures,
            onRetryOne = onRetryOne,
            onRetryAll = onRetryAll,
            onClear = onClearFailures,
            onDismiss = { showFailures = false },
        )
    }
}

/** 失败明细弹窗：一张一行，能单张重试，也能全部重试。 */
@Composable
private fun FailureDialog(
    failures: List<com.photovault.app.data.UploadFailure>,
    onRetryOne: (com.photovault.app.data.UploadFailure) -> Unit,
    onRetryAll: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("没传上去的照片") },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                failures.forEach { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                f.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onRetryOne(f) }) { Text("重试") }
                        }
                        Text(
                            f.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onRetryAll) { Text("全部重试") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onClear(); onDismiss() }) { Text("清空") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/** 让用户自己勾选要备份哪些相册（DCIM / Pictures / 微信 …）。 */
@Composable
private fun AlbumPickerDialog(vm: HomeViewModel, cfg: NasConfig, onClose: () -> Unit) {
    val albums by vm.albums.collectAsState()
    val loading by vm.albumsLoading.collectAsState()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("选择要备份的相册") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                when {
                    loading && albums.isEmpty() -> Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }

                    albums.isEmpty() -> Text(
                        "没读到相册。\n请确认已授予「照片」权限，然后重开这个页面。",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    else -> {
                        Text(
                            "只备份勾选的相册。默认全选。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(Modifier.padding(top = 6.dp)) {
                            items(albums, key = { it.name }) { a ->
                                AlbumRow(a, cfg) { on -> vm.setAlbumSelected(a.name, on) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.setAllAlbums(true) }) { Text("全选") }
                TextButton(onClick = { vm.setAllAlbums(false) }) { Text("全不选") }
            }
        },
    )
}

@Composable
private fun AlbumRow(a: AlbumInfo, cfg: NasConfig, onToggle: (Boolean) -> Unit) {
    // selectedAlbums 为 null 表示还没挑过 → 视觉上全部勾上
    val checked = cfg.selectedAlbums?.contains(a.name) ?: true
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                a.name + if (a.isCameraLike) "（相机）" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append("${a.images} 张照片")
                    if (a.videos > 0) append(" · ${a.videos} 个视频")
                    a.relDir?.let { append("  ·  $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    cfg: NasConfig,
    err: String?,
    errDetail: String?,
    loading: Boolean,
    latencyMs: Int?,
    serverVersion: String?,
    activeUrl: String,
    activeIsLan: Boolean,
    stats: NasStats?,
    localCount: Int,
    recent: List<com.photovault.app.data.PhotoDto>,
    onRefresh: () -> Unit,
    onOpenBrowse: () -> Unit,
) {
    var showErrDetail by remember { mutableStateOf(false) }
    val hasError = !err.isNullOrBlank()
    val statusColor = when {
        !cfg.configured -> MaterialTheme.colorScheme.outline
        // 连接中用中性色：绿色留给「连上了」
        loading -> MaterialTheme.colorScheme.primary
        hasError -> MaterialTheme.colorScheme.error
        latencyMs != null -> Color(0xFF1B9E75)
        else -> MaterialTheme.colorScheme.outline
    }
    val statusText = when {
        !cfg.configured -> "未配置"
        loading -> "正在连接…"
        hasError -> "连接异常"
        cfg.loggedIn -> "已连接 · ${cfg.who}"
        else -> "地址已填，尚未登录"
    }
    // 渐变收在 surfaceContainerLow 而不是纯白：否则卡片下缘和页面背景同色，边界直接消失
    val gradient = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.surfaceContainerLow,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(Brush.linearGradient(gradient))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Wifi, null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "NAS 连接",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "重新检测")
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LatencyPill(latencyMs, loading, hasError)
                Text(
                    buildString {
                        if (cfg.hasLan && cfg.hasWan) {
                            append(if (activeIsLan) "内网 · " else "外网 · ")
                        }
                        append(activeUrl.ifBlank { cfg.primaryUrl }.ifBlank { "未填写地址" })
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }

            if (serverVersion != null && cfg.loggedIn) {
                Text(
                    "服务端 v$serverVersion · ${cfg.who}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasError) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        err!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!errDetail.isNullOrBlank()) {
                        Text(
                            if (showErrDetail) "收起" else "看看原因",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showErrDetail = !showErrDetail },
                        )
                        if (showErrDetail) {
                            Text(
                                errDetail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------- 存储概况：与连接信息合并成同一张卡 ----------
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "存储概况",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                ) {
                    Text(
                        "按内容去重",
                        Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            if (stats == null) {
                // 没数据时不摆一排「—」：那看起来像坏了，不如直接说清楚为什么没有
                Text(
                    when {
                        loading -> "正在读取 NAS 统计…"
                        !cfg.loggedIn -> "登录后会显示照片 / 视频数量、占用和最后备份时间"
                        else -> "暂时取不到统计，点右上角刷新试试"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniStat(
                        icon = Icons.Default.PhotoLibrary,
                        label = "照片",
                        value = (stats!!.count - stats!!.videos).toString(),
                        modifier = Modifier.weight(1f),
                    )
                    MiniStat(
                        icon = Icons.Default.Videocam,
                        label = "视频",
                        value = stats!!.videos.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    MiniStat(
                        icon = Icons.Default.Storage,
                        label = "占用",
                        value = humanSize(stats!!.bytes),
                        modifier = Modifier.weight(1.2f),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow("本机相册文件", localCount.toString())
                    StatRow("NAS 已备份", stats!!.count.toString())
                    StatRow("带位置信息", stats!!.withGps.toString())
                    StatRow("最后备份", stats!!.lastUploadAt?.let { fmtTime(it) } ?: "还没备份过")
                }

                Text(
                    "NAS 按内容去重：同一张照片的多个副本只存一份，"
                        + "所以「已备份」可能少于「本机相册文件」—— 少的是重复副本，不是漏备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (recent.isNotEmpty()) {
                RecentStrip(cfg, recent, onOpenBrowse)
            }
        }
    }
}

/** 首页的「最近备份」横向缩略图：打开 App 先看到照片，而不是只有数字。 */
@Composable
private fun RecentStrip(
    cfg: NasConfig,
    list: List<com.photovault.app.data.PhotoDto>,
    onOpenBrowse: () -> Unit,
) {
    val api = remember(cfg.lanUrl, cfg.wanUrl, cfg.authToken) { com.photovault.app.data.NasApi(cfg) }
    val headers = remember(cfg.authToken) {
        okhttp3.Headers.headersOf("Authorization", "Bearer ${cfg.authToken}")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近备份",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "查看全部",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onOpenBrowse),
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(list.size) { i ->
                val p = list[i]
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(onClick = onOpenBrowse)
                            .semantics(mergeDescendants = true) {
                                contentDescription = photoDesc(p) + "，打开相册"
                            },
                    ) {
                        coil.compose.AsyncImage(
                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                .data(api.thumbUrl(p.id, 200))
                                .headers(headers)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyPill(ms: Int?, loading: Boolean, hasError: Boolean) {
    val text = when {
        loading && ms == null -> "…"
        ms != null -> "延迟 $ms ms"
        hasError -> "连接异常"
        else -> "未检测"
    }
    val color = when {
        ms != null && ms < 80 -> Color(0xFF1B9E75)
        ms != null && ms < 250 -> Color(0xFFB7791F)
        ms != null -> MaterialTheme.colorScheme.error
        hasError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.12f), shape = CircleShape) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Speed, null, Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(5.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun MiniStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun humanSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

private fun fmtTime(ms: Long) =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

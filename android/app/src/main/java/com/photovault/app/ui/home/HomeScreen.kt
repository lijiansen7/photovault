package com.photovault.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import com.photovault.app.data.AlbumInfo
import com.photovault.app.data.NasConfig
import com.photovault.app.data.NasStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val cfg by vm.config.collectAsState()
    val stats by vm.stats.collectAsState()
    val local by vm.localCount.collectAsState()
    val loading by vm.loading.collectAsState()
    val err by vm.error.collectAsState()
    val work by vm.workInfo.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val lastResult by vm.lastSyncResult.collectAsState()
    val progress by vm.syncProgress.collectAsState()

    var showAlbumPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ConnectionCard(cfg, err, loading, onRefresh = { vm.refresh() })

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
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("存储概况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatRow("本机待备份文件", local.toString())
                StatRow("NAS 已备份", stats?.count?.toString() ?: "—")
                StatRow("其中照片", stats?.let { (it.count - it.videos).toString() } ?: "—")
                StatRow("其中视频", stats?.videos?.toString() ?: "—")
                StatRow("带位置信息", stats?.withGps?.toString() ?: "—")
                StatRow("占用空间", stats?.bytes?.let(::humanSize) ?: "—")
                StatRow("最后备份", stats?.lastUploadAt?.let { fmtTime(it) } ?: "—")
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Text(
                    "NAS 按内容去重：同一张照片的多个副本只存一份，"
                        + "所以「已备份」可能少于「本机待备份文件」—— 少的是重复副本，不是漏备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
) {
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
                    enabled = cfg.configured,
                )
            }

            HorizontalDivider()

            Text("要备份的相册：$albumHint", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onPickAlbums, enabled = cfg.configured,
                modifier = Modifier.fillMaxWidth()) {
                Text("选择要备份的相册")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSyncNow,
                    enabled = cfg.configured && !running,
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
        }
    }
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
private fun ConnectionCard(cfg: NasConfig, err: String?, loading: Boolean, onRefresh: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (cfg.configured) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("NAS 连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else Icon(Icons.Default.Refresh, "刷新")
                }
            }
            Text(
                when {
                    !cfg.configured -> "未配置，去「设置」里填 NAS 地址"
                    cfg.loggedIn -> "已连接 · 已登录：${cfg.who}"
                    else -> "地址已填，但还没登录"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (loading) Text("正在查询…", style = MaterialTheme.typography.bodySmall)
            else if (!err.isNullOrBlank()) Text(err, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
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

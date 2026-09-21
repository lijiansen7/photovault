package com.photovault.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.photovault.app.data.PhotoDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BrowseScreen(vm: BrowseViewModel = viewModel()) {
    val items = vm.items
    val loading = vm.loading
    val error = vm.error
    val selectMode = vm.selectMode
    val selection = vm.selection
    val cfg by vm.config.collectAsState()

    LaunchedEffect(cfg.baseUrl, cfg.authToken) { if (cfg.configured) vm.refresh() }

    var confirmAll = remember { androidx.compose.runtime.mutableStateOf(false) }
    var confirmDelete = remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("all" to "全部", "image" to "照片", "video" to "视频").forEach { (k, label) ->
                FilterChip(selected = vm.filter == k, onClick = { vm.changeFilter(k) }, label = { Text(label) })
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { vm.refresh() },
                enabled = !loading,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(Icons.Default.Refresh, "刷新")
                }
            }
            if (selectMode) {
                IconButton(onClick = { vm.toggleSelectAll() }) {
                    Icon(Icons.Default.SelectAll, "全选")
                }
            } else {
                TextButton(onClick = { vm.selectMode = true }) { Text("选择") }
            }
        }

        if (vm.folders.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = vm.folder == null,
                        onClick = { vm.changeFolder(null) },
                        label = { Text("全部照片") },
                    )
                }
                items(vm.folders) { (name, count) ->
                    FilterChip(
                        selected = vm.folder == name,
                        onClick = { vm.changeFolder(name) },
                        label = { Text("$name（$count）") },
                    )
                }
            }
        }

        if (selectMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("已选 ${selection.size} / ${items.size}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.exitSelect() }) { Text("取消") }
                OutlinedButton(
                    onClick = { confirmDelete.value = true },
                    enabled = selection.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Text("删除", Modifier.padding(start = 6.dp))
                }
                Button(onClick = { vm.restoreSelected() }, enabled = selection.isNotEmpty()) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Text("还原选中", Modifier.padding(start = 6.dp))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("NAS 共 ${vm.total} 项", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirmAll.value = true }) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Text("一键还原全部", Modifier.padding(start = 6.dp))
                }
            }
        }

        vm.stats?.let { s ->
            Text(
                "${s.count - s.videos} 张照片 · ${s.videos} 个视频 · 共 ${s.count} 项 · ${humanSize(s.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        when {
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { vm.refresh() }) { Text("重试") }
                }
            }
            items.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp),
                    )
                    Text("NAS 上还没有照片", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "去「备份」页打开自动备份，照片就会同步上来",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val groups = remember(items) { groupByDay(items) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    groups.forEachIndexed { gi, (label, list) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "day-$label-$gi") {
                            DayHeader(label, list.size)
                        }
                        itemsIndexed(list, key = { _, p -> p.id }) { idx, p ->
                            // 只在最后一组快到底时才翻页
                            if (gi == groups.lastIndex && idx >= list.size - 10) vm.loadMore()
                            PhotoCell(
                                photo = p,
                                url = vm.thumbUrl(p.id),
                                selected = p.id in selection,
                                selectMode = selectMode,
                                onClick = { if (selectMode) vm.toggleSelect(p.id) else vm.open(p) },
                                onLongClick = { vm.selectMode = true; vm.toggleSelect(p.id) },
                            )
                        }
                    }
                }
            }
        }

        if (loading && items.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }

    vm.viewer?.let { FullScreenViewer(it, vm) }
    vm.detail?.let { DetailDialog(it, vm) }

    if (confirmAll.value) {
        AlertDialog(
            onDismissRequest = { confirmAll.value = false },
            title = { Text("一键还原全部") },
            text = {
                Text(
                    "将把 NAS 上的 ${vm.total} 个文件写回本机相册，尽量回到备份前的原相册目录。\n"
                        + "拍摄时间与 GPS 会完整保留。\n\n"
                        + "手机里已有的照片："
                        + when (cfg.restoreConflict) {
                            "duplicate" -> "会再存一份副本"
                            "overwrite" -> "尝试覆盖（改不了的就再存一份）"
                            else -> "自动跳过"
                        }
                        + "。\n可在「设置」里改。"
                )
            },
            confirmButton = {
                Button(onClick = { vm.restoreAll(); confirmAll.value = false }) { Text("开始还原") }
            },
            dismissButton = { TextButton(onClick = { confirmAll.value = false }) { Text("取消") } },
        )
    }

    if (confirmDelete.value) {
        AlertDialog(
            onDismissRequest = { confirmDelete.value = false },
            title = { Text("删除选中的 ${vm.selection.size} 张？") },
            text = {
                Text(
                    "会先移进 NAS 的回收站，之后还能恢复；"
                        + "只有到「设置 → 回收站」里清空才会真正删掉文件。"
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.deleteSelected(); confirmDelete.value = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete.value = false }) { Text("取消") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: PhotoDto,
    url: String,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = url,
            contentDescription = photo.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 底部压一层渐变，白字才看得清
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                    )
                )
        )

        photo.takenAt?.let {
            Text(
                // 日期已经在分组标题上，格子上只留时间
                fmtTime(it),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 5.dp),
            )
        }

        // 播放角标：视频缩略图由手机上传时抽帧提供；老视频可能还没抽过，
        // 那种情况 /thumb 会 404，此时显示的就是下面这层灰底 + 角标，不会是空白格。
        if (photo.isVideo) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, "视频", tint = Color.White,
                    modifier = Modifier.size(22.dp))
            }
        }

        if (photo.hasGps) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Place, "含位置", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }

        if (selectMode) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape),
            ) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
        }

        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary, shape)
            )
        }
    }
}

/** 点缩略图进来的全屏查看：看原图、看时间地点、直接还原。 */
@Composable
private fun FullScreenViewer(p: PhotoDto, vm: BrowseViewModel) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { vm.viewer = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = vm.fileUrl(p.id),      // 看的是原图，不是缩略图
                contentDescription = p.filename,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Text(
                    p.filename,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(p.takenAt?.let { fmtFull(it) } ?: "无拍摄时间")
                        if (p.hasGps) append("  含位置")
                        append("  ").append(humanSize(p.size))
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.viewer = null }) { Text("关闭", color = Color.White) }
                TextButton(onClick = { vm.detail = p; vm.viewer = null }) {
                    Text("详情", color = Color.White)
                }
                Button(onClick = { vm.restoreOne(p); vm.viewer = null }) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Text("还原到本机", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailDialog(p: PhotoDto, vm: BrowseViewModel) {
    AlertDialog(
        onDismissRequest = { vm.detail = null },
        title = { Text(p.filename, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("拍摄时间：${p.takenAt?.let { fmtFull(it) } ?: "未知"}（来源：${p.metaSource ?: "-"}）")
                Text("位置：${if (p.hasGps) "%.5f, %.5f".format(p.gpsLat, p.gpsLon) else "无"}")
                Text("相机：${listOfNotNull(p.cameraMake, p.cameraModel).joinToString(" ").ifBlank { "未知" }}")
                Text("尺寸：${p.width ?: "?"} × ${p.height ?: "?"}   大小：${humanSize(p.size)}")
                Text("相册：${p.album ?: "-"}   设备：${p.deviceId ?: "-"}")
            }
        },
        confirmButton = {
            Button(onClick = { vm.restoreOne(p); vm.detail = null }) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Text("还原到本机", Modifier.padding(start = 6.dp))
            }
        },
        dismissButton = { TextButton(onClick = { vm.detail = null }) { Text("关闭") } },
    )
}

/** 日期分组条，横跨整行。 */
@Composable
private fun DayHeader(label: String, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
        Text(
            "$count 张",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

/** 按拍摄日期（没有就按上传时间）分组，保持原有的时间倒序。 */
private fun groupByDay(items: List<PhotoDto>): List<Pair<String, List<PhotoDto>>> {
    val map = LinkedHashMap<String, MutableList<PhotoDto>>()
    items.forEach { p ->
        val key = dayKeyFmt.format(Date(p.takenAt ?: p.uploadedAt))
        map.getOrPut(key) { mutableListOf() }.add(p)
    }
    return map.map { (k, v) -> dayLabel(k) to v }
}

private fun dayLabel(key: String): String {
    val today = dayKeyFmt.format(Date())
    if (key == today) return "今天"
    if (key == dayKeyFmt.format(Date(System.currentTimeMillis() - 86_400_000L))) return "昨天"
    return try {
        val d = dayKeyFmt.parse(key)
        if (d != null) SimpleDateFormat("yyyy年M月d日 EEEE", Locale.getDefault()).format(d) else key
    } catch (e: Exception) {
        key
    }
}

private fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
private fun fmtFull(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun humanSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

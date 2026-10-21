package com.photovault.app.ui.browse

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.photovault.app.data.PhotoDto
import java.text.SimpleDateFormat
import java.util.Calendar
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
    // 网格列数：双指放大/缩小就是改它（照片变大 = 列数变少）
    var columns by rememberSaveable { mutableStateOf(3) }

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
                val groups = remember(items) { groupTimeline(items) }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    // 双指捏合改列数：往外撑放大照片（列数-1），往里收缩小照片（列数+1）。
                    // 只在**两根手指**时接管事件 —— 用 detectTransformGestures 的话
                    // 单指拖动也会被当成平移吃掉，整个列表就滑不动了。
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            var accum = 1f
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        val zoom = event.calculateZoom()
                                        if (zoom != 1f) {
                                            event.changes.forEach { it.consume() }
                                            accum *= zoom
                                            val next = when {
                                                accum >= 1.4f && columns > 2 -> columns - 1
                                                accum <= 0.72f && columns < 8 -> columns + 1
                                                else -> columns
                                            }
                                            if (next != columns) {
                                                columns = next
                                                accum = 1f
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                ) {
                    groups.forEachIndexed { gi, g ->
                        // 年 / 月 / 日三级：只在变化时插一行，不然每天顶一个"2026年10月"
                        if (g.showYear) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "y-${g.year}") {
                                PeriodHeader("${g.year}年", big = true)
                            }
                        }
                        if (g.showMonth) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "m-${g.year}-${g.month}") {
                                PeriodHeader("${g.month}月", big = false)
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "d-${g.key}") {
                            DayHeader(g.dayLabel, g.items.size)
                        }
                        itemsIndexed(g.items, key = { _, p -> p.id }) { idx, p ->
                            // 只在最后一组快到底时才翻页
                            if (gi == groups.lastIndex && idx >= g.items.size - 10) vm.loadMore()
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
    vm.player?.let { p ->
        VideoPlayerDialog(
            p = p,
            url = vm.streamUrl(p.id),
            onClose = { vm.player = null },
            onOpenExternal = { vm.player = null; vm.openExternally(p) },
        )
    }
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

/** 点缩略图进来的全屏查看：看原图（可双指缩放/双击放大）、看时间地点、直接还原。 */
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
            ZoomableImage(url = vm.fileUrl(p.id), desc = p.filename)

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

/**
 * 可缩放的原图：双指捏合 1x~6x，双击在 1x / 2.5x 之间切换，放大后可拖动。
 * 缩到 1x 时偏移归零并夹住边界，避免把照片拖到屏幕外面找不回来。
 */
@Composable
private fun ZoomableImage(url: String, desc: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(s: Float, o: Offset): Offset {
        if (s <= 1.01f) return Offset.Zero
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(1f, 6f)
        val ratio = if (scale <= 0f) 1f else next / scale
        scale = next
        offset = clamp(next, offset * ratio + panChange)
    }

    AsyncImage(
        model = url,
        contentDescription = desc,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2.5f; offset = Offset.Zero
                        }
                    },
                )
            }
            .transformable(transformState)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** App 内播放视频：ExoPlayer + 系统风格控制条，可拖动进度、暂停、全屏。 */
@Composable
private fun VideoPlayerDialog(
    p: PhotoDto,
    url: String,
    onClose: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val ctx = LocalContext.current
    var playError by remember(p.id) { mutableStateOf<String?>(null) }

    val exo = remember(p.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(p.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 编码太偏门（如某些 HEVC / mkv 音轨）内置解不了，给出兜底入口
                playError = error.errorCodeName
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.stop()
            exo.release()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        player = exo
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        keepScreenOn = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "关闭", tint = Color.White)
                }
                Column {
                    Text(
                        p.filename,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(p.takenAt?.let { fmtFull(it) } ?: "无拍摄时间")
                            append("  ").append(humanSize(p.size))
                        },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (playError != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        "这台手机的内置播放器解不了这个视频（$playError）",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onOpenExternal) {
                            Text("用其他应用打开", color = Color.White)
                        }
                        TextButton(onClick = {
                            playError = null
                            exo.prepare()
                            exo.play()
                        }) { Text("重试", color = Color.White) }
                    }
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

/** 时间轴分组条目：年 / 月 / 日三级。年月只在变化时插一行。 */
private data class TimelineGroup(
    val key: String,
    val year: Int,
    val month: Int,
    val dayLabel: String,
    val showYear: Boolean,
    val showMonth: Boolean,
    val items: List<PhotoDto>,
)

private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

/**
 * 按「拍摄时间，没有就用上传时间」倒序分组。
 *
 * 这里必须本地再排一次：服务端分页拿回来的顺序里，拍摄时间为空的条目会被 NULL
 * 顶到末尾，前端不兜的话时间轴上会出现"今天"夹在几年前的中间。
 */
private fun groupTimeline(items: List<PhotoDto>): List<TimelineGroup> {
    val sorted = items.sortedByDescending { it.takenAt ?: it.uploadedAt }
    val cal = Calendar.getInstance()
    val map = LinkedHashMap<String, MutableList<PhotoDto>>()
    val meta = HashMap<String, Triple<Int, Int, Int>>()
    sorted.forEach { p ->
        cal.timeInMillis = p.takenAt ?: p.uploadedAt
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val key = "%04d-%02d-%02d".format(y, m, d)
        if (!map.containsKey(key)) meta[key] = Triple(y, m, d)
        map.getOrPut(key) { mutableListOf() }.add(p)
    }
    var lastYear = Int.MIN_VALUE
    var lastMonth = Int.MIN_VALUE
    return map.map { (key, list) ->
        val md = meta.getValue(key)
        val showYear = md.first != lastYear
        val showMonth = showYear || md.second != lastMonth
        lastYear = md.first
        lastMonth = md.second
        TimelineGroup(
            key = key,
            year = md.first,
            month = md.second,
            dayLabel = dayLabel(key, md.third),
            showYear = showYear,
            showMonth = showMonth,
            items = list,
        )
    }
}

private fun dayLabel(key: String, day: Int): String {
    val today = dayKeyFmt.format(Date())
    if (key == today) return "今天"
    if (key == dayKeyFmt.format(Date(System.currentTimeMillis() - 86_400_000L))) return "昨天"
    val week = try {
        dayKeyFmt.parse(key)?.let { SimpleDateFormat("EEEE", Locale.getDefault()).format(it) }.orEmpty()
    } catch (e: Exception) {
        ""
    }
    return if (week.isBlank()) "${day}日" else "${day}日 $week"
}

/** 年/月分隔行。 */
@Composable
private fun PeriodHeader(label: String, big: Boolean) {
    Text(
        label,
        style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = if (big) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = if (big) 18.dp else 10.dp, bottom = 2.dp),
    )
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

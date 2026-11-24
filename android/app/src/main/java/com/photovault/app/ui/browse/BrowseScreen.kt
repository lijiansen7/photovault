package com.photovault.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import okhttp3.Headers
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.photovault.app.data.PhotoDto
import com.photovault.app.util.photoDesc
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch
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
    val restore by vm.restoreState.collectAsState()
    val imgHeaders = remember(cfg.authToken) {
        Headers.headersOf("Authorization", "Bearer ${cfg.authToken}")
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(cfg.lanUrl, cfg.wanUrl, cfg.authToken) { if (cfg.configured) vm.refresh() }

    // 滚到接近底部才翻页；不能在 item 组合期直接调用（会在每一帧重复触发）
    LaunchedEffect(gridState, items.size, vm.total, vm.loading) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= items.size - 12 && items.size < vm.total && !vm.loading) vm.loadMore()
            }
    }

    var confirmAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val groups = remember(items) { groupByDay(items) }

    // 索引 → 分组标题：表头行和它下面每个格子都映射到同一个标题，
    // 顶部那句「2026年11月」就是靠它在滚动时吸顶（网格没有原生 stickyHeader）。
    val labelAt = remember(groups) {
        val out = ArrayList<String>()
        groups.forEach { (label, list) ->
            out.add(label)
            repeat(list.size) { out.add(label) }
        }
        out
    }
    // 年份 → 在网格里的第一个索引，给右侧年份索引条用
    val yearAt = remember(groups) {
        val m = LinkedHashMap<Int, Int>()
        var idx = 0
        groups.forEach { (_, list) ->
            idx++ // 表头占一格
            list.forEach { p ->
                val y = yearOf(p.takenAt ?: p.uploadedAt)
                if (!m.containsKey(y)) m[y] = idx
                idx++
            }
        }
        m
    }

    var stickyLabel by remember { mutableStateOf<String?>(null) }
    // 网格里每一格（含表头）属于哪一年，用来给右侧年份条做「当前年」高亮。
    // 顺序必须和 labelAt 一致：先表头一格，再它下面每一个格子。
    val yearOfIndex = remember(groups) {
        val out = ArrayList<Int>()
        groups.forEach { (_, list) ->
            val y = list.firstOrNull()?.let { yearOf(it.takenAt ?: it.uploadedAt) }
            if (y != null) {
                out.add(y)
                list.forEach { p -> out.add(yearOf(p.takenAt ?: p.uploadedAt)) }
            }
        }
        out.toIntArray()
    }
    var scrolledYear by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(labelAt) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .collect { i ->
                stickyLabel = labelAt.getOrNull(i)
                yearOfIndex.getOrNull(i)?.let { scrolledYear = it }
            }
    }

    val filtering = vm.search.isNotBlank() || vm.gpsOnly || vm.year != null ||
        vm.folder != null || vm.filter != "all"

    fun clearAllFilters() {
        if (vm.search.isNotBlank()) vm.clearSearch()
        if (vm.gpsOnly) vm.toggleGps()
        if (vm.year != null) vm.changeYear(vm.year)
        if (vm.folder != null) vm.changeFolder(null)
        if (vm.filter != "all") vm.changeFilter("all")
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------------- 标题行
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "NAS 相册",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            !cfg.configured -> "还没配置 NAS"
                            vm.total > 0 -> buildString {
                                append("共 ${vm.total} 项")
                                // 筛选时不再报整个库的体积，那个数字跟当前结果对不上
                                if (!filtering) vm.stats?.let { s ->
                                    if (s.bytes > 0) append(" · ${humanSize(s.bytes)}")
                                }
                                if (filtering) append(" · 已筛选")
                            }
                            filtering -> "筛选后没有结果"
                            else -> "按时间浏览 NAS 上的照片"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = {
                    if (selectMode) vm.exitSelect() else vm.selectMode = true
                }) { Text(if (selectMode) "完成" else "选择") }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { showMenu = false; vm.refresh() },
                        )
                        DropdownMenuItem(
                            text = { Text("一键还原全部") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            enabled = vm.total > 0 && !restore.running,
                            onClick = { showMenu = false; confirmAll = true },
                        )
                        if (filtering) {
                            DropdownMenuItem(
                                text = { Text("清除全部筛选") },
                                leadingIcon = { Icon(Icons.Default.FilterList, null) },
                                onClick = { showMenu = false; clearAllFilters() },
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------- 搜索框
            OutlinedTextField(
                value = vm.search,
                onValueChange = vm::changeSearch,
                singleLine = true,
                placeholder = { Text("搜文件名、相册、机型、设备") },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                trailingIcon = {
                    if (vm.search.isNotEmpty()) {
                        IconButton(onClick = { vm.clearSearch() }) {
                            Icon(Icons.Default.Close, "清空", Modifier.size(18.dp))
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )

            // ------------------------------------------------------- 筛选条
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    FilterChip(
                        selected = vm.folder != null,
                        onClick = { showFolderPicker = true },
                        label = {
                            Text(vm.folder ?: "文件夹", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) },
                    )
                }
                item {
                    FilterChip(
                        selected = vm.gpsOnly,
                        onClick = { vm.toggleGps() },
                        label = { Text("有定位") },
                        leadingIcon = { Icon(Icons.Default.Place, null, Modifier.size(15.dp)) },
                    )
                }
                items(listOf("all" to "全部", "image" to "照片", "video" to "视频")) { (k, label) ->
                    FilterChip(
                        selected = vm.filter == k,
                        onClick = { vm.changeFilter(k) },
                        label = { Text(label) },
                    )
                }
                items(yearAt.keys.toList()) { y ->
                    FilterChip(
                        selected = vm.year == y,
                        onClick = { vm.changeYear(y) },
                        label = { Text("${y}年") },
                    )
                }
            }

            // ------------------------------------------------- 还原进度（运行时才出现）
            if (restore.running) {
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp), strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("正在还原到手机相册", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    restore.text ?: "准备中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { vm.cancelRestore() }) { Text("取消") }
                        }
                        if (restore.total > 0) {
                            LinearProgressIndicator(
                                progress = { restore.done.toFloat() / restore.total },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Text(
                                "${restore.done}/${restore.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
                        }
                    }
                }
            }

            // ------------------------------------------------------- 内容区
            Box(Modifier.weight(1f)) {
                when {
                    error != null -> ErrorState(
                        message = error!!,
                        detail = vm.errorDetail,
                        onRetry = { vm.refresh() },
                    )
                    items.isEmpty() && loading -> SkeletonGrid()
                    items.isEmpty() -> EmptyState(
                        configured = cfg.configured,
                        filtering = filtering,
                        onClearFilters = { clearAllFilters() },
                    )
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            state = gridState,
                            // 顶部留出吸顶标签的位置，底部留出悬浮导航的位置
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 36.dp, bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            groups.forEachIndexed { gi, (label, list) ->
                                item(span = { GridItemSpan(maxLineSpan) }, key = "day-$label-$gi") {
                                    DayHeader(label, list.size)
                                }
                                itemsIndexed(list, key = { _, p -> p.id }) { _, p ->
                                    PhotoCell(
                                        photo = p,
                                        url = vm.thumbUrl(p.id, 320),
                                        headers = imgHeaders,
                                        selected = p.id in selection,
                                        selectMode = selectMode,
                                        onClick = { if (selectMode) vm.toggleSelect(p.id) else vm.open(p) },
                                        onLongClick = { vm.selectMode = true; vm.toggleSelect(p.id) },
                                    )
                                }
                            }
                        }
                        stickyLabel?.let { StickyLabel(it) }
                    }
                }

                if (loading && items.isNotEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        // --------------------------------------------------- 选择模式底部操作条
        // 之前只把「选择」开关做出来了、没有落地动作，选完没地方点；这里补齐，
        // 位置要压在悬浮导航条上方，不然最后一行按钮会被切掉。
        if (selectMode) {
            val n = selection.size
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        if (n == 0) "先选照片" else "已选 $n 张",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { vm.toggleSelectAll() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (items.isNotEmpty() && n >= items.size) "取消" else "全选",
                            maxLines = 1, softWrap = false,
                        )
                    }
                    // 两个动作都带文字：删除只放一个图标的话，用户会以为没有这个功能
                    Button(
                        onClick = { vm.restoreSelected() },
                        enabled = n > 0 && !restore.running,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("还原", maxLines = 1)
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { confirmDelete = true },
                        enabled = n > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除", maxLines = 1)
                    }
                }
            }
        }

        // --------------------------------------------------- 右侧年份快速索引
        if (items.isNotEmpty() && yearAt.size > 1 && !selectMode) {
            YearIndex(
                years = yearAt.keys.toList(),
                active = scrolledYear ?: vm.year,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 1.dp),
                onPick = { y ->
                    val idx = yearAt[y]
                    if (idx != null) {
                        vm.year = null
                        scope.launch { gridState.scrollToItem(idx) }
                    } else {
                        vm.changeYear(y)
                    }
                },
            )
        }
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            folders = vm.folders,
            current = vm.folder,
            onPick = { vm.changeFolder(it); showFolderPicker = false },
            onDismiss = { showFolderPicker = false },
        )
    }

    // 视频走内置播放器（带 Authorization 头 + 播放进度记忆），不再跳走
    vm.player?.let { p ->
        com.photovault.app.ui.player.VideoPlayerDialog(
            mediaId = p.id,
            url = vm.fileUrl(p.id),
            authToken = cfg.authToken,
            title = p.filename,
            onDismiss = { vm.player = null },
        )
    }
    vm.viewer?.let { FullScreenViewer(it, vm, imgHeaders) }
    vm.detail?.let { DetailDialog(it, vm) }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
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
                Button(onClick = { vm.restoreAll(); confirmAll = false }) { Text("开始还原") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除选中的 ${vm.selection.size} 张？") },
            text = {
                Text(
                    "会先移进 NAS 的回收站，之后还能恢复；"
                        + "只有到「设置 → 回收站」里清空才会真正删掉文件。"
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.deleteSelected(); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: PhotoDto,
    url: String,
    headers: Headers,
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // 整格读一句就够：图 + 时间 + 角标本来会被念三四遍
            .semantics(mergeDescendants = true) { contentDescription = photoDesc(photo) },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .headers(headers)
                .crossfade(true)
                .build(),
            contentDescription = null,
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
                Icon(Icons.Default.PlayArrow, null, tint = Color.White,
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
                Icon(Icons.Default.Place, null, tint = Color.White, modifier = Modifier.size(13.dp))
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
private fun FullScreenViewer(p: PhotoDto, vm: BrowseViewModel, headers: Headers) {
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
                model = ImageRequest.Builder(LocalContext.current)
                    .data(vm.fileUrl(p.id))
                    .headers(headers)
                    .build(),
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
            // TalkBack 能把日期分组当标题跳读，不然一屏几百张只能一张张划
            modifier = Modifier.semantics { heading() },
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
        // 日期标题只用中文：手机语言是英文时会排出「2026年11月21日 Saturday」这种混搭
        if (d != null) SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(d) else key
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

// ------------------------------------------------------------------ 辅助 UI

/** 网格顶部的吸顶标签：滚到哪一组就显示哪一组，替代网格缺失的 stickyHeader。 */
@Composable
private fun StickyLabel(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = CircleShape,
        shadowElevation = 3.dp,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 右侧年份索引：竖着一列「26 / 25 / 24」，点一下直接跳到那一年的第一张。 */
@Composable
private fun YearIndex(
    years: List<Int>,
    active: Int?,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        years.forEach { y ->
            val selected = active == y
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                    )
                    .clickable { onPick(y) }
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    // 屏幕上只写了两位数字，读屏得念全「2026 年」
                    .semantics {
                        contentDescription = "${y}年"
                        if (selected) stateDescription = "当前"
                    },
            ) {
                Text(
                    "${y % 100}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SkeletonGrid() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(configured: Boolean, filtering: Boolean, onClearFilters: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                if (filtering) Icons.Default.Search else Icons.Default.PhotoLibrary, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(52.dp),
            )
            Text(
                when {
                    !configured -> "还没连上 NAS"
                    filtering -> "没有符合条件的照片"
                    else -> "NAS 上还没有照片"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when {
                    !configured -> "到「设置」里填 NAS 地址并登录，之后这里会自动出图"
                    filtering -> "换个关键词，或者把筛选清掉看看"
                    else -> "去「备份」页打开自动备份，照片就会同步上来"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filtering) {
                OutlinedButton(onClick = onClearFilters) { Text("清除筛选") }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, detail: String?, onRetry: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.CloudOff, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("重试") }
                if (!detail.isNullOrBlank()) {
                    TextButton(onClick = { showDetail = !showDetail }) {
                        Text(if (showDetail) "收起详情" else "详情")
                    }
                }
            }
            if (showDetail && !detail.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        detail,
                        Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderPickerDialog(
    folders: List<Pair<String, Int>>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按文件夹筛选") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                FolderRow("全部照片", null, current, onPick)
                folders.forEach { (name, count) -> FolderRow(name, count, current, onPick) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun FolderRow(
    name: String,
    count: Int?,
    current: String?,
    onPick: (String?) -> Unit,
) {
    val selected = when {
        count == null -> current == null
        else -> current == name
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onPick(if (count == null) null else name) }
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun yearOf(ms: Long): Int {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ms
    return c.get(java.util.Calendar.YEAR)
}

package com.photovault.app.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import okhttp3.Headers
import com.photovault.app.data.PhotoDto
import com.photovault.app.util.photoDesc
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun TrashScreen(vm: TrashViewModel = viewModel()) {
    val confirmDelete = remember { mutableStateOf(false) }
    val confirmEmpty = remember { mutableStateOf(false) }
    val cfg by vm.config.collectAsState()
    val imgHeaders = remember(cfg.authToken) {
        Headers.headersOf("Authorization", "Bearer ${cfg.authToken}")
    }
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, vm.items.size, vm.total, vm.loading, vm.loadingMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= vm.items.size - 18 && vm.items.size < vm.total && !vm.loadingMore) {
                    vm.loadMore()
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "回收站",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    if (vm.total > 0) "${vm.total} 张 · 确认后再永久删除" else "删除的照片会先进这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.selectMode) {
                IconButton(onClick = { vm.toggleSelectAll() }) {
                    Icon(Icons.Default.SelectAll, "全选")
                }
                TextButton(onClick = { vm.exitSelect() }) { Text("完成") }
            } else {
                TextButton(onClick = { vm.selectMode = true }) { Text("选择") }
            }
        }

        if (vm.selectMode) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已选 ${vm.selection.size} / ${vm.items.size}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { vm.restoreSelected() },
                        enabled = vm.selection.isNotEmpty() && !vm.busy,
                    ) {
                        Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                        Text("恢复", Modifier.padding(start = 4.dp))
                    }
                    Button(
                        onClick = { confirmDelete.value = true },
                        enabled = vm.selection.isNotEmpty() && !vm.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Text(if (vm.busy) "删除中…" else "删除", Modifier.padding(start = 4.dp))
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "回收站共 ${vm.total} 张（先勾选可只删其中几张）",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmEmpty.value = true },
                    enabled = vm.items.isNotEmpty() && !vm.busy,
                ) {
                    Icon(Icons.Default.DeleteForever, null, Modifier.size(18.dp))
                    Text(if (vm.busy) "清空中…" else "清空全部", Modifier.padding(start = 6.dp))
                }
            }
        }

        vm.msg?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.startsWith("已")) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        when {
            vm.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(vm.error!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { vm.refresh() }) { Text("重试") }
                }
            }
            vm.loading && vm.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            vm.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("回收站是空的", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    state = gridState,
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.items, key = { it.id }) { p ->
                        TrashCell(
                            photo = p,
                            url = vm.thumbUrl(p.id, 256),
                            headers = imgHeaders,
                            selected = p.id in vm.selection,
                            selectMode = vm.selectMode,
                            onClick = { if (vm.selectMode) vm.toggleSelect(p.id) },
                            onLongClick = { vm.selectMode = true; vm.toggleSelect(p.id) },
                        )
                    }
                    if (vm.loadingMore || vm.items.size < vm.total) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (vm.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("加载中…", Modifier.padding(start = 8.dp),
                                        style = MaterialTheme.typography.bodySmall)
                                } else {
                                    TextButton(onClick = { vm.loadMore() }) { Text("加载更多") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete.value) {
        AlertDialog(
            onDismissRequest = { confirmDelete.value = false },
            title = { Text("永久删除选中的 ${vm.selection.size} 张？") },
            text = { Text("会把它们的原始文件从 NAS 磁盘上永久抹掉，无法恢复。未勾选的会留在回收站。") },
            confirmButton = {
                Button(
                    onClick = { vm.deleteSelected(); confirmDelete.value = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete.value = false }) { Text("取消") } },
        )
    }

    if (confirmEmpty.value) {
        AlertDialog(
            onDismissRequest = { confirmEmpty.value = false },
            title = { Text("清空整个回收站？") },
            text = { Text("会把 ${vm.total} 张照片的原始文件从 NAS 磁盘上永久抹掉，无法恢复。") },
            confirmButton = {
                Button(
                    onClick = { vm.emptyAll(); confirmEmpty.value = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("永久清空") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty.value = false }) { Text("取消") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrashCell(
    photo: PhotoDto,
    url: String,
    headers: Headers,
    selected: Boolean,
    selectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val ctx = LocalContext.current
    // 选择状态变化会重组整个格子；ImageRequest 缓存住，别每次重组都重新 build。
    val model = remember(url, headers) {
        ImageRequest.Builder(ctx)
            .data(url)
            .headers(headers)
            .crossfade(false)
            .build()
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription = photoDesc(photo) + "，已删除"
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                    )
                )
                .padding(horizontal = 7.dp, vertical = 5.dp),
        ) {
            Text(
                photo.filename,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

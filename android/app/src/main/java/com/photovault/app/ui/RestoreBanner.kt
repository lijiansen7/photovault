package com.photovault.app.ui

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photovault.app.util.Humanize
import com.photovault.app.work.RestoreWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 还原进度的一次快照。分批入队时会把同时在跑的几个任务加起来。 */
data class RestoreUi(
    val doneBytes: Long,
    val totalBytes: Long,
    val speed: Double,
    val index: Int,
    val count: Int,
    val name: String?,
) {
    /** 0~1；拿不到总字节也没拿到张数时返回 null（进度条走不确定态） */
    val fraction: Float?
        get() = when {
            totalBytes > 0 -> (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            count > 0 && index > 0 -> (index.toFloat() / count).coerceIn(0f, 1f)
            else -> null
        }
}

class RestoreBannerViewModel(app: Application) : AndroidViewModel(app) {

    private val wm = WorkManager.getInstance(app)

    /**
     * 盯住所有打了 [RestoreWorker.TAG] 的还原任务。
     * 只认 RUNNING：排队中的那几批还没进度，混进来会把总数搅乱。
     */
    val state: StateFlow<RestoreUi?> =
        wm.getWorkInfosByTagFlow(RestoreWorker.TAG)
            .map { infos -> summarize(infos) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun cancel() = wm.cancelAllWorkByTag(RestoreWorker.TAG)

    private fun summarize(infos: List<WorkInfo>): RestoreUi? {
        val active = infos.filter { it.state == WorkInfo.State.RUNNING }
        if (active.isEmpty()) return null
        val done = active.sumOf { it.progress.getLong(RestoreWorker.KEY_DONE_BYTES, 0L) }
        val total = active.sumOf { it.progress.getLong(RestoreWorker.KEY_TOTAL_BYTES, 0L) }
        val speed = active.sumOf { it.progress.getDouble(RestoreWorker.KEY_SPEED, 0.0) }
        val index = active.sumOf { it.progress.getInt(RestoreWorker.KEY_INDEX, 0) }
        val count = active.sumOf { it.progress.getInt(RestoreWorker.KEY_COUNT, 0) }
        val name = active.firstNotNullOfOrNull {
            it.progress.getString(RestoreWorker.KEY_TEXT)?.takeIf { s -> s.isNotBlank() }
        }
        return RestoreUi(done, total, speed, index, count, name)
    }
}

/**
 * App 顶部的还原进度条：百分比 + 速度 + 剩余时间，跑完自动消失。
 *
 * 放在 Scaffold 的内容区顶部（而不是某个 Tab 里），因为还原可能是在相册页点的，
 * 用户切到设置页也该还能看到进度。
 */
@Composable
fun RestoreBanner(vm: RestoreBannerViewModel = viewModel()) {
    val p by vm.state.collectAsStateWithLifecycle()
    val cur = p ?: return

    val target = cur.fraction ?: 0f
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(420),
        label = "restoreFraction",
    )

    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download, null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "正在还原到本地相册",
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                TextButton(onClick = { vm.cancel() }) { Text("取消") }
            }

            if (cur.fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
            } else {
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }

            val eta = Humanize.eta(cur.totalBytes - cur.doneBytes, cur.speed)
            Text(
                buildString {
                    append(Humanize.speed(cur.speed))
                    if (cur.count > 0) append(" · ${cur.index}/${cur.count} 张")
                    if (cur.totalBytes > 0) {
                        append(" · ")
                        append(Humanize.bytes(cur.doneBytes))
                        append("/")
                        append(Humanize.bytes(cur.totalBytes))
                    }
                    if (eta != null) append(" · ").append(eta)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!cur.name.isNullOrBlank()) {
                Text(
                    cur.name!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

package com.photovault.app.ui.browse

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.photovault.app.data.NasApi
import com.photovault.app.data.NasConfig
import com.photovault.app.data.PhotoDto
import com.photovault.app.data.SettingsRepo
import com.photovault.app.work.RestoreWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)
    private val wm = WorkManager.getInstance(app)

    val config: StateFlow<NasConfig> =
        repo.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NasConfig())

    var items by mutableStateOf<List<PhotoDto>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var filter by mutableStateOf("all")           // all | image | video
    /** 当前文件夹（rel_dir，如 DCIM/Camera）；null = 全部 */
    var folder by mutableStateOf<String?>(null)
    /** 可切换的文件夹列表：目录名 → 张数 */
    var folders by mutableStateOf<List<Pair<String, Int>>>(emptyList())
        private set
    var selectMode by mutableStateOf(false)
    var selection by mutableStateOf(setOf<String>())
    var detail by mutableStateOf<PhotoDto?>(null)

    /** 全库统计（照片/视频各多少、占多大） */
    var stats by mutableStateOf<com.photovault.app.data.NasStats?>(null)
        private set

    /** 正在全屏看的图片；视频走系统播放器，不进这里 */
    var viewer by mutableStateOf<PhotoDto?>(null)

    private var offset = 0
    private val pageSize = 300

    init { refresh() }

    private fun loadStats() {
        viewModelScope.launch {
            val cfg = repo.current()
            if (!cfg.configured) return@launch
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).stats() }.onSuccess { stats = it }
            }
        }
    }

    // --------------------------------------------------------- 查看/播放 ----

    /** 点开一个媒体：图片进全屏查看，视频交给系统播放器。 */
    fun open(p: PhotoDto) {
        if (p.isVideo) playExternally(p) else viewer = p
    }

    private fun playExternally(p: PhotoDto) {
        val ctx = getApplication<Application>()
        // 用带 token 的直链交给系统播放器，比内置播放器省事，也能用上硬件解码
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(fileUrl(p.id)), p.mime.ifBlank { "video/*" })
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
            .onFailure { error = "手机上没有能播放这种视频的应用" }
    }

    fun changeFilter(f: String) {
        filter = f
        refresh()
    }

    fun changeFolder(f: String?) {
        folder = f
        refresh()
    }

    private fun kindArg(): String? = if (filter == "all") null else filter

    private fun loadFolders() {
        viewModelScope.launch {
            val cfg = repo.current()
            if (!cfg.configured) return@launch
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).folders() }.onSuccess { folders = it }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            offset = 0
            items = emptyList()
            val cfg = repo.current()
            if (!cfg.configured) {
                error = "还没有配置 NAS 地址"
                loading = false
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).list(0, pageSize, kindArg(), folder) }
                    .onSuccess { (list, t) ->
                        items = list.distinctBy { it.id }
                        total = t
                        offset = list.size
                    }
                    .onFailure { error = it.message ?: "加载失败" }
            }
            loading = false
            loadStats()
            loadFolders()
        }
    }

    fun loadMore() {
        if (loading || offset >= total) return
        // 必须**同步**置位：写在协程里的话，快速滑到底时同一帧会触发两次 loadMore、
        // 两次都用同一个 offset 拉数据 → 同一页被追加两遍 → 列表里出现重复 id
        // → LazyVerticalGrid 的 key 重复，直接崩（Key "xxx" was already used）。
        loading = true
        viewModelScope.launch {
            val cfg = repo.current()
            if (!cfg.configured) { loading = false; return@launch }
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).list(offset, pageSize, kindArg(), folder) }
                    // 再兜一层：万一服务端还是给了重复的，去重后再追加
                    .onSuccess { (list, _) ->
                        items = (items + list).distinctBy { it.id }
                        offset += list.size
                    }
            }
            loading = false
        }
    }

    fun toggleSelect(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun toggleSelectAll() {
        selection = if (selection.size == items.size) emptySet() else items.map { it.id }.toSet()
    }

    fun exitSelect() {
        selectMode = false
        selection = emptySet()
    }

    // ------------------------------------------------------------- 还原 ----

    private fun enqueue(jobs: List<PhotoDto>, all: Boolean) {
        if (all) {
            wm.enqueueUniqueWork(
                "photovault_restore", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RestoreWorker>()
                    .setInputData(workDataOf(RestoreWorker.KEY_ALL to true))
                    .build(),
            )
            return
        }
        // WorkManager 的 Data 有 10KB 上限，分批入队
        jobs.chunked(200).forEachIndexed { i, chunk ->
            val data: Data = workDataOf(
                RestoreWorker.KEY_IDS to chunk.map { it.id }.toTypedArray(),
                RestoreWorker.KEY_NAMES to chunk.map { it.filename }.toTypedArray(),
                RestoreWorker.KEY_MIMES to chunk.map { it.mime }.toTypedArray(),
                RestoreWorker.KEY_TAKEN to chunk.map { it.takenAt ?: 0L }.toLongArray(),
                RestoreWorker.KEY_VIDEO to chunk.map { it.isVideo }.toBooleanArray(),
                RestoreWorker.KEY_DIRS to chunk.map { it.relDir ?: "" }.toTypedArray(),
                RestoreWorker.KEY_ALBUMS to chunk.map { it.album ?: "" }.toTypedArray(),
                RestoreWorker.KEY_SIZES to chunk.map { it.size }.toLongArray(),
            )
            wm.enqueue(
                OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(data).build()
            )
        }
    }

    fun restoreSelected() {
        val chosen = items.filter { it.id in selection }
        enqueue(chosen, false)
        exitSelect()
    }

    fun restoreAll() = enqueue(emptyList(), true)

    fun restoreOne(p: PhotoDto) = enqueue(listOf(p), false)

    // ------------------------------------------------------------- 删除 ----

    /** 删除选中的照片（软删除：进 NAS 回收站，之后还能恢复）。 */
    fun deleteSelected() {
        val ids = items.filter { it.id in selection }.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val cfg = repo.current()
            withContext(Dispatchers.IO) {
                runCatching { NasApi(cfg).deleteMany(ids) }
                    .onFailure { error = it.message ?: "删除失败" }
            }
            exitSelect()
            refresh()
        }
    }

    fun thumbUrl(id: String): String = NasApi(config.value).thumbUrl(id)

    fun fileUrl(id: String): String = NasApi(config.value).fileUrl(id)
}

package com.photovault.app.ui.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photovault.app.data.AlbumInfo
import com.photovault.app.data.EndpointRouter
import com.photovault.app.data.MediaRepository
import com.photovault.app.data.NasApi
import com.photovault.app.data.NasStats
import com.photovault.app.data.SettingsRepo
import com.photovault.app.work.UploadScheduler
import com.photovault.app.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)

    val config: StateFlow<com.photovault.app.data.NasConfig> =
        repo.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            com.photovault.app.data.NasConfig())

    val workInfo: StateFlow<List<WorkInfo>> =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWorkFlow("photovault_upload_once")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats = MutableStateFlow<NasStats?>(null)
    val localCount = MutableStateFlow(0)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** 原始异常文本，收在「详情」里 */
    val errorDetail = MutableStateFlow<String?>(null)
    /** 最近备份的几张，首页做个缩略图预览 */
    val recent = MutableStateFlow<List<com.photovault.app.data.PhotoDto>>(emptyList())
    /** 上次没传上去的照片明细（文件名 + 原因），可单张重试 */
    val failures = MutableStateFlow<List<com.photovault.app.data.UploadFailure>>(emptyList())
    /** NAS 健康检查往返耗时（毫秒）；null = 还没测到 */
    val latencyMs = MutableStateFlow<Int?>(null)
    /** 服务端 /api/health 返回的版本号 */
    val serverVersion = MutableStateFlow<String?>(null)
    /** 当前实际使用的地址（内网或外网） */
    var activeUrl by mutableStateOf("")
        private set
    var activeIsLan by mutableStateOf(false)
        private set

    /** 立即同步是否在跑（含 ENQUEUED/RUNNING/BLOCKED），用于按钮转圈与进度条 */
    val syncing = MutableStateFlow(false)
    /** 上一次同步的结果文案，null 表示还没同步过 */
    val lastSyncResult = MutableStateFlow<String?>(null)

    /** 立即同步的实时进度（worker 通过 setProgress 上报）；null = 还没有细分进度 */
    val syncProgress = MutableStateFlow<SyncProgress?>(null)

    /** 手机上能看到的相册，供用户挑要备份哪些 */
    val albums = MutableStateFlow<List<AlbumInfo>>(emptyList())
    val albumsLoading = MutableStateFlow(false)

    /** 点了「立即同步」、结果还没回来。避免按钮转圈被 workInfo 的当前状态瞬间覆盖掉。 */
    private var awaitingResult = false

    init {
        refresh()
        // 盯着唯一的「立即同步」任务：一进 ENQUEUED/RUNNING 就转圈，结束就给出结果。
        viewModelScope.launch {
            workInfo.collect { list ->
                val active = list.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED
                }
                if (active) {
                    syncing.value = true
                    // 重新武装：上一轮遗留的 finished 状态可能抢先被当成结果，
                    // 看到 active 说明新一轮真的起来了，把上次结果清掉、继续等。
                    awaitingResult = true
                    lastSyncResult.value = null
                    // 实时进度：worker 在 RUNNING 时会持续 setProgress
                    val running = list.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    syncProgress.value = running?.let {
                        SyncProgress(
                            text = it.progress.getString(UploadWorker.KEY_TEXT),
                            done = it.progress.getInt(UploadWorker.KEY_DONE, 0),
                            total = it.progress.getInt(UploadWorker.KEY_TOTAL, 0),
                        )
                    }
                    return@collect
                }
                if (list.none { it.state.isFinished }) {
                    if (!awaitingResult) syncing.value = false
                    return@collect
                }
                if (!awaitingResult) return@collect
                awaitingResult = false
                syncProgress.value = null

                // 链式接续后 list 里可能挂着好几批 finished：把成功的几批加总成一次总账
                val finished = list.filter { it.state.isFinished }
                val ok = finished.filter { it.state == WorkInfo.State.SUCCEEDED }
                val cancelled = finished.any { it.state == WorkInfo.State.CANCELLED }
                val anyFailed = finished.any { it.state == WorkInfo.State.FAILED }
                val done = ok.sumOf { it.outputData.getInt(UploadWorker.KEY_DONE, 0) }
                val skipped = ok.sumOf { it.outputData.getInt(UploadWorker.KEY_SKIPPED, 0) }
                val failed = ok.sumOf { it.outputData.getInt(UploadWorker.KEY_FAILED, 0) }
                val deleted = ok.sumOf { it.outputData.getInt(UploadWorker.KEY_DELETED, 0) }

                val label = when {
                    cancelled -> "已取消备份（已经传上去的不受影响）"
                    anyFailed -> "同步失败，稍后会自动重试"
                    done > 0 || failed > 0 -> "已备份 $done 张 · 跳过 $skipped 张 · 失败 $failed 张" +
                        if (deleted > 0) " · NAS 回收站 +$deleted" else ""
                    skipped > 0 -> "已是最新 · 跳过 $skipped 张（都备份过了）"
                    deleted > 0 -> "已把 $deleted 张手机删除的照片移入 NAS 回收站"
                    else -> "没有需要备份的照片"
                }
                viewModelScope.launch {
                    delay(800)
                    syncing.value = false
                    lastSyncResult.value = label
                    refresh()   // 刷新「NAS 已备份」等统计，不然数字对不上
                }
            }
        }
    }

    fun loadAlbums(force: Boolean = false) {
        if (albumsLoading.value) return
        if (!force && albums.value.isNotEmpty()) return
        viewModelScope.launch {
            albumsLoading.value = true
            albums.value = withContext(Dispatchers.IO) {
                runCatching { MediaRepository(getApplication()).listAlbums() }.getOrDefault(emptyList())
            }
            albumsLoading.value = false
        }
    }

    /** 自动备份总开关。只有打开时才下发周期任务 —— 配好地址不等于用户同意开始传。 */
    fun setBackupEnabled(on: Boolean) {
        viewModelScope.launch {
            val cfg = repo.current()
            if (on && !cfg.loggedIn) {
                error.value = "请先在「设置」里登录 NAS 账号"
                return@launch
            }
            val next = cfg.copy(backupEnabled = on)
            repo.save(next)
            UploadScheduler.apply(getApplication(), next)
        }
    }

    /** 勾/取消一个相册。第一次操作时把"没挑过（null）"实体化成明确的列表。 */
    fun setAlbumSelected(name: String, on: Boolean) {
        viewModelScope.launch {
            val cfg = repo.current()
            val current = cfg.selectedAlbums ?: albums.value.map { it.name }.toSet()
            val next = current.toMutableSet().apply { if (on) add(name) else remove(name) }
            repo.save(cfg.copy(selectedAlbums = next))
            // 关键：新勾上的相册里，照片大多"诞生"在同步水位线之前，
            // 不清零的话 querySince(lastSyncMs) 会把它们整段过滤掉 ——
            // 表现就是"追加了相册，却什么都不上传"。
            // 清零后下一轮重新全量比对（已备份的按内容指纹跳过，不会重复传）。
            repo.resetSync()
        }
    }

    fun setAllAlbums(on: Boolean) {
        viewModelScope.launch {
            val cfg = repo.current()
            repo.save(cfg.copy(
                selectedAlbums = if (on) albums.value.map { it.name }.toSet() else emptySet()
            ))
            repo.resetSync()   // 同上：改了选择就要让下一轮重新全量比对
        }
    }

    /** 挑中的相册数；null 表示还没挑过（等于全部） */
    fun chosenAlbumCount(): Int? = config.value.selectedAlbums?.size

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            error.value = null
            errorDetail.value = null
            failures.value = com.photovault.app.data.FailureStore.load(getApplication())
            val cfg = repo.current()
            // 只统计勾选的相册：和「NAS 已备份」同口径，不然用户会以为漏备份了
            localCount.value = withContext(Dispatchers.IO) {
                runCatching {
                    MediaRepository(getApplication()).countAll(cfg.includeVideo, cfg.selectedAlbums)
                }.getOrDefault(0)
            }
            if (cfg.configured) {
                withContext(Dispatchers.IO) {
                    val api = NasApi(cfg)
                    val started = System.nanoTime()
                    runCatching { api.health() }
                        .onSuccess {
                            latencyMs.value = ((System.nanoTime() - started) / 1_000_000).toInt()
                            serverVersion.value = it
                            activeUrl = EndpointRouter.active(cfg)
                            activeIsLan = EndpointRouter.isLan(cfg, activeUrl)
                        }
                        .onFailure { latencyMs.value = null }
                    runCatching { api.stats() }
                        .onSuccess { stats.value = it }
                        .onFailure {
                            error.value = com.photovault.app.util.Errors.humanize(it)
                            errorDetail.value = com.photovault.app.util.Errors.detail(it)
                        }
                    // 首页预览：取最近 6 张，失败不影响主流程
                    runCatching { api.list(0, 6, viewerDevice = cfg.deviceId) }
                        .onSuccess { (list, _) -> recent.value = list }
                }
            } else {
                stats.value = null
                recent.value = emptyList()
                latencyMs.value = null
                serverVersion.value = null
                activeUrl = ""
                activeIsLan = false
                error.value = if (cfg.baseUrl.isNotBlank() || cfg.lanUrl.isNotBlank())
                    "还没登录 —— 到「设置」里用 NAS 账号登录一次"
                else "还没有配置 NAS 地址"
            }
            loading.value = false
        }
    }

    fun syncNow() {
        syncing.value = true
        awaitingResult = true
        lastSyncResult.value = null
        error.value = null
        UploadScheduler.runOnceForced(getApplication())
    }

    /** 取消正在跑的「立即同步」。已经传成功的照片不会被撤回。 */
    fun cancelSync() {
        UploadScheduler.cancelOnce(getApplication())
        awaitingResult = false
        syncing.value = false
        lastSyncResult.value = "已取消备份（已经传上去的不受影响）"
    }

    /** 单张重试 */
    fun retryOne(f: com.photovault.app.data.UploadFailure) {
        UploadScheduler.retryIds(getApplication(), listOf(f.mediaId))
    }

    /** 把失败的全部重试一遍 */
    fun retryAllFailures() {
        UploadScheduler.retryIds(getApplication(), failures.value.map { it.mediaId })
    }

    fun clearFailures() {
        com.photovault.app.data.FailureStore.clear(getApplication())
        failures.value = emptyList()
    }

    val isRunning: Boolean
        get() = workInfo.value.any { it.state == WorkInfo.State.RUNNING }
}

/** 立即同步的实时进度。total<=0 表示还在扫描/分析阶段，还不知道总数。 */
data class SyncProgress(val text: String?, val done: Int, val total: Int)

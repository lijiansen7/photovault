package com.photovault.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photovault.app.data.LocalMedia
import com.photovault.app.data.MediaRepository
import com.photovault.app.data.NasApi
import com.photovault.app.data.SettingsRepo
import com.photovault.app.util.Notifications
import com.photovault.app.util.readExif
import com.photovault.app.util.sha256Of
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DONE = "done"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_TOTAL = "total"
        const val KEY_TEXT = "text"
        private const val MAX_PER_RUN = 800

        /** 并行上传数。家庭 NAS 上 2 路足够，再多 SQLite 写入会开始排队。 */
        private const val UPLOAD_CONCURRENCY = 2
    }

    private val ctx get() = applicationContext

    /**
     * 刷新进度。App 在前台才升级成前台服务（保活、不被系统掐断），
     * 后台时只发普通通知 —— Android 12+ 从后台 startForeground 会直接杀进程。
     */
    private suspend fun progress(text: String, current: Int = 0, total: Int = 0) {
        // 关键：把进度写进 WorkInfo.progress，App 首页的进度条就是从这里读的。
        // 之前只发了通知、没调 setProgress，导致 UI 永远只能显示兜底文案
        // 「正在扫描并上传…」，拿不到已处理几张/共几张。
        runCatching {
            setProgress(workDataOf(
                KEY_TEXT to text, KEY_DONE to current, KEY_TOTAL to total,
            ))
        }
        val n = Notifications.build(
            ctx, Notifications.CH_SYNC, "正在备份到 NAS", text,
            progress = current to total, indeterminate = total == 0
        )
        if (!Notifications.appInForeground(ctx)) {
            Notifications.notify(ctx, Notifications.ID_SYNC, n)
            return
        }
        try {
            setForeground(Notifications.foregroundInfo(Notifications.ID_SYNC, n))
        } catch (t: Throwable) {
            Notifications.notify(ctx, Notifications.ID_SYNC, n)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = SettingsRepo(ctx)
        val cfg = repo.current()
        if (!cfg.configured) return@withContext Result.success()

        val api = NasApi(cfg)
        val media = MediaRepository(ctx)
        val deviceId = repo.deviceId()

        progress("正在扫描本机相册…")

        // 用户勾了哪些相册就只传哪些；null 表示还没挑过 → 全部
        val picked = cfg.selectedAlbums
        val all = media.querySince(cfg.lastSyncMs, cfg.includeVideo)
            .filter { it.size > 0 }
            .filter { picked == null || (it.bucket ?: "") in picked }

        // 单次上限。但边界要往外扩：如果第 800 张和第 801 张的 DATE_ADDED 在同一秒，
        // 按 800 截断后水位线会越过同一秒里剩下的照片，**它们永远传不上**。
        // 所以把与截断点同秒的一并带上（批量恢复/导入时同一秒落盘很常见）。
        val capped = all.take(MAX_PER_RUN)
        val candidates = if (capped.isEmpty()) emptyList() else {
            val boundary = capped.last().dateAddedSec
            all.takeWhile { it.dateAddedSec <= boundary }
        }

        if (candidates.isEmpty()) {
            Notifications.cancel(ctx, Notifications.ID_SYNC)
            return@withContext Result.success(workDataOf(KEY_DONE to 0, KEY_SKIPPED to 0, KEY_FAILED to 0))
        }

        // 1) 粗筛：只报大小 / 拍摄时间 / 文件名，两边都不用读文件内容。
        //    第二次以后的备份里绝大多数照片在这里就被跳过了，一次 IO 都不用做。
        val coarse = runCatching {
            api.prescreen(candidates.map {
                NasApi.PrescreenItem(it.id.toString(), it.size, it.dateTaken, it.displayName)
            })
        }.getOrDefault(emptySet())

        val needInspect = candidates.filter { it.id.toString() !in coarse }

        // 2) 只有粗筛没命中的才真的读文件算指纹（顺便读 EXIF 的时间/GPS）
        val fingerprinted = mutableListOf<Pair<LocalMedia, String>>()
        needInspect.forEachIndexed { i, m ->
            if (isStopped) return@withContext Result.success()
            m.applyExif(readExif(ctx, m.uri))
            val sha = sha256Of(ctx, m.uri)
            if (!sha.isNullOrBlank()) fingerprinted += m to sha
            if (i % 20 == 0) {
                progress("正在分析第 ${i + 1}/${needInspect.size} 张…", i + 1, needInspect.size)
            }
        }

        // 3) 精确查重，兜住粗筛的漏判
        val existing = mutableSetOf<String>()
        fingerprinted.map { it.second }.chunked(200).forEach { chunk ->
            existing += runCatching { api.existing(chunk) }.getOrDefault(emptySet())
        }

        val todo = fingerprinted.filter { it.second !in existing }
        val skipped = candidates.size - todo.size

        // 4) 并发上传：网络往返占大头，两路并行能把总时间压掉近一半
        val uploaded = AtomicInteger()
        val failed = AtomicInteger()
        val tooLarge = AtomicInteger()
        val done = AtomicInteger()
        val gate = Semaphore(UPLOAD_CONCURRENCY)

        if (todo.isNotEmpty()) {
            coroutineScope {
                todo.map { (m, _) ->
                    async {
                        if (isStopped) return@async
                        gate.withPermit {
                            val ok = runCatching { api.upload(ctx, m, deviceId) }.isSuccess
                            if (ok) uploaded.incrementAndGet() else failed.incrementAndGet()
                            val n = done.incrementAndGet()
                            if (n % 10 == 0 || n == todo.size) {
                                progress("正在备份 $n/${todo.size}", n, todo.size)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val okCount = uploaded.get()
        val failCount = failed.get()

        // 5) 只有全部成功才推进同步水位，失败的下一轮还会再试
        if (failCount == 0) {
            val newest = candidates.maxOfOrNull { it.dateAddedSec }?.times(1000) ?: System.currentTimeMillis()
            repo.markSynced(maxOf(cfg.lastSyncMs, newest))
        }

        Notifications.cancel(ctx, Notifications.ID_SYNC)
        Notifications.done(
            ctx,
            "备份完成",
            "新增 $okCount 张，跳过 $skipped 张已备份，失败 $failCount 张"
        )

        val out = workDataOf(KEY_DONE to okCount, KEY_SKIPPED to skipped, KEY_FAILED to failCount)

        // 这一批打满了单次上限（候选比 800 多），而且没有失败 → 自动接续下一批，
        // 一直传到扫完为止，用户不用一遍遍手动点「立即同步」。
        // 有失败就不接了：先让用户看到失败数，重试交给 WorkManager 的 Result.retry。
        if (failCount == 0 && candidates.size >= MAX_PER_RUN) {
            runCatching { UploadScheduler.runOnceChained(applicationContext) }
        }

        if (failCount > 0 && runAttemptCount < 3) Result.retry() else Result.success(out)
    }
}

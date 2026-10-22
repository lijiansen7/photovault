package com.photovault.app.work

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photovault.app.data.MediaRepository
import com.photovault.app.data.NasApi
import com.photovault.app.data.SettingsRepo
import com.photovault.app.util.Humanize
import com.photovault.app.util.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FilterInputStream

/**
 * 把 NAS 上的照片还原回本机相册。
 *
 * 入参：
 *   - [KEY_IDS]/[KEY_NAMES]/[KEY_MIMES]/[KEY_TAKEN]/[KEY_VIDEO]  -> 还原指定照片
 *   - [KEY_ALL] = true                                          -> 一键还原全部
 *
 * 进度按**字节**上报（照片大小差得远，按张数算的进度条会一顿一顿），
 * 同时算一个滑动平均速度。App 顶部的进度条和通知栏都从这里读。
 */
class RestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        /** 所有还原任务都打的 tag，UI 靠它把进度取回来（分批入队会有好几个任务） */
        const val TAG = "photovault_restore_progress"

        const val KEY_IDS = "ids"
        const val KEY_NAMES = "names"
        const val KEY_MIMES = "mimes"
        const val KEY_TAKEN = "taken"
        const val KEY_VIDEO = "video"
        const val KEY_DIRS = "dirs"
        const val KEY_ALBUMS = "albums"
        const val KEY_SIZES = "sizes"
        const val KEY_ALL = "all"

        const val KEY_RESTORED = "restored"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"

        // ---- 实时进度（setProgress 里带的字段）----
        const val KEY_TEXT = "text"
        const val KEY_INDEX = "index"
        const val KEY_COUNT = "count"
        const val KEY_DONE_BYTES = "doneBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_SPEED = "speed"

        /** 进度刷新间隔。太快会频繁 setProgress/刷通知，太慢速度数字又跳得难受。 */
        private const val REPORT_MS = 400L
    }

    private val ctx get() = applicationContext

    private var doneBytes = 0L
    private var totalBytes = 0L
    private var speed = 0.0
    private var lastSampleBytes = 0L
    private var lastSampleMs = 0L
    private var curIndex = 0
    private var curCount = 0
    private var curName = ""

    /** 定时器和主循环都会调 [report]，串起来免得 setForeground 互相打架 */
    private val reportLock = Mutex()

    /** 记一次进度：算速度 -> 写 WorkInfo.progress（UI 读这个）-> 刷通知。 */
    private suspend fun report() = reportLock.withLock {
        val now = SystemClock.elapsedRealtime()
        if (lastSampleMs > 0) {
            val dt = (now - lastSampleMs) / 1000.0
            if (dt >= 0.25) {
                val instant = (doneBytes - lastSampleBytes) / dt
                // 滑动平均：只用瞬时值的话数字会随 IO 抖动上下乱跳
                speed = if (speed <= 0) instant else speed * 0.65 + instant * 0.35
                lastSampleBytes = doneBytes
                lastSampleMs = now
            }
        } else {
            lastSampleBytes = doneBytes
            lastSampleMs = now
        }

        runCatching {
            setProgress(
                workDataOf(
                    KEY_TEXT to curName,
                    KEY_INDEX to curIndex,
                    KEY_COUNT to curCount,
                    KEY_DONE_BYTES to doneBytes,
                    KEY_TOTAL_BYTES to totalBytes,
                    KEY_SPEED to speed,
                )
            )
        }

        val line = buildString {
            append(Humanize.speed(speed))
            if (curCount > 0) append(" · $curIndex/$curCount")
            append(" · ").append(Humanize.bytes(doneBytes))
            if (totalBytes > 0) append("/").append(Humanize.bytes(totalBytes))
        }
        val byBytes = totalBytes > 0
        val n = Notifications.build(
            ctx, Notifications.CH_RESTORE, "正在还原到本地相册",
            if (curName.isBlank()) line else "$line · $curName",
            // 通知的进度是 Int，按 KB 算免得大文件溢出
            progress = if (byBytes) {
                (minOf(doneBytes, totalBytes) / 1024).toInt() to (totalBytes / 1024).toInt()
            } else {
                curIndex to curCount
            },
            indeterminate = !byBytes && curCount == 0,
        )
        if (!Notifications.appInForeground(ctx)) {
            Notifications.notify(ctx, Notifications.ID_RESTORE, n)
            return@withLock
        }
        try {
            setForeground(Notifications.foregroundInfo(Notifications.ID_RESTORE, n))
        } catch (t: Throwable) {
            Notifications.notify(ctx, Notifications.ID_RESTORE, n)
        }
    }

    data class Job(
        val id: String,
        val name: String,
        val mime: String,
        val takenAt: Long?,
        val isVideo: Boolean,
        /** 备份时手机上的相对目录，还原回这里 */
        val relDir: String?,
        /** 备份时的相册名，给没有 relDir 的老数据兜底推断用 */
        val album: String?,
        /** 字节数，用来判断本地是不是已经有同一张 */
        val size: Long,
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cfg = SettingsRepo(ctx).current()
        if (!cfg.configured) return@withContext Result.failure()
        val api = NasApi(cfg)
        val media = MediaRepository(ctx)

        val jobs: List<Job> = if (inputData.getBoolean(KEY_ALL, false)) {
            curName = "正在拉取 NAS 照片清单…"
            report()
            buildList {
                var offset = 0
                while (true) {
                    val (items, total) = api.list(offset, 500)
                    if (items.isEmpty()) break
                    items.forEach { p ->
                        add(Job(p.id, p.filename, p.mime.ifBlank { guess(p.filename) }, p.takenAt,
                                p.isVideo, p.relDir, p.album, p.size))
                    }
                    offset += items.size
                    if (offset >= total) break
                }
            }
        } else {
            val ids = inputData.getStringArray(KEY_IDS) ?: return@withContext Result.failure()
            val names = inputData.getStringArray(KEY_NAMES) ?: emptyArray()
            val mimes = inputData.getStringArray(KEY_MIMES) ?: emptyArray()
            val taken = inputData.getLongArray(KEY_TAKEN) ?: LongArray(0)
            val videos = inputData.getBooleanArray(KEY_VIDEO)
            val dirs = inputData.getStringArray(KEY_DIRS) ?: emptyArray()
            val albums = inputData.getStringArray(KEY_ALBUMS) ?: emptyArray()
            val sizes = inputData.getLongArray(KEY_SIZES) ?: LongArray(0)
            ids.mapIndexed { i, id ->
                Job(
                    id = id,
                    name = names.getOrElse(i) { "$id.jpg" },
                    mime = mimes.getOrNull(i)?.ifBlank { guess(names.getOrNull(i) ?: "") } ?: guess(names.getOrNull(i) ?: ""),
                    takenAt = taken.getOrNull(i)?.takeIf { it > 0 },
                    isVideo = videos?.getOrNull(i) ?: (mimes.getOrNull(i)?.startsWith("video") == true),
                    relDir = dirs.getOrNull(i)?.takeIf { it.isNotBlank() },
                    album = albums.getOrNull(i)?.takeIf { it.isNotBlank() },
                    size = sizes.getOrNull(i) ?: 0L,
                )
            }
        }

        // 本地已有索引，用来判断"这张是不是已经在手机里了"。
        // 选「留两份」的话根本不需要这个索引，跳过查询省一次全库扫描
        val local: Map<String, Long> = if (cfg.restoreConflict == "duplicate") {
            emptyMap()
        } else {
            runCatching { media.localIndex() }.getOrDefault(emptyMap())
        }

        totalBytes = jobs.sumOf { it.size.coerceAtLeast(0) }
        curCount = jobs.size
        doneBytes = 0
        lastSampleMs = 0
        speed = 0.0

        var restored = 0
        var skipped = 0
        var failed = 0
        var stopped = false

        coroutineScope {
            // 单独一个定时器刷进度：大视频下一张就要几十秒，只在文件边界刷的话
            // 进度条会僵在原地，速度也没法算
            val ticker = launch {
                while (isActive) {
                    delay(REPORT_MS)
                    runCatching { report() }
                }
            }
            try {
                for ((i, job) in jobs.withIndex()) {
                    if (isStopped) { stopped = true; break }
                    curIndex = i + 1
                    curName = job.name
                    report()

                    val existing = local["${job.name}|${job.size}"]?.let { media.uriOf(it, job.isVideo) }
                    val beforeBytes = doneBytes
                    val outcome = runCatching {
                        api.withOriginal(job.id) { raw ->
                            // 边拷边数：进度和速度都靠这个
                            val counting = object : FilterInputStream(raw) {
                                override fun read(): Int {
                                    val v = super.read()
                                    if (v >= 0) doneBytes += 1
                                    return v
                                }

                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    val n = super.read(b, off, len)
                                    if (n > 0) doneBytes += n
                                    return n
                                }
                            }
                            MediaStoreWriter.save(
                                ctx, counting, job.name, job.mime, job.takenAt, job.isVideo,
                                job.relDir, job.album, cfg.restoreConflict, existing,
                            )
                        }
                    }.getOrDefault(MediaStoreWriter.SaveOutcome.FAILED)

                    when (outcome) {
                        MediaStoreWriter.SaveOutcome.SKIPPED -> {
                            // 没下载，但这条也算走完了 —— 不加的话百分比会停在原地
                            doneBytes = beforeBytes + job.size.coerceAtLeast(0)
                            skipped++
                        }
                        MediaStoreWriter.SaveOutcome.FAILED -> failed++
                        else -> restored++
                    }
                    report()
                }
            } finally {
                ticker.cancel()
            }
        }

        if (stopped) {
            Notifications.cancel(ctx, Notifications.ID_RESTORE)
            return@withContext Result.success()
        }

        Notifications.cancel(ctx, Notifications.ID_RESTORE)
        Notifications.done(
            ctx,
            "还原完成",
            buildString {
                append("已还原 $restored 个")
                if (skipped > 0) append("，跳过 $skipped 个手机里已有")
                if (failed > 0) append("，失败 $failed 个")
            },
        )

        val out = workDataOf(KEY_RESTORED to restored, KEY_SKIPPED to skipped, KEY_FAILED to failed)
        if (failed > 0 && runAttemptCount < 2) Result.retry() else Result.success(out)
    }

    private fun guess(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> "image/jpeg"
    }
}

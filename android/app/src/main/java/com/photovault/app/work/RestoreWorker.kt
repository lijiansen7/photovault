package com.photovault.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photovault.app.data.MediaRepository
import com.photovault.app.data.NasApi
import com.photovault.app.data.SettingsRepo
import com.photovault.app.util.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 NAS 上的照片还原回本机相册。
 *
 * 入参：
 *   - [KEY_IDS]/[KEY_NAMES]/[KEY_MIMES]/[KEY_TAKEN]/[KEY_VIDEO]  -> 还原指定照片
 *   - [KEY_ALL] = true                                          -> 一键还原全部
 */
class RestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
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
    }

    private val ctx get() = applicationContext

    /** 同 UploadWorker：后台时退回普通通知，避免 Android 12+ 的 FGS 启动限制杀进程。 */
    private suspend fun progress(text: String, cur: Int, total: Int) {
        val n = Notifications.build(ctx, Notifications.CH_RESTORE, "正在还原到本地相册", text, cur to total)
        if (!Notifications.appInForeground(ctx)) {
            Notifications.notify(ctx, Notifications.ID_RESTORE, n)
            return
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
            progress("正在拉取 NAS 照片清单…", 0, 0)
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

        var restored = 0
        var skipped = 0
        var failed = 0

        jobs.forEachIndexed { i, job ->
            if (isStopped) return@withContext Result.success()
            if (i % 5 == 0 || i == jobs.lastIndex) {
                progress("${job.name}（${i + 1}/${jobs.size}）", i + 1, jobs.size)
            }
            val existing = local["${job.name}|${job.size}"]?.let { media.uriOf(it, job.isVideo) }
            val outcome = runCatching {
                api.withOriginal(job.id) { stream ->
                    MediaStoreWriter.save(
                        ctx, stream, job.name, job.mime, job.takenAt, job.isVideo,
                        job.relDir, job.album, cfg.restoreConflict, existing,
                    )
                }
            }.getOrDefault(MediaStoreWriter.SaveOutcome.FAILED)

            when (outcome) {
                MediaStoreWriter.SaveOutcome.SKIPPED -> skipped++
                MediaStoreWriter.SaveOutcome.FAILED -> failed++
                else -> restored++
            }
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

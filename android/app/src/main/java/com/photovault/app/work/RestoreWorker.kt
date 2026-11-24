package com.photovault.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photovault.app.data.AuthException
import com.photovault.app.data.MediaRepository
import com.photovault.app.data.NasApi
import com.photovault.app.data.SettingsRepo
import com.photovault.app.util.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 NAS 上的照片还原回本机相册。
 *
 * 入参：
 *   - [KEY_IDS]/[KEY_NAMES]/[KEY_MIMES]/[KEY_TAKEN]/[KEY_VIDEO]  -> 还原指定照片
 *   - [KEY_ALL] = true                                          -> 一键还原全部
 */
class RestoreWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "PhotoVault/Restore"
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
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_TEXT = "text"
        /** 所有还原任务共用的 tag，便于观察进度和整体取消。 */
        const val WORK_TAG = "photovault_restore"

        /** 并行下载数：网络往返占大头，3 路足够又不至于把 NAS 打满。 */
        private const val CONCURRENCY = 3
    }

    private val ctx get() = applicationContext

    /** 同 UploadWorker：后台时退回普通通知，避免 Android 12+ 的 FGS 启动限制杀进程。 */
    private suspend fun progress(text: String, cur: Int, total: Int) {
        setProgress(
            workDataOf(
                KEY_DONE to cur,
                KEY_TOTAL to total,
                KEY_TEXT to text,
            )
        )
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
        val relDir: String?,
        val album: String?,
        val size: Long,
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = SettingsRepo(ctx)
        var cfg = repo.current()
        if (!cfg.configured) return@withContext Result.failure()
        var api = NasApi(cfg)
        try {
            api.ping()
        } catch (e: AuthException) {
            val fresh = if (cfg.username.isNotBlank() && cfg.savedPassword.isNotBlank()) {
                runCatching {
                    val r = NasApi.loginDual(cfg, cfg.username, cfg.savedPassword)
                    repo.saveLogin(r, cfg.savedPassword)
                    repo.current()
                }.getOrNull()
            } else null
            if (fresh == null) {
                Notifications.done(ctx, "还原失败", "登录已失效，请到「设置」里重新登录")
                return@withContext Result.failure()
            }
            cfg = fresh
            api = NasApi(cfg)
        } catch (e: Exception) {
            Log.w(TAG, "ping failed", e)
            return@withContext Result.retry()
        }

        val media = MediaRepository(ctx)
        val deviceId = repo.deviceId()

        val jobs: List<Job> = if (inputData.getBoolean(KEY_ALL, false)) {
            progress("正在拉取 NAS 照片清单…", 0, 0)
            buildList {
                var offset = 0
                while (true) {
                    // viewer_device：这台设备上已删除的照片不参与一键还原
                    val (items, total) = api.list(offset, 500, viewerDevice = deviceId)
                    if (items.isEmpty()) break
                    items.forEach { p ->
                        add(Job(p.id, p.filename, p.mime.ifBlank { guess(p.filename) }, p.takenAt,
                                p.isVideo, p.relDir, p.album, p.size))
                    }
                    offset += items.size
                    // offset>0 时服务端不再重复 COUNT，total 可能是 0，
                    // 所以以“本页是否满 500”为准，不能只看 total
                    if (items.size < 500) break
                    if (total > 0 && offset >= total) break
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

        val restored = AtomicInteger()
        val skipped = AtomicInteger()
        val failed = AtomicInteger()
        val done = AtomicInteger()
        val gate = Semaphore(CONCURRENCY)

        coroutineScope {
            jobs.mapIndexed { _, job ->
                async {
                    if (isStopped) return@async
                    gate.withPermit {
                        if (!isStopped) {
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
                                MediaStoreWriter.SaveOutcome.SKIPPED -> skipped.incrementAndGet()
                                MediaStoreWriter.SaveOutcome.FAILED -> {
                                    failed.incrementAndGet()
                                    Log.w(TAG, "restore failed: ${job.name}")
                                }
                                else -> restored.incrementAndGet()
                            }
                            val n = done.incrementAndGet()
                            if (n % 5 == 0 || n == jobs.size) {
                                progress("${job.name}（$n/${jobs.size}）", n, jobs.size)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        Notifications.cancel(ctx, Notifications.ID_RESTORE)
        if (isStopped) return@withContext Result.failure()
        Notifications.done(
            ctx,
            "还原完成",
            buildString {
                append("已还原 ${restored.get()} 个")
                if (skipped.get() > 0) append("，跳过 ${skipped.get()} 个手机里已有")
                if (failed.get() > 0) append("，失败 ${failed.get()} 个")
            },
            Notifications.CH_RESTORE,
        )

        val out = workDataOf(
            KEY_RESTORED to restored.get(),
            KEY_SKIPPED to skipped.get(),
            KEY_FAILED to failed.get(),
        )
        if (failed.get() > 0 && runAttemptCount < 2) Result.retry() else Result.success(out)
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

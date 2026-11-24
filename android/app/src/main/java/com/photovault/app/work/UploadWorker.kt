package com.photovault.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photovault.app.data.AuthException
import com.photovault.app.data.FailureStore
import com.photovault.app.data.LocalMedia
import com.photovault.app.data.MediaRepository
import com.photovault.app.data.NasApi
import com.photovault.app.data.NasConfig
import com.photovault.app.data.SettingsRepo
import com.photovault.app.data.UploadFailure
import com.photovault.app.data.UploadManifest
import com.photovault.app.ui.Perms
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
        private const val TAG = "PhotoVault/Upload"
        const val KEY_DONE = "done"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_TOTAL = "total"
        const val KEY_TEXT = "text"
        const val KEY_DELETED = "deleted"
        /** 只重试这几个 MediaStore id（失败清单里的「重试」） */
        const val KEY_ONLY_IDS = "only_ids"
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

    /**
     * 手机本地已删除的照片 → NAS 回收站。
     *
     * 只动这台设备备份过、且本机签名（文件名|大小）已经不在清单里的照片。
     * 本机相册读不到 / 只授权了部分照片 / 缺失比例异常时整轮跳过，宁可少删。
     */
    private suspend fun syncDeletions(cfg: NasConfig, api: NasApi, deviceId: String): Int {
        // 用用户名做清单键：老版本升级上来时 userId 可能还没存过，但用户名一定有
        val userKey = cfg.username.ifBlank { cfg.userId }
        if (userKey.isBlank() || deviceId.isBlank()) return 0
        if (!Perms.hasFullMediaAccess(ctx)) {
            Log.i(TAG, "skip delete sync: partial media access")
            return 0
        }
        val manifest = UploadManifest(ctx)
        try {
            var entries = manifest.entries(userKey)
            if (entries.isEmpty()) {
                // 首次开启：用 NAS 上“这台设备上传过”的照片建初始清单
                entries = seedManifest(manifest, api, userKey, deviceId)
            }
            if (entries.isEmpty()) return 0

            val local = runCatching { MediaRepository(ctx).localIndex() }.getOrNull()
            if (local == null) {
                Log.w(TAG, "skip delete sync: local index failed")
                return 0
            }
            val missing = entries.filter { it.localKey !in local }
            if (missing.isEmpty()) return 0

            // 误删保护：相册整体读空，或一次少了超过 80%（且数量不小）就跳过。
            // 典型场景：SD 卡没挂载、云相册还没同步下来、Android 14 只授权了部分照片。
            if (local.isEmpty() || (entries.size >= 20 && missing.size * 5 > entries.size * 4)) {
                Log.w(TAG, "skip delete sync: local=${local.size} manifest=${entries.size} missing=${missing.size}")
                Notifications.done(
                    ctx, "已跳过删除同步",
                    "本机相册读取不完整（SD 卡未挂载或只授权了部分照片），没有删除 NAS 上的文件"
                )
                return 0
            }

            var total = 0
            missing.map { it.photoId }.chunked(500).forEach { chunk ->
                total += api.forget(chunk, deviceId)
                // 只为服务端确实处理过的这一批移除清单，避免超过 1000 条时漏删
                manifest.forget(userKey, chunk)
            }
            if (total > 0) Log.i(TAG, "delete sync: trashed $total on NAS")
            return total
        } finally {
            manifest.close()
        }
    }

    private suspend fun seedManifest(
        manifest: UploadManifest,
        api: NasApi,
        userKey: String,
        deviceId: String,
    ): List<UploadManifest.Entry> {
        var offset = 0
        while (true) {
            val (items, total) = api.list(offset, 500, viewerDevice = deviceId)
            if (items.isEmpty()) break
            items.filter { it.deviceId == deviceId }.forEach {
                manifest.remember(userKey, it.id, "${it.filename}|${it.size}")
            }
            offset += items.size
            if (items.size < 500) break
            if (total > 0 && offset >= total) break
        }
        return manifest.entries(userKey)
    }

    /** token 过期 + 用户勾了「记住密码」时，静默换一个新 token。 */
    private suspend fun reauth(repo: SettingsRepo, cfg: NasConfig): NasConfig? {
        if (cfg.username.isBlank() || cfg.savedPassword.isBlank()) return null
        return runCatching {
            val r = NasApi.loginDual(cfg, cfg.username, cfg.savedPassword)
            repo.saveLogin(r, cfg.savedPassword)
            Log.i(TAG, "token expired, re-login ok")
            repo.current()
        }.getOrNull()
    }

    /** 只重试指定的几张：失败清单里点「重试」走这里，不动全局同步水位线。 */
    private suspend fun retryOnly(
        ids: List<Long>,
        cfg: NasConfig,
        api: NasApi,
        deviceId: String,
    ): Result {
        val manifest = UploadManifest(ctx)
        val left = FailureStore.load(ctx).toMutableList()
        var ok = 0
        try {
            val byId = MediaRepository(ctx).byIds(ids).associateBy { it.id }
            progress("正在重试 ${ids.size} 张…", 0, ids.size)
            ids.forEachIndexed { i, id ->
                if (isStopped) return Result.success()
                val m = byId[id]
                if (m == null) {
                    left.removeAll { it.mediaId == id }
                    left += UploadFailure(
                        id, "#$id", "手机上找不到这个文件了（可能已删除）", System.currentTimeMillis()
                    )
                    return@forEachIndexed
                }
                m.applyExif(readExif(ctx, m.uri))
                runCatching { api.upload(ctx, m, deviceId) }
                    .onSuccess {
                        ok++
                        left.removeAll { f -> f.mediaId == id }
                        if (it.id.isNotBlank()) {
                            manifest.remember(
                                cfg.username.ifBlank { cfg.userId },
                                it.id, "${m.displayName}|${m.size}"
                            )
                        }
                    }
                    .onFailure { e ->
                        left.removeAll { f -> f.mediaId == id }
                        left += UploadFailure(
                            id, m.displayName,
                            com.photovault.app.util.Errors.humanize(e),
                            System.currentTimeMillis()
                        )
                        Log.w(TAG, "retry failed: ${m.displayName}", e)
                    }
                progress("正在重试 ${i + 1}/${ids.size}", i + 1, ids.size)
            }
        } finally {
            manifest.close()
        }
        FailureStore.save(ctx, left)
        Notifications.cancel(ctx, Notifications.ID_SYNC)
        Notifications.done(
            ctx,
            if (ok == ids.size) "重试成功" else "重试完成",
            "成功 $ok 张" + if (ok < ids.size) "，还有 ${ids.size - ok} 张没成功" else ""
        )
        return Result.success(workDataOf(KEY_DONE to ok, KEY_FAILED to (ids.size - ok)))
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = SettingsRepo(ctx)
        var cfg = repo.current()
        if (!cfg.configured) return@withContext Result.success()

        var api = NasApi(cfg)
        try {
            api.ping()
        } catch (e: AuthException) {
            val fresh = reauth(repo, cfg)
            if (fresh != null) {
                cfg = fresh
                api = NasApi(cfg)
            } else {
                Notifications.done(ctx, "备份失败", "登录已失效，请到「设置」里重新登录")
                return@withContext Result.failure()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ping failed", e)
            return@withContext Result.retry()
        }

        val media = MediaRepository(ctx)
        val deviceId = repo.deviceId()

        // 单张/几张重试：跳过水位线、只处理指定 id，不动全局同步进度
        val onlyIds = inputData.getLongArray(KEY_ONLY_IDS)
        if (onlyIds != null && onlyIds.isNotEmpty()) {
            return@withContext retryOnly(onlyIds.toList(), cfg, api, deviceId)
        }

        // 先把"手机里已经删掉的照片"同步到 NAS 回收站；失败不影响本次备份
        val deleted = if (cfg.syncDeletions) {
            runCatching { syncDeletions(cfg, api, deviceId) }
                .onFailure { Log.w(TAG, "delete sync failed", it) }
                .getOrDefault(0)
        } else 0

        progress("正在扫描本机相册…")

        // 用户勾了哪些相册就只传哪些；null 表示还没挑过 → 全部
        val picked = cfg.selectedAlbums
        val all = media.querySince(cfg.lastSyncMs, cfg.includeVideo)
            .filter { it.size > 0 }
            .filter { picked == null || (it.bucket ?: "") in picked }

        // 单次上限。边界往外扩：与截断点同一秒的也带上，避免水位线越过它们。
        val capped = all.take(MAX_PER_RUN)
        val candidates = if (capped.isEmpty()) emptyList() else {
            val boundary = capped.last().dateAddedSec
            all.takeWhile { it.dateAddedSec <= boundary }
        }

        if (candidates.isEmpty()) {
            // 没有新照片，但上一轮还有失败的：自动补一次。
            // 失败项不会重新进入 candidates（水位线已经推过去了），只靠用户手动点
            // 「重试」的话，那些视频就会永远躺在失败清单里。
            val stale = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val pending = runCatching {
                FailureStore.load(ctx).filter { it.at >= stale }.map { it.mediaId }
            }.getOrDefault(emptyList())
            if (pending.isNotEmpty() && runAttemptCount < 2) {
                Log.i(TAG, "no new media, auto-retry ${pending.size} failed items")
                return@withContext retryOnly(pending, cfg, api, deviceId)
            }
            Notifications.cancel(ctx, Notifications.ID_SYNC)
            if (deleted > 0) {
                Notifications.done(ctx, "同步完成", "已把 $deleted 张手机删除的照片移入 NAS 回收站")
            }
            return@withContext Result.success(
                workDataOf(KEY_DONE to 0, KEY_SKIPPED to 0, KEY_FAILED to 0, KEY_DELETED to deleted)
            )
        }

        // 1) 粗筛：key 用 MediaStore 的 dateTaken，和上传 meta 的 taken_at 同源
        val coarse = runCatching {
            api.prescreen(candidates.map {
                NasApi.PrescreenItem(it.id.toString(), it.size, it.dateTaken, it.displayName)
            })
        }.getOrDefault(emptySet())

        val needInspect = candidates.filter { it.id.toString() !in coarse }

        // 2) 只有粗筛没命中的才读文件算指纹（顺便读 EXIF 的时间/GPS）
        val fingerprinted = mutableListOf<Pair<LocalMedia, String>>()
        val hashFailed = mutableListOf<LocalMedia>()
        // 失败明细：文件名 + 原因，用户能点开看、能单张重试
        val failures = java.util.Collections.synchronizedList(mutableListOf<UploadFailure>())
        needInspect.forEachIndexed { i, m ->
            if (isStopped) return@withContext Result.success()
            m.applyExif(readExif(ctx, m.uri))
            val sha = sha256Of(ctx, m.uri)
            if (sha.isNullOrBlank()) {
                // 读不出来（云同步未下载 / 权限 / IO 错）绝不能算「跳过」，
                // 否则水位线一推进，这张照片就永远不会再被尝试。
                hashFailed += m
                failures += UploadFailure(
                    m.id, m.displayName,
                    "读不到文件内容（可能还在云相册里没下载下来，或没有读取权限）",
                    System.currentTimeMillis()
                )
                Log.w(TAG, "hash failed: ${m.displayName}")
            } else {
                fingerprinted += m to sha
            }
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
        val skippedBase = candidates.size - todo.size - hashFailed.size

        // 4) 并发上传：网络往返占大头，两路并行能把总时间压掉近一半
        val uploaded = AtomicInteger()
        val duplicated = AtomicInteger()
        val failed = AtomicInteger()
        val done = AtomicInteger()
        val gate = Semaphore(UPLOAD_CONCURRENCY)

        if (todo.isNotEmpty()) {
            val manifest = UploadManifest(ctx)
            try {
                coroutineScope {
                    todo.map { (m, _) ->
                        async {
                            if (isStopped) return@async
                            gate.withPermit {
                                val result = runCatching { api.upload(ctx, m, deviceId) }
                                result.onSuccess {
                                    if (it.duplicate) duplicated.incrementAndGet() else uploaded.incrementAndGet()
                                    // 记下 NAS id + 本地签名，之后手机删除时才知道该删哪条
                                    if (it.id.isNotBlank()) {
                                        manifest.remember(
                                            cfg.username.ifBlank { cfg.userId },
                                            it.id, "${m.displayName}|${m.size}"
                                        )
                                    }
                                }.onFailure {
                                    failed.incrementAndGet()
                                    failures += UploadFailure(
                                        m.id, m.displayName,
                                        com.photovault.app.util.Errors.humanize(it),
                                        System.currentTimeMillis()
                                    )
                                    Log.w(TAG, "upload failed: ${m.displayName}", it)
                                }
                                val n = done.incrementAndGet()
                                if (n % 10 == 0 || n == todo.size) {
                                    progress("正在备份 $n/${todo.size}", n, todo.size)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                manifest.close()
            }
        }

        val okCount = uploaded.get()
        val dupCount = duplicated.get()
        val failCount = failed.get() + hashFailed.size
        val skipped = skippedBase + dupCount

        // 失败明细落盘：本轮处理过的从旧清单里剔除，本轮失败的合并进去
        runCatching {
            val touched = todo.map { it.first.id }.toSet()
            val prev = FailureStore.load(ctx).filter { it.mediaId !in touched }
            if (failures.isEmpty()) FailureStore.clear(ctx)
            else FailureStore.save(ctx, failures + prev)
        }

        // 5) 水位推进：本批只要有进展就推过去。
        //    以前是「全部成功才推进」，结果库里有 2000+ 张、其中一张视频上传 500 时，
        //    水位线永远停在第一批，每次重试都从头扫描、重新算一遍指纹 ——
        //    表现就是「备份到 800 多就一直显示正在扫描」。失败的已经落到失败清单，
        //    下一次同步会单独补传那几张（见上面 candidates 为空时的 auto-retry）。
        //    只有「一张都没成功」才不推进，留给 Result.retry() 整批重来。
        val batchMadeProgress = failCount == 0 || (okCount + dupCount) > 0
        if (batchMadeProgress) {
            val newest = candidates.maxOfOrNull { it.dateAddedSec }?.times(1000)
                ?: System.currentTimeMillis()
            repo.markSynced(maxOf(cfg.lastSyncMs, newest))
        }

        Notifications.cancel(ctx, Notifications.ID_SYNC)
        Notifications.done(
            ctx,
            if (failCount > 0) "备份未完成" else "备份完成",
            "新增 $okCount 张，跳过 $skipped 张已备份，失败 $failCount 张" +
                if (deleted > 0) "，NAS 回收站 +$deleted" else ""
        )

        val out = workDataOf(
            KEY_DONE to okCount,
            KEY_SKIPPED to skipped,
            KEY_FAILED to failCount,
            KEY_DELETED to deleted,
        )

        // 这一批打满了单次上限就接续下一批 —— **不看有没有失败**。
        // 原来要求 failCount == 0 才接续，于是「2000+ 张里有一张传不上去」
        // 就等于后面一千多张永远排不到。
        if (candidates.size >= MAX_PER_RUN) {
            runCatching { UploadScheduler.runOnceChained(applicationContext) }
        }

        // 一张都没成功 = 整体不通（地址错 / 服务端挂 / 网络断），整批重来一次值得；
        // 部分失败只进失败清单，不重跑整批，免得几千张反复扫描
        if (okCount == 0 && dupCount == 0 && failCount > 0 && runAttemptCount < 2) {
            Result.retry()
        } else Result.success(out)
    }
}

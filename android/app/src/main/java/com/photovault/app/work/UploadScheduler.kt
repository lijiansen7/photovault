package com.photovault.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.photovault.app.data.NasConfig
import com.photovault.app.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object UploadScheduler {

    private const val PERIODIC = "photovault_periodic_upload"
    private const val ONCE = "photovault_upload_once"

    fun ensurePeriodic(ctx: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { apply(ctx, SettingsRepo(ctx).current()) }
        }
    }

    /** 设置变更后重新下发周期任务。开关没开就撤掉，不会偷偷传。 */
    fun apply(ctx: Context, cfg: NasConfig) {
        val wm = WorkManager.getInstance(ctx)
        if (!cfg.configured || !cfg.backupEnabled) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cfg.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(cfg.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<UploadWorker>(cfg.periodMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** 立即同步一次（不等周期，但仍受 WiFi/充电约束）。 */
    fun runOnce(ctx: Context) {
        val cfg = runCatching { kotlinx.coroutines.runBlocking { SettingsRepo(ctx).current() } }.getOrNull()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cfg?.wifiOnly != false) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(cfg?.chargingOnly == true)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).build()
        )
    }

    /** 强制同步一次，忽略 WiFi/充电约束（用户点了"立即备份"就想要立刻跑）。 */
    fun runOnceForced(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    /** 取消正在跑的「立即同步」。
     *
     * 只影响还没传的照片：已经上传成功的那几张在服务端已经是独立记录，
     * 取消不会撤回它们；同步水位线也不会推进（worker 被 stop 时不会 markSynced），
     * 所以下一轮会重新接着传剩下的。
     */
    fun cancelOnce(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(ONCE)
    }

    /** 接续下一批：挂到同一条 unique 链上，上一批跑完自动开下一批。
     *
     * worker 自己 enqueue 自己时**不能用 REPLACE**——会把正在收尾的自己取消掉，
     * 必须用 APPEND_OR_REPLACE 追加。
     */
    fun runOnceChained(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }
}

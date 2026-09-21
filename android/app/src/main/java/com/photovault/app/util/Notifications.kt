package com.photovault.app.util

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.photovault.app.R

object Notifications {
    const val CH_SYNC = "photovault_sync"
    const val CH_RESTORE = "photovault_restore"
    const val ID_SYNC = 1001
    const val ID_RESTORE = 1002

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(CH_SYNC, ctx.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_RESTORE, ctx.getString(R.string.channel_restore), NotificationManager.IMPORTANCE_LOW),
        ).forEach { nm.createNotificationChannel(it) }
    }

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun build(
        ctx: Context,
        channel: String,
        title: String,
        text: String,
        progress: Pair<Int, Int>? = null,
        indeterminate: Boolean = false,
    ) = NotificationCompat.Builder(ctx, channel).apply {
        setSmallIcon(R.drawable.ic_cloud)
        setContentTitle(title)
        setContentText(text)
        setOngoing(true)
        setOnlyAlertOnce(true)
        priority = NotificationCompat.PRIORITY_LOW
        if (progress != null) {
            setProgress(progress.second, progress.first, indeterminate)
        }
    }.build()

    fun notify(ctx: Context, id: Int, n: android.app.Notification) {
        if (!canPost(ctx)) return
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun cancel(ctx: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
    }

    /**
     * Android 12 起禁止从后台启动前台服务，周期备份恰好是在后台被拉起的。
     * 返回 false 时 Worker 必须退回普通通知，否则 startForeground 会直接杀进程。
     */
    fun appInForeground(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * 前台任务用的 ForegroundInfo。
     *
     * Android 14（targetSdk 34）起，前台服务必须显式声明类型，否则
     * `startForeground` 会抛 InvalidForegroundServiceTypeException 直接杀进程。
     * 这里统一带上 dataSync —— 与 Manifest 里 SystemForegroundService 的声明一致。
     */
    fun foregroundInfo(id: Int, n: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, n)
        }

    /** 一次性结果通知（可滑掉）。 */
    fun done(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_SYNC)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        notify(ctx, ID_SYNC + 1, n)
    }
}

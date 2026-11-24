package com.photovault.app.ui

import android.Manifest
import android.os.Build

object Perms {

    fun required(): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            out += Manifest.permission.READ_MEDIA_IMAGES
            out += Manifest.permission.READ_MEDIA_VIDEO
            out += Manifest.permission.POST_NOTIFICATIONS
        } else {
            out += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // Android 14 起允许"仅选择部分照片"；声明后才能拿到用户选中的那批
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            out += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        // 读取照片 EXIF 内 GPS 的关键权限（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            out += Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        return out
    }

    /** 缺了就没法备份的核心权限（位置/通知/部分选择缺失不阻断备份）。 */
    fun blocking(): List<String> = required().filter {
        it != Manifest.permission.ACCESS_MEDIA_LOCATION &&
            it != Manifest.permission.POST_NOTIFICATIONS &&
            it != Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    }

    /** 完全授权，或 Android 14 的"部分照片"授权，都算能备份。 */
    fun hasMediaAccess(context: android.content.Context): Boolean {
        val full = blocking().all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (full) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    /** 只有完整相册权限才允许做"手机删除同步"，部分授权会误判成照片消失。 */
    fun hasFullMediaAccess(context: android.content.Context): Boolean = blocking().all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

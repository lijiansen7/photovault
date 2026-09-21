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
        // 读取照片 EXIF 内 GPS 的关键权限（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            out += Manifest.permission.ACCESS_MEDIA_LOCATION
        }
        return out
    }

    /** 缺了就没法备份的核心权限（位置权限缺失只影响 GPS，不阻断备份）。 */
    fun blocking(): List<String> = required().filter {
        it != Manifest.permission.ACCESS_MEDIA_LOCATION && it != Manifest.permission.POST_NOTIFICATIONS
    }
}

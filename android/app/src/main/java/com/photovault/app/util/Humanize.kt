package com.photovault.app.util

import java.util.Locale

/**
 * 人读的字节数 / 速度 / 剩余时间。
 *
 * 还原进度条要用到：进度按字节算（照片大小差别很大，按张数算的进度条会一顿一顿），
 * 速度按字节/秒算，剩下的时间拿剩余字节除以速度估。
 */
object Humanize {

    fun bytes(v: Long): String = when {
        v >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", v / 1073741824.0)
        v >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", v / 1048576.0)
        v >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", v / 1024.0)
        else -> "$v B"
    }

    fun speed(bytesPerSec: Double): String =
        if (bytesPerSec < 1.0) "0 B/s" else bytes(bytesPerSec.toLong()) + "/s"

    /** 剩余时间；速度还没测出来（前几百毫秒）或已经下完时返回 null。 */
    fun eta(remainingBytes: Long, bytesPerSec: Double): String? {
        if (remainingBytes <= 0 || bytesPerSec < 1024) return null
        val s = (remainingBytes / bytesPerSec).toLong().coerceAtLeast(1)
        return when {
            s < 60 -> "还剩 $s 秒"
            s < 3600 -> "还剩 ${s / 60} 分 ${s % 60} 秒"
            else -> "还剩 ${s / 3600} 小时 ${(s % 3600) / 60} 分"
        }
    }
}

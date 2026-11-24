package com.photovault.app.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ExifInfo(
    val takenAt: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val alt: Double? = null,
    val make: String? = null,
    val model: String? = null,
    val orientation: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * EXIF 的 DateTimeOriginal 是**拍摄地本地墙钟时间，没有时区**。
 *
 * 以前这里固定用 UTC 解析，东八区会整整偏 8 小时，还原时还会把这个错误值
 * 写进 MediaStore.DATE_TAKEN。优先用 OffsetTimeOriginal；没有就按手机当前时区。
 */
private fun parseExifDate(raw: String?, offset: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().replace('-', ':')
    val tz = offset?.takeIf { it.isNotBlank() }?.let { runCatching { TimeZone.getTimeZone("GMT$it") }.getOrNull() }
        ?: TimeZone.getDefault()
    return try {
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            .apply { timeZone = tz }
            .parse(normalized)?.time
    } catch (e: Exception) {
        null
    }
}

/**
 * 从照片自身的 EXIF 里读拍摄时间与 GPS。
 *
 * Android 10+ 起 MediaStore 会把照片里的 GPS 抹掉（除非声明并授予
 * ACCESS_MEDIA_LOCATION），所以必须走 ExifInterface 直读文件描述符才拿得到。
 */
fun readExif(context: Context, uri: Uri): ExifInfo = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        val taken = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), offset)
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED), offset)
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME), offset)
        val ll = exif.latLong
        val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            .takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 }
        val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            .takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 }
        ExifInfo(
            takenAt = taken,
            lat = ll?.getOrNull(0),
            lon = ll?.getOrNull(1),
            alt = exif.getAltitude(Double.NaN).takeIf { !it.isNaN() },
            make = exif.getAttribute(ExifInterface.TAG_MAKE),
            model = exif.getAttribute(ExifInterface.TAG_MODEL),
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                .takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
            width = w,
            height = h,
        )
    } ?: ExifInfo()
} catch (e: Exception) {
    ExifInfo()
}

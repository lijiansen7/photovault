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

private val EXIF_DATE by lazy {
    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

private fun parseExifDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().replace('-', ':')
    return try {
        EXIF_DATE.parse(normalized)?.time
    } catch (e: Exception) {
        null
    }
}

/**
 * 从照片自身的 EXIF 里读拍摄时间与 GPS。
 *
 * 关键点：Android 10+ 起 MediaStore 会把照片里的 GPS 抹掉（除非声明并授予
 * ACCESS_MEDIA_LOCATION），所以必须走 ExifInterface 直读文件描述符才拿得到。
 */
fun readExif(context: Context, uri: Uri): ExifInfo = try {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        val taken = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME))
        val ll = exif.latLong
        ExifInfo(
            takenAt = taken,
            lat = ll?.getOrNull(0),
            lon = ll?.getOrNull(1),
            alt = exif.getAltitude(Double.NaN).takeIf { !it.isNaN() },
            make = exif.getAttribute(ExifInterface.TAG_MAKE),
            model = exif.getAttribute(ExifInterface.TAG_MODEL),
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                .takeIf { it != ExifInterface.ORIENTATION_UNDEFINED },
            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 },
            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 },
        )
    } ?: ExifInfo()
} catch (e: Exception) {
    ExifInfo()
}

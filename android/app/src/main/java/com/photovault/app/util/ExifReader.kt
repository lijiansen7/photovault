package com.photovault.app.util

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.SimpleTimeZone
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

private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"

/**
 * EXIF 的 DateTimeOriginal 是**拍摄地墙上时间**，不带时区。
 *
 * 早期版本一律按 UTC 解析，导致上报给服务端、以及还原后写进 MediaStore 的
 * DATE_TAKEN 整体偏 8 小时（UTC+8）。按 DATE_TAKEN 排序的相册（PixelOS、
 * Google Photos）会把上午拍的显示成晚上。
 *
 * 现在：EXIF 自带 OffsetTimeOriginal 就用它（出国拍的照片也准），
 * 没有才退回手机当前时区。
 */
private fun parseExifOffset(raw: String?): TimeZone? {
    if (raw.isNullOrBlank()) return null
    val m = Regex("""\s*([+-])(\d{2}):?(\d{2})\s*""").find(raw) ?: return null
    val hh = m.groupValues[2].toIntOrNull() ?: return null
    val mm = m.groupValues[3].toIntOrNull() ?: return null
    if (hh > 14 || mm > 59) return null
    val sign = if (m.groupValues[1] == "-") -1 else 1
    return SimpleTimeZone(sign * (hh * 3_600_000 + mm * 60_000), "EXIF")
}

private fun parseExifDate(raw: String?, offset: String? = null): Long? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().replace('-', ':')
    val tz = parseExifOffset(offset) ?: TimeZone.getDefault()
    val fmt = SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US).apply { timeZone = tz }
    return try {
        fmt.parse(normalized)?.time
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
        // EXIF 3.0 的时区字段。多数手机不写，写了就以它为准。
        val offset = exif.getAttribute("OffsetTimeOriginal")
            ?: exif.getAttribute("OffsetTimeDigitized")
            ?: exif.getAttribute("OffsetTime")
        val taken = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), offset)
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED), offset)
            ?: parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME), offset)
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

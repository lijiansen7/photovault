package com.photovault.app.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.photovault.app.util.ExifInfo

/** 本机相册里的一张照片/一个视频。 */
data class LocalMedia(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val mime: String,
    val dateTaken: Long?,
    val dateAddedSec: Long,
    val bucket: String?,
    val isVideo: Boolean,
    /** 相册里的相对目录，如 DCIM/Camera。备份时记下来，还原时才能写回原处。 */
    val relDir: String? = null,
) {
    // 以下字段由 EXIF 补齐，上传时一起送给服务端
    var takenAt: Long? = dateTaken
    var gpsLat: Double? = null
    var gpsLon: Double? = null
    var gpsAlt: Double? = null
    var width: Int? = null
    var height: Int? = null
    var orientation: Int? = null
    var cameraMake: String? = null
    var cameraModel: String? = null

    fun applyExif(e: ExifInfo) {
        takenAt = e.takenAt ?: dateTaken
        gpsLat = e.lat
        gpsLon = e.lon
        gpsAlt = e.alt
        width = e.width
        height = e.height
        orientation = e.orientation
        cameraMake = e.make
        cameraModel = e.model
    }
}

/** 手机上的一个相册，用于让用户挑要备份哪些。 */
data class AlbumInfo(
    /** BUCKET_DISPLAY_NAME，如 Camera / DCIM / WeiXin */
    val name: String,
    /** 相对目录，如 DCIM/Camera，能区分同名目录 */
    val relDir: String?,
    val images: Int,
    val videos: Int,
) {
    val total: Int get() = images + videos
    /** DCIM 这种是手机相机的默认目录，通常最需要备份 */
    val isCameraLike: Boolean
        get() = name.equals("Camera", true) || name.equals("DCIM", true)
}

class MediaRepository(private val context: Context) {

    /**
     * 本机已有媒体的快速索引：key = `文件名|字节数`，value = MediaStore 的记录 id。
     *
     * 还原前用它判断"这张是不是已经在手机里了"。只查文件名和大小，
     * 不读文件内容，几千张也是一瞬间的事；同名的照片极少大小也完全相同，
     * 拿来做判定足够。
     */
    fun localIndex(): Map<String, Long> {
        val out = HashMap<String, Long>(4096)
        for (uri in listOf(imagesUri(), videosUri())) {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                ),
                null, null, null,
            )?.use { c ->
                val iId = c.safeIndex(MediaStore.MediaColumns._ID)
                val iName = c.safeIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.safeIndex(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val name = c.stringOrNull(iName) ?: continue
                    val size = c.longOrNull(iSize) ?: continue
                    val id = c.longOrNull(iId) ?: continue
                    out["$name|$size"] = id
                }
            }
        }
        return out
    }

    /** 按 id 拼出 uri，覆盖已有文件时用。 */
    fun uriOf(id: Long, isVideo: Boolean): Uri =
        android.content.ContentUris.withAppendedId(if (isVideo) videosUri() else imagesUri(), id)

    /** 列出手机上的所有相册及各自的照片/视频数量。 */
    fun listAlbums(): List<AlbumInfo> {
        val agg = LinkedHashMap<String, AlbumAcc>()
        collectAlbums(imagesUri(), false, agg)
        collectAlbums(videosUri(), true, agg)
        return agg.values
            .map { AlbumInfo(it.name, it.relDir, it.images, it.videos) }
            .sortedWith(compareByDescending<AlbumInfo> { it.isCameraLike }.thenByDescending { it.total })
    }

    private fun imagesUri(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun videosUri(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private class AlbumAcc(val name: String) {
        var relDir: String? = null
        var images = 0
        var videos = 0
    }

    private fun collectAlbums(collection: Uri, isVideo: Boolean, agg: MutableMap<String, AlbumAcc>) {
        val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val cols = mutableListOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        if (scoped) cols += MediaStore.MediaColumns.RELATIVE_PATH
        context.contentResolver.query(collection, cols.toTypedArray(), null, null, null)?.use { c ->
            val iBucket = c.safeIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iRel = if (scoped) c.safeIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                val name = c.stringOrNull(iBucket)?.takeIf { it.isNotBlank() } ?: continue
                val acc = agg.getOrPut(name) { AlbumAcc(name) }
                if (acc.relDir == null) acc.relDir = c.stringOrNull(iRel)?.trim('/')?.takeIf { it.isNotBlank() }
                if (isVideo) acc.videos++ else acc.images++
            }
        }
    }

    /** 查询本机相册中 [sinceMs] 之后新增的媒体。 */
    fun querySince(sinceMs: Long, includeVideo: Boolean): List<LocalMedia> {
        val out = mutableListOf<LocalMedia>()
        out += queryImages(sinceMs)
        if (includeVideo) out += queryVideos(sinceMs)
        return out.sortedBy { it.dateAddedSec }
    }

    /**
     * 本机相册文件数。[buckets] 非空时只统计勾选的相册 ——
     * 不然首页「本机相册文件」算的是全部相册、而备份只传勾选的那几个，
     * 两个数字口径不一致，用户会以为漏备份了（实际只是没勾）。
     * buckets 为空集表示"一个相册都没勾"→ 直接返回 0。
     */
    fun countAll(includeVideo: Boolean, buckets: Set<String>? = null): Int {
        var n = 0
        if (buckets != null && buckets.isEmpty()) return 0

        val sel: String?
        val args: Array<String>?
        if (buckets == null) {
            sel = null; args = null
        } else {
            sel = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} IN (" +
                buckets.joinToString(",") { "?" } + ")"
            args = buckets.toTypedArray()
        }

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID), sel, args, null
        )?.use { n += it.count }
        if (includeVideo) {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID), sel, args, null
            )?.use { n += it.count }
        }
        return n
    }

    private fun queryImages(sinceMs: Long): List<LocalMedia> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return query(collection, sinceMs, false)
    }

    private fun queryVideos(sinceMs: Long): List<LocalMedia> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return query(collection, sinceMs, true)
    }

    private fun query(collection: Uri, sinceMs: Long, isVideo: Boolean): List<LocalMedia> {
        val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            "datetaken",
        )
        // RELATIVE_PATH 只有 Android 10+ 有；它比 BUCKET_DISPLAY_NAME 更完整
        // （能把 DCIM/Camera 和 Pictures/Camera 区分开）
        if (scoped) cols += MediaStore.MediaColumns.RELATIVE_PATH
        val projection = cols.toTypedArray()

        val sinceSec = sinceMs / 1000
        val selection = if (sinceSec > 0) "${MediaStore.MediaColumns.DATE_ADDED} > ?" else null
        val args = if (sinceSec > 0) arrayOf(sinceSec.toString()) else null
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} ASC"

        val list = mutableListOf<LocalMedia>()
        context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val iId = c.safeIndex(MediaStore.MediaColumns._ID)
            val iName = c.safeIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.safeIndex(MediaStore.MediaColumns.SIZE)
            val iMime = c.safeIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iAdded = c.safeIndex(MediaStore.MediaColumns.DATE_ADDED)
            val iBucket = c.safeIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iTaken = c.safeIndex("datetaken")
            val iRel = if (scoped) c.safeIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                val id = c.longOrNull(iId) ?: return@use
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                list += LocalMedia(
                    id = id,
                    uri = uri,
                    displayName = c.stringOrNull(iName) ?: uri.lastPathSegment ?: "photo.jpg",
                    size = c.longOrNull(iSize) ?: 0L,
                    mime = c.stringOrNull(iMime) ?: "",
                    dateTaken = c.longOrNull(iTaken)?.takeIf { it > 0 },
                    dateAddedSec = c.longOrNull(iAdded) ?: 0L,
                    bucket = c.stringOrNull(iBucket),
                    isVideo = isVideo,
                    relDir = c.stringOrNull(iRel)?.trim('/')?.takeIf { it.isNotBlank() },
                )
            }
        }
        return list
    }
}

private fun Cursor.safeIndex(column: String): Int =
    try { getColumnIndexOrThrow(column) } catch (e: Exception) { -1 }

private fun Cursor.longOrNull(i: Int): Long? = if (i < 0 || isNull(i)) null else getLong(i)

private fun Cursor.stringOrNull(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)

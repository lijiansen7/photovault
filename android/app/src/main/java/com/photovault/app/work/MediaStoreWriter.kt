package com.photovault.app.work

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/**
 * 把 NAS 上的**原文件**写回系统相册。
 *
 * 落点优先还原到备份前的原目录（DCIM/Camera 就回 DCIM/Camera），
 * 这样相册分组和备份前一模一样；只有拿不到原始位置时才退回 PhotoVault 目录。
 *
 * 保留拍摄时间/地点的三道保险：
 *   1. 写的是原文件字节，EXIF 里的 DateTimeOriginal 与 GPS 原封不动；
 *   2. 同时把 DATE_TAKEN 写进 MediaStore，系统相册/Google Photos 排序用；
 *   3. 尽量把文件 mtime 调回拍摄时间（部分老相册 App 按 mtime 排序）。
 */
object MediaStoreWriter {

    const val FOLDER = "PhotoVault"

    /**
     * 决定还原到哪个目录（相对存储根，如 `DCIM/Camera`）。
     *
     * - 有备份时记下的 [relDir] 就用它，这是最准的；
     * - 老数据没这个字段，按相册名 [album] 推断一个常见位置；
     * - 都没有才落到 `Pictures/PhotoVault`。
     */
    fun resolveDir(relDir: String?, album: String?, isVideo: Boolean): String {
        val root = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        val clean = relDir
            ?.substringAfter("storage/emulated/0/", relDir)   // 有的 ROM 会给绝对路径
            ?.replace("..", "")
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
        if (clean != null) return clean

        val name = album?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
            ?: return "$root/$FOLDER"

        // BUCKET_DISPLAY_NAME 本身就是目录名，所以未知相册直接拿原样用，
        // 比硬编码映射准（比如微信的目录就叫 WeiXin，不叫 WeChat）
        return when (name.lowercase()) {
            "camera" -> "DCIM/Camera"
            "screenshots", "screenshot" -> "$root/Screenshots"
            "download", "downloads" -> Environment.DIRECTORY_DOWNLOADS
            "dcim" -> "DCIM"
            "pictures" -> Environment.DIRECTORY_PICTURES
            "movies", "video", "videos" -> Environment.DIRECTORY_MOVIES
            else -> "$root/$name"
        }
    }

    /** 一次写入的结果，调用方据此统计"还原了几个 / 跳过几个"。 */
    enum class SaveOutcome { CREATED, SKIPPED, DUPLICATED, OVERWRITTEN, FAILED }

    /**
     * 把一份原文件写回系统相册。
     *
     * @param conflict 本地已有同一张时的策略：`skip` / `duplicate` / `overwrite`
     * @param existing 本地已存在的那份的 uri；null 表示本地没有
     *
     * 关于 overwrite：Android 10+ 分区存储下 App **只能覆盖自己创建的文件**，
     * 系统相机、微信保存的照片会抛 SecurityException。这里捕获后自动降级成
     * "再存一份"，所以选了覆盖也不会丢数据。
     */
    fun save(
        context: Context,
        input: InputStream,
        filename: String,
        mime: String,
        takenAt: Long?,
        isVideo: Boolean,
        relDir: String? = null,
        album: String? = null,
        conflict: String = "skip",
        existing: Uri? = null,
    ): SaveOutcome {
        if (existing != null) {
            when (conflict) {
                "skip" -> return SaveOutcome.SKIPPED
                "overwrite" -> {
                    if (tryOverwrite(context, existing, input, mime, takenAt)) {
                        return SaveOutcome.OVERWRITTEN
                    }
                    // 覆盖不了（多数是别人 App 创建的文件），往下走存成新的一份
                }
            }
        }

        val dir = resolveDir(relDir, album, isVideo)
        val uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveScoped(context, input, filename, mime, takenAt, isVideo, dir)
            } else {
                saveLegacy(context, input, filename, mime, takenAt, isVideo, dir)
            }
        } catch (e: Exception) {
            null
        }
        return when {
            uri == null -> SaveOutcome.FAILED
            existing != null -> SaveOutcome.DUPLICATED
            else -> SaveOutcome.CREATED
        }
    }

    /** 覆盖本地已有文件。成功返回 true；没权限或失败返回 false（由调用方降级）。 */
    private fun tryOverwrite(
        context: Context,
        uri: Uri,
        input: InputStream,
        mime: String,
        takenAt: Long?,
    ): Boolean {
        // 用块体而不是表达式体：函数里要用 `?: return false`，
        // 表达式体（`= try {...}`）不允许出现 return
        return try {
            val resolver = context.contentResolver
            val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            if (scoped) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }, null, null)
            }
            // "wt" = 截断后写入，不然会在原文件末尾接着写
            resolver.openOutputStream(uri, "wt")?.use { out -> input.copyTo(out, 1024 * 1024) }
                ?: return false
            if (scoped) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (takenAt != null) put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
                }, null, null)
            }
            if (takenAt != null) runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { File(it).setLastModified(takenAt) }
                }
            }
            true
        } catch (e: Exception) {
            // 别人 App 创建的文件覆盖不了 —— 这不是错误，交给调用方走"再存一份"
            false
        }
    }

    // -------------------------------------------------- Android 10+ (分区存储)

    private fun saveScoped(
        context: Context,
        input: InputStream,
        filename: String,
        mime: String,
        takenAt: Long?,
        isVideo: Boolean,
        dir: String,
    ): Uri? {
        val resolver = context.contentResolver
        val collection = if (isVideo)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (takenAt != null) {
                put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
                put(MediaStore.MediaColumns.DATE_ADDED, takenAt / 1000)
            }
        }

        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out, 1024 * 1024) }
                ?: return null
        } finally {
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (takenAt != null) put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            }
            resolver.update(uri, done, null, null)
        }

        // 尽力把文件 mtime 调回拍摄时间（对自家插入的文件通常有效）
        if (takenAt != null) runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val path = c.getString(0)
                    if (!path.isNullOrBlank()) File(path).setLastModified(takenAt)
                }
            }
        }
        return uri
    }

    // ---------------------------------------------------- Android 9 及以下 ----

    private fun saveLegacy(
        context: Context,
        input: InputStream,
        filename: String,
        mime: String,
        takenAt: Long?,
        isVideo: Boolean,
        dir: String,
    ): Uri? {
        val root = Environment.getExternalStorageDirectory()
        val target_dir = File(root, dir).apply { mkdirs() }
        val target = uniqueFile(target_dir, filename)
        input.use { it.copyTo(target.outputStream(), 1024 * 1024) }
        target.setLastModified(takenAt ?: System.currentTimeMillis())
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            target.name.hashCode().toLong()
        )
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists() && i < 999) {
            f = File(dir, "${base}-$i" + (if (ext.isNotBlank()) ".$ext" else ""))
            i++
        }
        return f
    }
}

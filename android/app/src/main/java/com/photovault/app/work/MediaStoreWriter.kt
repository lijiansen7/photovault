package com.photovault.app.work

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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

    private const val TAG = "PhotoVault/MediaStore"
    const val FOLDER = "PhotoVault"
    private const val MAX_BACKUP_BYTES = 256L * 1024 * 1024

    /**
     * 决定还原到哪个目录（相对存储根，如 `DCIM/Camera`）。
     *
     * relDir 来自服务端索引（最初由客户端上报），不能无条件信任：
     * 去掉空段、`.`、`..`，避免一路写到奇怪的位置。
     */
    fun resolveDir(relDir: String?, album: String?, isVideo: Boolean): String {
        val root = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

        val clean = relDir
            ?.substringAfter("storage/emulated/0/", relDir)   // 有的 ROM 会给绝对路径
            ?.replace('\\', '/')
            ?.split('/')
            ?.filter { it.isNotBlank() && it != "." && it != ".." }
            ?.joinToString("/")
            ?.takeIf { it.isNotBlank() }
        if (clean != null) return clean

        val name = album?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
            ?: return "$root/$FOLDER"

        // BUCKET_DISPLAY_NAME 本身就是目录名，所以未知相册直接拿原样用
        return when (name.lowercase()) {
            "camera" -> "DCIM/Camera"
            "screenshots", "screenshot" -> "$root/Screenshots"
            "download", "downloads" -> Environment.DIRECTORY_DOWNLOADS
            "dcim" -> "DCIM"
            "pictures" -> Environment.DIRECTORY_PICTURES
            "movies", "video", "videos" -> Environment.DIRECTORY_MOVIES
            else -> "$root/${name.replace('/', '_')}"
        }
    }

    enum class SaveOutcome { CREATED, SKIPPED, DUPLICATED, OVERWRITTEN, FAILED }

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
                // 覆盖先把网络流完整落到缓存：覆盖失败时还能用同一份字节
                // 再存一份，不会因为流已经被读了一半而写出半个文件。
                "overwrite" -> return overwriteOrDuplicate(
                    context, input, filename, mime, takenAt, isVideo, relDir, album, existing
                )
            }
        }

        val uri = saveNew(context, input, filename, mime, takenAt, isVideo, relDir, album)
        return when {
            uri == null -> SaveOutcome.FAILED
            existing != null -> SaveOutcome.DUPLICATED
            else -> SaveOutcome.CREATED
        }
    }

    private fun saveNew(
        context: Context,
        input: InputStream,
        filename: String,
        mime: String,
        takenAt: Long?,
        isVideo: Boolean,
        relDir: String?,
        album: String?,
    ): Uri? = try {
        val dir = resolveDir(relDir, album, isVideo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(context, input, filename, mime, takenAt, isVideo, dir)
        } else {
            saveLegacy(context, input, filename, mime, takenAt, isVideo, dir)
        }
    } catch (e: Exception) {
        Log.w(TAG, "save failed: $filename", e)
        null
    }

    private fun overwriteOrDuplicate(
        context: Context,
        input: InputStream,
        filename: String,
        mime: String,
        takenAt: Long?,
        isVideo: Boolean,
        relDir: String?,
        album: String?,
        existing: Uri,
    ): SaveOutcome {
        val tmp = File(context.cacheDir, "restore-${System.nanoTime()}.tmp")
        return try {
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 1024 * 1024) } }
            if (tryOverwrite(context, existing, tmp.inputStream(), mime, takenAt)) {
                SaveOutcome.OVERWRITTEN
            } else {
                val uri = saveNew(context, tmp.inputStream(), filename, mime, takenAt, isVideo, relDir, album)
                if (uri != null) SaveOutcome.DUPLICATED else SaveOutcome.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "overwrite failed: $filename", e)
            SaveOutcome.FAILED
        } finally {
            tmp.delete()
        }
    }

    /**
     * 覆盖本地已有文件。
     *
     * 先把原文件备份到应用缓存，再截断写入；中途失败就尽力恢复。
     * 无论成功失败，**一定要把 IS_PENDING 复位**：否则这张照片会在相册里消失。
     */
    private fun tryOverwrite(
        context: Context,
        uri: Uri,
        input: InputStream,
        mime: String,
        takenAt: Long?,
    ): Boolean {
        val resolver = context.contentResolver
        val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var pendingSet = false
        var backup: File? = null
        return try {
            if (scoped) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }, null, null)
                pendingSet = true
            }
            backup = runCatching {
                val f = File(context.cacheDir, "overwrite-${System.nanoTime()}.bak")
                resolver.openInputStream(uri)?.use { src ->
                    f.outputStream().use { dst -> src.copyTo(dst, 1024 * 1024) }
                }
                if (f.length() > MAX_BACKUP_BYTES) { f.delete(); null } else f
            }.getOrNull()

            // "wt" = 截断后写入，不然会在原文件末尾接着写
            val out = resolver.openOutputStream(uri, "wt")
            if (out == null) {
                restoreBackup(context, uri, backup)
                return false
            }
            out.use { input.copyTo(it, 1024 * 1024) }
            if (scoped) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (takenAt != null) put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
                }, null, null)
                pendingSet = false
            }
            if (takenAt != null) runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { File(it).setLastModified(takenAt) }
                }
            }
            true
        } catch (e: Exception) {
            // 别人 App 创建的文件覆盖不了；写了一半的则尝试用备份恢复
            restoreBackup(context, uri, backup)
            false
        } finally {
            backup?.delete()
            if (scoped && pendingSet) runCatching {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            }
        }
    }

    private fun restoreBackup(context: Context, uri: Uri, backup: File?) {
        if (backup == null || !backup.exists()) return
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                backup.inputStream().use { it.copyTo(out, 1024 * 1024) }
            }
            Log.i(TAG, "restored original after failed overwrite")
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
        var ok = false
        try {
            resolver.openOutputStream(uri)?.use { out ->
                input.copyTo(out, 1024 * 1024)
                ok = true
            }
        } finally {
            if (ok) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (takenAt != null) put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
                }
                resolver.update(uri, done, null, null)
            } else {
                // 失败还留着 pending 记录 → 相册里多一个 0 字节空条目
                runCatching { resolver.delete(uri, null, null) }
            }
        }

        if (!ok) return null

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
        val targetDir = File(root, dir).apply { mkdirs() }
        val target = uniqueFile(targetDir, filename)
        input.use { it.copyTo(target.outputStream(), 1024 * 1024) }
        target.setLastModified(takenAt ?: System.currentTimeMillis())
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
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

package com.photovault.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest

/** 流式计算 SHA256，避免把整段视频读进内存。 */
fun sha256Of(context: Context, uri: Uri): String? {
    return try {
        val md = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }
}

fun Context.displayNameOf(uri: Uri): String =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "photo.jpg")

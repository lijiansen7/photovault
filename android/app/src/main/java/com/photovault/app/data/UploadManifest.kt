package com.photovault.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本机“已经备份到 NAS 的照片”清单。
 *
 * 手机删除照片后，本地相册里就没有它了；要判断哪些 NAS 照片该跟着删，
 * 只能靠这份清单：photo_id → 本地签名（文件名|字节数）。
 * 用 SQLite 而不是 DataStore：几万条记录时不用每次整文件重写。
 */
class UploadManifest(context: Context) : SQLiteOpenHelper(context, DB, null, 1) {

    data class Entry(val photoId: String, val localKey: String)

    companion object {
        private const val DB = "upload_manifest.db"
        private const val TABLE = "items"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE(" +
                "user_id TEXT NOT NULL, photo_id TEXT NOT NULL, local_key TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, PRIMARY KEY(user_id, photo_id))"
        )
        db.execSQL("CREATE INDEX idx_manifest_user ON $TABLE(user_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    @Synchronized
    fun remember(userId: String, photoId: String, localKey: String) {
        if (userId.isBlank() || photoId.isBlank() || localKey.isBlank()) return
        val values = ContentValues().apply {
            put("user_id", userId)
            put("photo_id", photoId)
            put("local_key", localKey)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun entries(userId: String): List<Entry> {
        if (userId.isBlank()) return emptyList()
        val out = mutableListOf<Entry>()
        readableDatabase.query(
            TABLE, arrayOf("photo_id", "local_key"), "user_id=?",
            arrayOf(userId), null, null, "created_at ASC",
        ).use { c ->
            while (c.moveToNext()) out += Entry(c.getString(0), c.getString(1))
        }
        return out
    }

    @Synchronized
    fun forget(userId: String, photoIds: Collection<String>) {
        if (userId.isBlank() || photoIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            photoIds.chunked(400).forEach { chunk ->
                val marks = chunk.joinToString(",") { "?" }
                db.delete(TABLE, "user_id=? AND photo_id IN ($marks)",
                    arrayOf(userId) + chunk.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun clear(userId: String) {
        if (userId.isBlank()) return
        writableDatabase.delete(TABLE, "user_id=?", arrayOf(userId))
    }
}

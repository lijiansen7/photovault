package com.photovault.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一张没传上去的照片：谁、为什么。 */
data class UploadFailure(
    val mediaId: Long,
    val name: String,
    val reason: String,
    val at: Long,
)

/**
 * 上传失败清单。
 *
 * 之前只有「失败 N 张」一个数字，用户既不知道是哪几张、也没法单独重试 ——
 * 对备份类 App 来说这是最让人焦虑的部分。这里把失败的文件名和原因落盘，
 * 首页可以直接展开看，并针对单张重试。
 */
object FailureStore {
    private const val FILE = "photovault_upload_failures"
    private const val KEY = "items"
    private const val MAX = 50

    fun load(ctx: Context): List<UploadFailure> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UploadFailure(
                    mediaId = o.optLong("id"),
                    name = o.optString("name"),
                    reason = o.optString("reason"),
                    at = o.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, list: List<UploadFailure>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().apply {
                put("id", it.mediaId)
                put("name", it.name)
                put("reason", it.reason)
                put("at", it.at)
            })
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

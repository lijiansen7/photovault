package com.photovault.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

data class UploadResult(val created: Boolean, val id: String, val sha256: String)

data class LoginResult(
    val token: String,
    val username: String,
    val role: String,
    val displayName: String,
) {
    val isAdmin: Boolean get() = role == "admin"
}

class NasApi(private val cfg: NasConfig) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    val base: String get() = normalize(cfg.baseUrl)

    val isReady: Boolean get() = base.isNotBlank()

    companion object {
        /** 用户填什么都能兜住：192.168.1.10 / http://192.168.1.10:9000 / https://nas.home */
        fun normalize(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isBlank()) return ""
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val host = Uri.parse(s).host
            if (host.isNullOrBlank()) return s
            return if (Uri.parse(s).port == -1) "$s:8765" else s
        }

        /** 登录不需要已有配置，用独立的短超时 client，卡住的网络别把 UI 拖死。 */
        private val loginClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        suspend fun login(rawUrl: String, username: String, password: String): LoginResult =
            withContext(Dispatchers.IO) {
                val base = normalize(rawUrl)
                if (base.isBlank()) throw ApiException("请先填服务器地址")
                if (username.isBlank()) throw ApiException("请填用户名")
                if (password.isBlank()) throw ApiException("请填密码")
                val body = JSONObject()
                    .put("username", username.trim())
                    .put("password", password)
                    .toString().toRequestBody("application/json".toMediaTypeOrNull())
                val r = Request.Builder().url("$base/api/auth/login").post(body).build()
                loginClient.newCall(r).execute().use { resp ->
                    val s = resp.body?.string().orEmpty()
                    if (resp.code == 401) throw ApiException("用户名或密码错误")
                    if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code} ${resp.message}")
                    val o = JSONObject(s)
                    val u = o.optJSONObject("user")
                    LoginResult(
                        token = o.optString("token"),
                        username = u?.optString("username") ?: username,
                        role = u?.optString("role") ?: "user",
                        displayName = u?.optString("display_name")?.takeIf { it.isNotBlank() }
                            ?: u?.optString("username") ?: username,
                    )
                }
            }
    }

    /** 统一带登录凭据。只认 Bearer token（旧的单一 Token 通道已移除）。 */
    private fun authed(url: String): Request.Builder =
        Request.Builder().url(url).addHeader("Authorization", "Bearer ${cfg.authToken}")

    private fun req(path: String) = authed(base + path).build()

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when (resp.code) {
                401 -> throw ApiException("登录已失效，请到「设置」里重新登录")
                403 -> throw ApiException("没有权限做这个操作")
                413 -> throw ApiException("文件超过服务端大小上限")
                in 200..299 -> body
                else -> throw ApiException("HTTP ${resp.code} ${resp.message}")
            }
        }
    }

    // ------------------------------------------------------------ 连接测试 --

    suspend fun ping() {
        val r = authed("$base/api/ping").post("".toRequestBody(null)).build()
        call(r)
    }

    suspend fun stats(): NasStats = withContext(Dispatchers.IO) {
        val o = JSONObject(call(req("/api/stats")))
        NasStats(
            count = o.optInt("count"),
            videos = o.optInt("videos"),
            withGps = o.optInt("with_gps"),
            bytes = o.optLong("bytes"),
            lastUploadAt = if (o.isNull("last_upload_at")) null else o.optLong("last_upload_at"),
        )
    }

    // -------------------------------------------------------------- 查重 ----

    /** 返回已经在 NAS 上的 sha256 集合，用于跳过已备份的照片。 */
    suspend fun existing(hashes: List<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        val arr = JSONArray()
        hashes.forEach { arr.put(it) }
        val body = JSONObject().put("sha256", arr).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val r = authed("$base/api/photos/exists").post(body).build()
        val o = JSONObject(call(r))
        val out = mutableSetOf<String>()
        val ja = o.optJSONArray("existing") ?: return emptySet()
        for (i in 0 until ja.length()) out.add(ja.getString(i))
        return out
    }

    // -------------------------------------------------------------- 粗筛 ----

    /** 粗筛用的一条：只带元信息，不带文件内容。 */
    data class PrescreenItem(val key: String, val size: Long, val takenAt: Long?, val name: String)

    /**
     * 上传前先问一遍「这些是不是已经备份过了」。
     *
     * 只比对大小 / 拍摄时间 / 文件名，服务端不用读文件，手机端也不用读文件
     * —— 命中即可整段跳过，省掉一遍完整的 SHA256 计算。
     * 粗筛可能漏判（同尺寸同时间的不同照片），所以没命中的还要走 [existing] 精确查重。
     */
    suspend fun prescreen(items: List<PrescreenItem>): Set<String> {
        if (items.isEmpty()) return emptySet()
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("key", it.key)
                    .put("size", it.size)
                    .put("taken_at", it.takenAt)
                    .put("name", it.name)
            )
        }
        val body = JSONObject().put("items", arr).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val r = authed("$base/api/photos/prescreen").post(body).build()
        val o = JSONObject(call(r))
        val out = mutableSetOf<String>()
        val ja = o.optJSONArray("known") ?: return emptySet()
        for (i in 0 until ja.length()) out.add(ja.getString(i))
        return out
    }

    // -------------------------------------------------------------- 上传 ----

    suspend fun upload(ctx: Context, m: LocalMedia, deviceId: String): UploadResult {
        val meta = JSONObject().apply {
            m.takenAt?.let { put("taken_at", it) }
            m.gpsLat?.let { put("gps_lat", it) }
            m.gpsLon?.let { put("gps_lon", it) }
            m.gpsAlt?.let { put("gps_alt", it) }
            m.width?.let { put("width", it) }
            m.height?.let { put("height", it) }
            m.orientation?.let { put("orientation", it) }
            m.cameraMake?.let { put("camera_make", it) }
            m.cameraModel?.let { put("camera_model", it) }
            m.bucket?.let { put("album", it) }
            m.relDir?.let { put("rel_dir", it) }
            put("original_path", m.uri.toString())
        }.toString()

        val type = (m.mime.ifBlank { "application/octet-stream" }).toMediaTypeOrNull()
        val fileBody = UriRequestBody(ctx.contentResolver, m.uri, m.size, type)

        val builder = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("meta", meta)
            .addFormDataPart("file", m.displayName, fileBody)

        // 视频顺手抽一张封面帧一起传：服务端没 ffmpeg 解不了视频帧，
        // 但手机本地就有原视频，抽一张几乎零成本。服务端存下后视频就有缩略图了。
        if (m.isVideo) {
            videoFrame(ctx, m.uri)?.let { bytes ->
                builder.addFormDataPart(
                    "thumb", "thumb.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
                )
            }
        }
        val multipart = builder.build()

        val r = authed("$base/api/upload").post(multipart).build()

        val o = JSONObject(call(r))
        return UploadResult(
            created = o.optString("status") == "created",
            id = o.optString("id"),
            sha256 = o.optString("sha256"),
        )
    }

    // ---------------------------------------------------------- 浏览/下载 --

    suspend fun list(
        offset: Int,
        limit: Int = 300,
        kind: String? = null,
        folder: String? = null,
    ): Pair<List<PhotoDto>, Int> {
        val q = buildString {
            append("/api/photos?limit=$limit&offset=$offset&order=taken_at%20DESC")
            if (!kind.isNullOrBlank()) append("&kind=$kind")
            // 文件夹名里可能有中文和斜杠，必须编码
            if (!folder.isNullOrBlank()) append("&folder=").append(Uri.encode(folder))
        }
        val o = JSONObject(call(req(q)))
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { PhotoDto.fromJson(arr.getJSONObject(it)) }
        return items to o.optInt("total")
    }

    /** 手机端按文件夹浏览用的目录列表：目录名（如 DCIM/Camera）→ 张数。 */
    suspend fun folders(): List<Pair<String, Int>> {
        val o = JSONObject(call(req("/api/folders")))
        val arr = o.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<Pair<String, Int>>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            out += it.optString("folder") to it.optInt("c")
        }
        return out
    }

    suspend fun photo(id: String): PhotoDto = PhotoDto.fromJson(JSONObject(call(req("/api/photos/$id"))))

    /** 图片库只能带 URL，token 走 query（服务端同样认）。 */
    private val queryToken: String
        get() = Uri.encode(cfg.authToken)

    /** 缩略图地址（给 Coil 用）。 */
    fun thumbUrl(id: String): String = "$base/api/photos/$id/thumb?token=$queryToken"

    /** 原文件下载地址：字节与上传时完全一致，EXIF 完整。 */
    fun fileUrl(id: String): String = "$base/api/photos/$id/file?download=1&token=$queryToken"

    /**
     * 打开原文件字节流（在 IO 线程执行）。
     * 调用方在 [block] 里把流写进 MediaStore，写完流自动关闭。
     */
    suspend fun <T> withOriginal(id: String, block: (java.io.InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val request = authed(fileUrl(id)).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: throw ApiException("空响应体")
                stream.use { block(it) }
            }
        }

    suspend fun delete(id: String) {
        val r = authed("$base/api/photos/$id").delete().build()
        call(r)
    }

    // -------------------------------------------------------------- 回收站 ----

    /** 回收站里还有多少张（设置页用来提示，清空前也展示数量）。 */
    suspend fun trashCount(): Int {
        val o = JSONObject(call(req("/api/trash?limit=1")))
        return o.optInt("total", 0)
    }

    /** 一键清空回收站：把回收站里的记录彻底删掉，磁盘上的原文件也一并删除。
     *  返回真正删掉的记录数。这是不可逆操作。 */
    suspend fun emptyTrash(): Int {
        val r = authed("$base/api/trash").delete().build()
        val o = JSONObject(call(r))
        return o.optInt("removed", 0)
    }

    /** 回收站列表。返回 (条目, 总数)。 */
    suspend fun trashList(limit: Int = 300): Pair<List<PhotoDto>, Int> {
        val o = JSONObject(call(req("/api/trash?limit=$limit")))
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { PhotoDto.fromJson(arr.getJSONObject(it)) }
        return items to o.optInt("total", items.size)
    }

    /** 只彻底删掉回收站里**指定的几条**（选择性删除，不可逆）。返回真正删掉的条数。
     *  不能复用 delete()：DELETE /api/photos/{id} 只认未删除的记录。 */
    suspend fun purgeTrash(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val body = JSONObject().put("ids", arr).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val r = authed("$base/api/trash/delete").post(body).build()
        val o = JSONObject(call(r))
        return o.optInt("deleted", 0)
    }

    /** 批量删除（软删除，进回收站，可恢复）。返回真正删掉的条数。 */
    suspend fun deleteMany(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val body = JSONObject().put("ids", arr).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val r = authed("$base/api/photos/delete").post(body).build()
        val o = JSONObject(call(r))
        return o.optInt("deleted", 0)
    }

    /**
     * 抽视频首帧当封面。服务端没 ffmpeg 解不了视频帧，但手机本地有原文件，
     * 抽一张代价极小 —— 随上传一起带过去，服务端存下后视频就有缩略图了。
     * 失败返回 null（不影响视频本身上传）。
     */
    private fun videoFrame(ctx: Context, uri: Uri): ByteArray? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val bmp = r.getFrameAtTime(
                0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            val max = 512
            val longSide = kotlin.math.max(bmp.width, bmp.height)
            val scaled = if (longSide > max) {
                val s = max.toFloat() / longSide
                android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true
                )
            } else bmp
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        } finally {
            r.release()
        }
    }.getOrNull()

    /** 流式上传本机 Uri，不把整个文件读进内存。 */
    private class UriRequestBody(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val size: Long,
        private val type: okhttp3.MediaType?,
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType? = type
        override fun contentLength(): Long = if (size > 0) size else -1
        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
            input.use { it.source().use { s -> sink.writeAll(s) } }
        }
    }
}

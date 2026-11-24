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

open class ApiException(message: String) : IOException(message)

/** 401：token 过期/被吊销。调用方可以尝试用「记住的密码」静默重登一次。 */
class AuthException(message: String) : ApiException(message)

data class UploadResult(val status: String, val id: String, val sha256: String) {
    val created: Boolean get() = status == "created"
    val duplicate: Boolean get() = status == "duplicate"
}

data class LoginResult(
    val token: String,
    val id: String,
    val username: String,
    val role: String,
    val displayName: String,
) {
    val isAdmin: Boolean get() = role == "admin"
}

class NasApi(private val cfg: NasConfig) {

    private val client: OkHttpClient get() = Http.client

    /** 同步取缓存地址（Coil 缩略图、文件 URL 用；网络探测由 API 调用触发）。 */
    val base: String get() = EndpointRouter.cached(cfg)

    private suspend fun resolvedBase(): String = EndpointRouter.resolve(cfg)

    val isReady: Boolean get() = base.isNotBlank()

    companion object {
        /**
         * 用户填什么都能兜住：192.168.1.10 / http://192.168.1.10:9000 / https://nas.home
         *
         * 注意：以前只要 Uri.port == -1 就无条件补 :8765，结果
         * `https://photo.example.com` 会被改成 `https://...:8765`（443 反代直接连不上），
         * 带子路径的地址还会拼成 `.../app/photovault:8765`。
         */
        fun normalize(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isBlank()) return ""
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            val uri = Uri.parse(s)
            val host = uri.host ?: return s
            val scheme = (uri.scheme ?: "http").lowercase()
            val port = uri.port
            val explicit = port != -1
            val defaultPort = if (scheme == "https") 443 else 8765
            val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
            val portPart = when {
                explicit -> ":$port"
                defaultPort == 443 -> ""        // https 默认 443，不要画蛇添足
                else -> ":$defaultPort"
            }
            val path = uri.encodedPath.orEmpty().let { if (it == "/") "" else it }
            val query = uri.encodedQuery?.let { "?$it" } ?: ""
            return "$scheme://$hostPart$portPart$path$query"
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
                Http.loginClient.newCall(r).execute().use { resp ->
                    val s = resp.body?.string().orEmpty()
                    if (resp.code == 401 || resp.code == 429) {
                        throw ApiException(detailOf(s) ?: if (resp.code == 429) "尝试过于频繁，请稍后再试" else "用户名或密码错误")
                    }
                    if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code} ${resp.message}")
                    val o = JSONObject(s)
                    val u = o.optJSONObject("user")
                    LoginResult(
                        token = o.optString("token"),
                        id = u?.optString("id").orEmpty(),
                        username = u?.optString("username") ?: username,
                        role = u?.optString("role") ?: "user",
                        displayName = u?.optString("display_name")?.takeIf { it.isNotBlank() }
                            ?: u?.optString("username") ?: username,
                    )
                }
            }

        suspend fun loginDual(cfg: NasConfig, username: String, password: String): LoginResult {
            val preferred = EndpointRouter.resolve(cfg)
            val urls = (listOf(preferred, cfg.lanUrl, cfg.wanUrl, cfg.baseUrl))
                .map { normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()
            if (urls.isEmpty()) throw ApiException("请先填内网或外网地址")
            var last: Throwable? = null
            for (url in urls) {
                try {
                    return login(url, username, password)
                } catch (e: Throwable) {
                    last = e
                }
            }
            throw (last ?: ApiException("登录失败"))
        }

        private fun detailOf(body: String): String? = runCatching {
            JSONObject(body).optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 统一带登录凭据。只认 Bearer token。 */
    private fun authed(url: String): Request.Builder =
        Request.Builder().url(url).addHeader("Authorization", "Bearer ${cfg.authToken}")

    private suspend fun req(path: String): Request =
        authed(resolvedBase() + path).build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when (resp.code) {
                401 -> throw AuthException("登录已失效，请到「设置」里重新登录")
                403 -> throw ApiException("没有权限做这个操作")
                413 -> throw ApiException("文件超过服务端大小/配额上限")
                429 -> throw ApiException(detailOf(body) ?: "操作过于频繁，请稍后再试")
                in 200..299 -> body
                else -> throw ApiException(detailOf(body) ?: "HTTP ${resp.code} ${resp.message}")
            }
        }
    }

    /**
     * 失败时如果当前走的是内网，自动切到外网重试一次。
     * 只是网络层失败才切；401/HTTP 错误不切，避免把「密码错」误判成「内网不通」。
     */
    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        try {
            execute(request)
        } catch (e: AuthException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            val failedBase = EndpointRouter.baseOf(request.url.toString())
            val next = EndpointRouter.noteFailure(cfg, failedBase)
            if (next == null || next == failedBase) throw e
            val retryUrl = request.url.toString().replaceFirst(failedBase, next)
            execute(request.newBuilder().url(retryUrl).build())
        }
    }

    // ------------------------------------------------------------ 连接测试 --

    /** 轻量健康检查，用来测首页「NAS 连接」的延迟（不需要登录）。 */
    suspend fun health(): String? = withContext(Dispatchers.IO) {
        val first = resolvedBase()
        fun fetch(base: String): String? {
            val request = Request.Builder().url("$base/api/health").get().build()
            Http.probeClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                return runCatching { JSONObject(body).optString("version") }.getOrNull()
            }
        }
        try {
            fetch(first)
        } catch (e: AuthException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            // 缓存里可能还是内网，但 WiFi/局域网已经断了；立刻换外网再试一次。
            val next = EndpointRouter.noteFailure(cfg, first)
            if (next == null || next == first) throw e
            fetch(next)
        }
    }

    suspend fun ping() {
        val base = resolvedBase()
        val r = authed("$base/api/ping").post("".toRequestBody(null)).build()
        call(r)
    }

    suspend fun logout() {
        val base = resolvedBase()
        val r = authed("$base/api/auth/logout").post("".toRequestBody(null)).build()
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
        val base = resolvedBase()
        val r = authed("$base/api/photos/exists").post(body).build()
        val o = JSONObject(call(r))
        val out = mutableSetOf<String>()
        val ja = o.optJSONArray("existing") ?: return emptySet()
        for (i in 0 until ja.length()) out.add(ja.getString(i))
        return out
    }

    // -------------------------------------------------------------- 粗筛 ----

    data class PrescreenItem(val key: String, val size: Long, val takenAt: Long?, val name: String)

    /**
     * 上传前先问一遍「这些是不是已经备份过了」。
     *
     * 服务端按 (size, taken_at, filename) 比对。上传 meta 里的 taken_at
     * 必须和这里传的 takenAt 来自同一个字段，否则永远不命中、每次同步都重算哈希。
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
        val base = resolvedBase()
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
            // 和粗筛保持同一个来源：MediaStore 的 dateTaken 优先，EXIF 只做兜底
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

        // 视频顺手抽一张封面帧一起传
        if (m.isVideo) {
            videoFrame(ctx, m.uri)?.let { bytes ->
                builder.addFormDataPart(
                    "thumb", "thumb.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaTypeOrNull()),
                )
            }
        }
        val multipart = builder.build()

        val base = resolvedBase()
        val r = authed("$base/api/upload").post(multipart).build()

        val o = JSONObject(call(r))
        return UploadResult(
            status = o.optString("status"),
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
        viewerDevice: String? = null,
        query: String? = null,
        album: String? = null,
        hasGps: Boolean? = null,
        takenFrom: Long? = null,
        takenTo: Long? = null,
    ): Pair<List<PhotoDto>, Int> {
        val q = buildString {
            append("/api/photos?limit=$limit&offset=$offset&order=taken_at%20DESC")
            if (!kind.isNullOrBlank()) append("&kind=").append(Uri.encode(kind))
            if (!folder.isNullOrBlank()) append("&folder=").append(Uri.encode(folder))
            // 服务端 /api/photos 早就支持这些筛选，之前 App 只传了分页参数
            if (!query.isNullOrBlank()) append("&q=").append(Uri.encode(query))
            if (!album.isNullOrBlank()) append("&album=").append(Uri.encode(album))
            if (hasGps == true) append("&has_gps=true")
            if (takenFrom != null) append("&taken_from=").append(takenFrom)
            if (takenTo != null) append("&taken_to=").append(takenTo)
            // 这台设备上已删除的照片不再出现在列表/一键还原里
            if (!viewerDevice.isNullOrBlank()) {
                append("&viewer_device=").append(Uri.encode(viewerDevice))
            }
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

    suspend fun photo(id: String): PhotoDto =
        PhotoDto.fromJson(JSONObject(call(req("/api/photos/${Uri.encode(id)}"))))

    /** 缩略图地址（给 Coil 用，token 走请求头，不再塞 URL）。 */
    fun thumbUrl(id: String, size: Int = 0): String =
        "$base/api/photos/${Uri.encode(id)}/thumb" + if (size > 0) "?size=$size" else ""

    /** 原文件地址：字节与上传时完全一致，EXIF 完整。 */
    fun fileUrl(id: String): String = "$base/api/photos/${Uri.encode(id)}/file"

    /** 系统播放器用的短期签名直链（1 小时）。 */
    suspend fun mediaLink(id: String): String {
        val base = resolvedBase()
        val o = JSONObject(call(req("/api/photos/${Uri.encode(id)}/link")))
        val path = o.optString("url")
        return if (path.startsWith("http")) path else base + path
    }

    /**
     * 打开原文件字节流（在 IO 线程执行）。
     * 调用方在 [block] 里把流写进 MediaStore，写完流自动关闭。
     */
    suspend fun <T> withOriginal(id: String, block: (java.io.InputStream) -> T): T =
        withContext(Dispatchers.IO) {
            val base = resolvedBase()
            val request = authed("$base/api/photos/${Uri.encode(id)}/file").get().build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) throw AuthException("登录已失效")
                if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}")
                val stream = resp.body?.byteStream() ?: throw ApiException("空响应体")
                stream.use { block(it) }
            }
        }

    suspend fun delete(id: String) {
        val base = resolvedBase()
        val r = authed("$base/api/photos/${Uri.encode(id)}").delete().build()
        call(r)
    }

    // -------------------------------------------------------------- 回收站 ----

    suspend fun trashCount(): Int {
        val o = JSONObject(call(req("/api/trash?limit=1")))
        return o.optInt("total", 0)
    }

    suspend fun emptyTrash(): Int {
        val base = resolvedBase()
        val r = authed("$base/api/trash").delete().build()
        val o = JSONObject(call(r))
        return o.optInt("removed", 0)
    }

    suspend fun trashList(limit: Int = 300, offset: Int = 0): Pair<List<PhotoDto>, Int> {
        val o = JSONObject(call(req("/api/trash?limit=$limit&offset=$offset")))
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { PhotoDto.fromJson(arr.getJSONObject(it)) }
        return items to o.optInt("total", items.size)
    }

    suspend fun purgeTrash(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val body = JSONObject().put("ids", arr).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val base = resolvedBase()
        val r = authed("$base/api/trash/delete").post(body).build()
        val o = JSONObject(call(r))
        return o.optInt("deleted", 0)
    }

    /** 把旧设备 ID 下的照片迁到新 ID（用户自定义设备 ID 时调用）。 */
    suspend fun renameDevice(oldId: String, newId: String): Int {
        val body = JSONObject()
            .put("old_id", oldId)
            .put("new_id", newId)
            .toString().toRequestBody("application/json".toMediaTypeOrNull())
        val base = resolvedBase()
        val r = authed("$base/api/device/rename").post(body).build()
        val o = JSONObject(call(r))
        return o.optInt("photos", 0)
    }

    /** 从 NAS 回收站批量恢复（恢复后照片重新出现在 NAS 相册）。 */
    suspend fun restoreTrash(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val body = JSONObject().put("ids", arr).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val base = resolvedBase()
        val r = authed("$base/api/trash/restore").post(body).build()
        val o = JSONObject(call(r))
        return o.optInt("restored", 0)
    }

    /** 批量删除（软删除，进回收站，可恢复）。返回真正删掉的条数。 */
    suspend fun deleteMany(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val body = JSONObject().put("ids", arr).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val base = resolvedBase()
        val r = authed("$base/api/photos/delete").post(body).build()
        val o = JSONObject(call(r))
        return o.optInt("deleted", 0)
    }

    /**
     * 手机本地已删除的照片：让 NAS 按设备打墓碑/移入回收站。
     * 只影响这台设备备份过、且本机已经不存在的照片。
     */
    suspend fun forget(ids: List<String>, deviceId: String): Int {
        if (ids.isEmpty() || deviceId.isBlank()) return 0
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        val body = JSONObject()
            .put("ids", arr)
            .put("device_id", deviceId)
            .toString().toRequestBody("application/json".toMediaTypeOrNull())
        val base = resolvedBase()
        val r = authed("$base/api/photos/forget").post(body).build()
        val o = JSONObject(call(r))
        return o.optInt("trashed", 0)
    }

    /**
     * 抽视频首帧当封面。服务端没 ffmpeg 解不了视频帧，但手机本地有原文件。
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
            val bytes = out.toByteArray()
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            bytes
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

package com.photovault.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 内网优先、外网兜底的地址选择器。
 *
 * - 有内网地址时先用内网；
 * - 内网 /api/health 2 秒内连不上，60 秒内直接走外网，避免每次请求都卡一下；
 * - 某个请求在“当前是内网”时发生网络异常，[NasApi] 会自动换外网重试一次；
 * - 只有一条地址时完全不做探测，行为跟以前一致。
 */
object EndpointRouter {

    private const val CACHE_MS = 60_000L
    private const val LAN_BLOCK_MS = 60_000L

    private val lock = Mutex()

    @Volatile private var active = ""
    @Volatile private var cacheKey = ""
    @Volatile private var probedAt = 0L
    @Volatile private var lanBlockedUntil = 0L

    private fun lanOf(cfg: NasConfig): String = NasApi.normalize(cfg.lanUrl)

    private fun wanOf(cfg: NasConfig): String {
        val wan = NasApi.normalize(cfg.wanUrl)
        if (wan.isNotBlank()) return wan
        // 只有旧版单地址时，把它当外网地址用
        return if (lanOf(cfg).isBlank()) NasApi.normalize(cfg.baseUrl) else ""
    }

    private fun keyOf(lan: String, wan: String) = "$lan|$wan"

    /** Coil / 文件 URL 这种同步场景用：拿上次选中的，没探测过就先乐观用内网。 */
    fun cached(cfg: NasConfig): String {
        val lan = lanOf(cfg)
        val wan = wanOf(cfg)
        if (active.isNotBlank() && cacheKey == keyOf(lan, wan)) return active
        return lan.ifBlank { wan }.ifBlank { NasApi.normalize(cfg.baseUrl) }
    }

    fun active(cfg: NasConfig): String = cached(cfg)

    fun isLan(cfg: NasConfig, url: String): Boolean {
        val lan = lanOf(cfg)
        return lan.isNotBlank() && NasApi.normalize(url) == lan
    }

    suspend fun resolve(cfg: NasConfig): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val lan = lanOf(cfg)
            val wan = wanOf(cfg)
            val key = keyOf(lan, wan)
            if (key != cacheKey) {
                cacheKey = key
                active = ""
                probedAt = 0L
                lanBlockedUntil = 0L
            }
            val now = System.currentTimeMillis()
            if (active.isNotBlank() && now - probedAt < CACHE_MS) return@withLock active
            if (lan.isBlank()) {
                active = wan
                probedAt = now
                return@withLock active
            }
            if (wan.isBlank() || lan == wan) {
                active = lan
                probedAt = now
                return@withLock active
            }
            if (now < lanBlockedUntil) {
                active = wan
                probedAt = now
                return@withLock active
            }
            val ok = probe(lan)
            active = if (ok) lan else wan
            if (!ok) lanBlockedUntil = now + LAN_BLOCK_MS
            probedAt = now
            active
        }
    }

    private fun probe(url: String): Boolean = runCatching {
        val req = Request.Builder().url("$url/api/health").get().build()
        Http.probeClient.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** 当前请求网络失败：如果走的是内网，切外网并返回外网地址。 */
    fun noteFailure(cfg: NasConfig, failedBase: String): String? {
        val lan = lanOf(cfg)
        val wan = wanOf(cfg)
        val failed = NasApi.normalize(failedBase)
        if (failed.isBlank()) return null
        if (failed == lan && wan.isNotBlank()) {
            lanBlockedUntil = System.currentTimeMillis() + LAN_BLOCK_MS
            active = wan
            cacheKey = keyOf(lan, wan)
            probedAt = System.currentTimeMillis()
            return wan
        }
        return null
    }

    /** 从完整请求 URL 里还原出它用的 base；找不到就截到 /api/ 之前。 */
    fun baseOf(url: String): String {
        val normalized = NasApi.normalize(url)
        // 常见路径前缀，按长度倒序，避免 /api 与 /app/photovault/api 混淆
        val known = listOf(active).filter { it.isNotBlank() }
        known.firstOrNull { normalized.startsWith(it) }?.let { return it }
        val idx = normalized.indexOf("/api/")
        return if (idx > 0) normalized.substring(0, idx) else normalized
    }

    /** 设置/登录变化后强制重新探测。 */
    fun invalidate() {
        active = ""
        probedAt = 0L
        lanBlockedUntil = 0L
    }
}

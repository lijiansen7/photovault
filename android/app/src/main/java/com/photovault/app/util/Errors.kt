package com.photovault.app.util

import com.photovault.app.data.AuthException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把 okhttp / Java 的底层异常翻译成用户能看懂的一句话。
 *
 * 之前首页直接把 `failed to connect to /10.0.2.2 (port 8765) from /10.0.2.15
 * (port 60004) after 15000ms` 甩在卡片里 —— 用户既不知道哪一步错了，也不知道该改什么。
 */
object Errors {

    /** 一句话结论，给卡片主文案用。 */
    fun humanize(t: Throwable, where: String = "NAS"): String {
        val chain = generateSequence(t) { it.cause }.toList()
        val msg = chain.mapNotNull { it.message }.joinToString(" / ")

        chain.filterIsInstance<AuthException>().firstOrNull()?.let {
            return "登录已失效，请到「设置」里重新登录"
        }
        chain.filterIsInstance<UnknownHostException>().firstOrNull()?.let {
            val host = it.message?.substringAfter("UnknownHostException:")?.trim()
            return if (host.isNullOrBlank()) "解析不到 $where 的域名，检查地址是否写错"
            else "解析不到域名 $host，检查地址是否写错"
        }
        chain.filterIsInstance<SocketTimeoutException>().firstOrNull()?.let {
            return "连接 $where 超时：网络不通，或者被防火墙拦了"
        }
        chain.filterIsInstance<ConnectException>().firstOrNull()?.let {
            return "连不上 $where：地址或端口不对、服务没启动，或者不在同一网络"
        }
        chain.filterIsInstance<SSLException>().firstOrNull()?.let {
            return "$where 的 HTTPS 证书不被信任（自签证书需要换用 http）"
        }
        if (msg.contains("after", ignoreCase = true) && msg.contains("ms")) {
            return "连接 $where 超时：地址端口不通，或者 NAS 没开机"
        }
        if (t is IOException && msg.contains("timeout", ignoreCase = true)) {
            return "连接 $where 超时，请稍后重试"
        }
        return msg.ifBlank { "请求失败，请重试" }
    }

    /** 收进「详情」里的原始异常，排查时用。 */
    fun detail(t: Throwable): String =
        generateSequence(t) { it.cause }
            .map { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            .joinToString("\n")
}

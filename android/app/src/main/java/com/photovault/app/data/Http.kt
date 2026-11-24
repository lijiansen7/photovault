package com.photovault.app.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttpClient。
 *
 * 以前每个 NasApi 实例都 new 一个 client（各自一套连接池 + 线程池），
 * 而 ViewModel / Worker / 每个缩略图请求都会 new NasApi，线程和 socket 很快堆起来。
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 登录用短超时：网络不通时别把 UI 拖死。 */
    val loginClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 内网探测用：2 秒内连不上就认为不可达，马上切外网。 */
    val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

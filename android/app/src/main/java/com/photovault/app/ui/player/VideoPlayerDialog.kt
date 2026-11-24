package com.photovault.app.ui.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * 播放进度记忆。
 *
 * 走系统播放器时，切回来进度就丢了；内置播放器顺手把位置记在本地，
 * 同一个视频下次打开直接接着播。
 */
object PlayerPositions {
    private const val FILE = "photovault_player_pos"

    fun load(ctx: Context, id: String): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(id, 0L)

    fun save(ctx: Context, id: String, ms: Long) {
        if (ms <= 0) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(id, ms).apply()
    }

    fun clear(ctx: Context, id: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(id).apply()
    }
}

/**
 * 内置视频播放器（media3 / ExoPlayer）。
 *
 * 用 Authorization 头拉原文件，不依赖短期签名直链，也不跳出去。
 */
@Composable
fun VideoPlayerDialog(
    mediaId: String,
    url: String,
    authToken: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val startAt = remember(mediaId) { PlayerPositions.load(ctx, mediaId) }

    // 解不了码时不解释一句「播不出来」，用户只会以为 App 坏了：
    // 挂一个 error listener，把原因摆到画面上，顺带给个重试。
    var errMsg by remember(mediaId) { mutableStateOf<String?>(null) }
    var retryTick by remember(mediaId) { mutableStateOf(0) }

    val player = remember(mediaId, retryTick) {
        val ds = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errMsg = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                                "这台设备解不了这个视频的编码（多为 HEVC/H.265 或 4K）"
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                                "NAS 拒绝了请求，可能登录过期了"
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                                "连不上 NAS，检查网络或地址"
                            else -> "播放失败：${error.errorCodeName}"
                        }
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                if (startAt > 0) seekTo(startAt)
                playWhenReady = true
            }
    }

    // 每 3 秒记一次进度，异常退出也不会丢太多
    LaunchedEffect(mediaId) {
        while (true) {
            delay(3000)
            runCatching { PlayerPositions.save(ctx, mediaId, player.currentPosition) }
        }
    }

    DisposableEffect(mediaId, retryTick) {
        onDispose {
            runCatching { PlayerPositions.save(ctx, mediaId, player.currentPosition) }
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        keepScreenOn = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                // 「重试」会换一个新的 player 实例，View 是复用的：
                // 只在 factory 里挂 player 的话，重试后画面还绑在已经被 release 的旧实例上。
                update = { view -> view.player = player },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, "关闭", tint = Color.White)
            }
            if (title.isNotBlank()) {
                androidx.compose.material3.Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                )
            }

            errMsg?.let { msg ->
                Column(
                    Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Warning, null, tint = Color(0xFFFFB4AB),
                        modifier = Modifier.size(34.dp),
                    )
                    androidx.compose.material3.Text(
                        msg,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { errMsg = null; retryTick++ }) {
                            Text("重试", color = Color.White)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("关闭", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** 播放页在等一下首帧时用的占位 */
@Composable
fun PlayerLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

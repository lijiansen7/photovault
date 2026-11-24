package com.photovault.app.util

import com.photovault.app.data.PhotoDto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 给 TalkBack 读的一整句媒体描述。
 *
 * 缩略图是一张图 + 一层时间文字 + 播放角标 + 定位角标拼起来的，
 * 如果每个子元素都自己带 contentDescription，TalkBack 会把同一张图念三四遍。
 * 所以统一关掉子元素的描述，在外层容器上用这一句代替。
 */
fun photoDesc(p: PhotoDto): String = buildString {
    append(p.filename)
    append(if (p.isVideo) "，视频" else "，照片")
    p.takenAt?.let {
        append("，")
        append(SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(it)))
    }
    if (p.hasGps) append("，含位置")
    if (p.album != null) append("，相册 ").append(p.album)
}

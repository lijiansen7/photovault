package com.photovault.app.data

import org.json.JSONObject

/** NAS 上一条照片索引记录。 */
data class PhotoDto(
    val id: String,
    val filename: String,
    val mime: String,
    val kind: String,
    val size: Long,
    val takenAt: Long?,
    val uploadedAt: Long,
    val metaSource: String?,
    val gpsLat: Double?,
    val gpsLon: Double?,
    val width: Int?,
    val height: Int?,
    val cameraMake: String?,
    val cameraModel: String?,
    val album: String?,
    val deviceId: String?,
    /** 手机上的相对目录（如 DCIM/Camera），还原时按它写回去 */
    val relDir: String?,
) {
    val isVideo: Boolean get() = kind == "video" || mime.startsWith("video")
    val hasGps: Boolean get() = gpsLat != null && gpsLon != null

    companion object {
        fun fromJson(o: JSONObject): PhotoDto = PhotoDto(
            id = o.optString("id"),
            filename = o.optString("filename"),
            mime = o.optString("mime"),
            kind = o.optString("kind", "image"),
            size = o.optLong("size"),
            takenAt = if (o.isNull("taken_at")) null else o.optLong("taken_at"),
            uploadedAt = o.optLong("uploaded_at"),
            metaSource = if (o.isNull("meta_source")) null else o.optString("meta_source"),
            gpsLat = if (o.isNull("gps_lat")) null else o.optDouble("gps_lat"),
            gpsLon = if (o.isNull("gps_lon")) null else o.optDouble("gps_lon"),
            width = if (o.isNull("width")) null else o.optInt("width"),
            height = if (o.isNull("height")) null else o.optInt("height"),
            cameraMake = if (o.isNull("camera_make")) null else o.optString("camera_make"),
            cameraModel = if (o.isNull("camera_model")) null else o.optString("camera_model"),
            album = if (o.isNull("album")) null else o.optString("album"),
            deviceId = if (o.isNull("device_id")) null else o.optString("device_id"),
            relDir = if (o.isNull("rel_dir")) null else o.optString("rel_dir"),
        )
    }
}

data class NasStats(
    val count: Int,
    val videos: Int,
    val withGps: Int,
    val bytes: Long,
    val lastUploadAt: Long?,
)

package fitness.mobile

import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

/** Sequential decode with actual presentation timestamps; never nearest-frame seeking. */
internal suspend fun decodeVideo(context: Context, uri: Uri, onFrame: suspend (Bitmap, Long, Long) -> Unit) {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("文件没有视频轨道")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        require(durationUs in 1..120_000_000L) { "请选择不超过 2 分钟且时长可读取的视频" }
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec = decoder
        decoder.configure(format, null, null, 0); decoder.start()
        var inputEnded = false
        var lastSampleUs = -100_000L
        var lastProgress = SystemClock.uptimeMillis()
        val info = MediaCodec.BufferInfo()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = decoder.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
                lastProgress = SystemClock.uptimeMillis()
                var bitmap: Bitmap? = null
                try {
                    if (info.size > 0 && info.presentationTimeUs >= 0 && info.presentationTimeUs - lastSampleUs >= 100_000) {
                        require(info.presentationTimeUs <= 120_000_000L) { "视频时间戳超出 2 分钟分析范围" }
                        val image = decoder.getOutputImage(index) ?: error("设备解码器无法提供可分析画面")
                        bitmap = try { videoBitmap(image, rotation) } finally { image.close() }
                        lastSampleUs = info.presentationTimeUs
                    }
                } finally { decoder.releaseOutputBuffer(index, false) }
                bitmap?.let { frame ->
                    try { onFrame(frame, info.presentationTimeUs / 1000, durationUs / 1000) }
                    finally { frame.recycle() }
                }
                // Inference time is not decoder stall time.
                lastProgress = SystemClock.uptimeMillis()
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (SystemClock.uptimeMillis() - lastProgress > 15_000) error("视频解码超时")
        }
    } finally {
        try { codec?.release() } finally { extractor.release() }
    }
}

private fun videoBitmap(image: android.media.Image, rotation: Int): Bitmap {
    require(image.format == ImageFormat.YUV_420_888) { "不支持的视频像素格式" }
    val crop = image.cropRect
    val width = crop.width() and -2; val height = crop.height() and -2
    require(width > 0 && height > 0 && width.toLong() * height <= 3840L * 2160) { "请选择不超过 4K 的视频" }
    val data = ByteArray(width * height * 3 / 2)
    for (p in 0..2) {
        val plane = image.planes[p]; val buffer = plane.buffer.duplicate()
        val shift = if (p == 0) 0 else 1
        val origin = buffer.position() + (crop.top shr shift) * plane.rowStride + (crop.left shr shift) * plane.pixelStride
        for (y in 0 until (height shr shift)) for (x in 0 until (width shr shift)) {
            val target = if (p == 0) y * width + x else width * height + y * width + x * 2 + if (p == 1) 1 else 0
            data[target] = buffer.get(origin + y * plane.rowStride + x * plane.pixelStride)
        }
    }
    val stream = ByteArrayOutputStream()
    check(YuvImage(data, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 95, stream))
    val bytes = stream.toByteArray()
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("视频画面转换失败")
    val scale = minOf(1f, 640f / maxOf(raw.width, raw.height))
    val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
    val result = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (raw !== result) raw.recycle()
    return result
}

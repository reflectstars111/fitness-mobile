package fitness.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import fitness.mobile.core.Geometry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Frame(
    val bitmap: Bitmap, val points: List<Pair<Float, Float>?>,
    val metrics: Map<String, Double?>, val timestampMs: Long,
    val epoch: Long, val rejection: String?, val inferenceMs: Long, val poseCount: Int = 0
)

/** A single sequential inference worker; CameraX keeps only the latest waiting frame.
 * VIDEO mode consumes a causal camera stream with explicit monotonic timestamps.
 * The displayed analysis bitmap and skeleton always belong to the same frame.
 */
class PoseCamera(
    private val context: Context, private val owner: LifecycleOwner,
    private val front: Boolean, private val side: Geometry.Side,
    private val onFrame: (Frame) -> Unit, private val onError: (String) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var model: PoseEngine? = null // worker-confined
    private var cameraOrigin = -1L
    private var clockOrigin = 0L
    private var previousTimestamp = -1L

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed.get()) return@addListener
            try {
                val p = future.get()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!p.hasCamera(selector)) { onError("所选摄像头不可用，请切换摄像头"); return@addListener }
                val useCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .build()
                useCase.setAnalyzer(executor) { image -> analyze(image) }
                provider = p; analysis = useCase
                p.bindToLifecycle(owner, selector, useCase)
            } catch (e: Exception) { onError("相机启动失败：${e.javaClass.simpleName}") }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (closed.get()) return
            if (model == null) model = PoseEngine(context, side)
            val cameraMs = image.imageInfo.timestamp / 1_000_000
            if (cameraOrigin < 0) { cameraOrigin = cameraMs; clockOrigin = SystemClock.uptimeMillis() }
            val timestamp = clockOrigin + cameraMs - cameraOrigin
            if (timestamp <= previousTimestamp) { model?.resetTracking(); return }
            previousTimestamp = timestamp
            val raw = image.toBitmap()
            val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotation, true)
            if (raw !== bitmap) raw.recycle()
            val result = model!!.analyze(bitmap, timestamp)
            val timed = if (SystemClock.uptimeMillis() - timestamp > 350) result.copy(rejection = "stale_frame") else result
            if (!closed.get()) onFrame(timed)
        } catch (e: Exception) {
            if (!closed.get()) onError("姿态处理失败：${e.javaClass.simpleName}，请重新打开相机")
        } finally { image.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        analysis?.let { it.clearAnalyzer(); provider?.unbind(it) }
        executor.execute { model?.close(); model = null }
        executor.shutdown()
    }
}

/** MPImage owns and recycles its Bitmap on close; Compose must retain a separate one. */
internal fun inferenceImage(display: Bitmap) =
    BitmapImageBuilder(display.copy(Bitmap.Config.ARGB_8888, false)).build()

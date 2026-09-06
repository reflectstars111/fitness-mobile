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
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import fitness.mobile.core.Geometry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

data class Frame(
    val bitmap: Bitmap, val points: List<Pair<Float, Float>?>,
    val metrics: Map<String, Double?>, val timestampMs: Long,
    val epoch: Long, val rejection: String?, val inferenceMs: Long
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
    private var model: PoseLandmarker? = null // worker-confined
    private var cameraOrigin = -1L
    private var clockOrigin = 0L
    private var previousTimestamp = -1L
    private var previousHip: Pair<Double, Double>? = null
    private var epoch = 0L

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
            if (model == null) {
                model = PoseLandmarker.createFromOptions(context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                        .setRunningMode(RunningMode.VIDEO).setNumPoses(2)
                        .setMinPoseDetectionConfidence(.6f).setMinPosePresenceConfidence(.6f)
                        .setMinTrackingConfidence(.6f).build())
            }
            val cameraMs = image.imageInfo.timestamp / 1_000_000
            if (cameraOrigin < 0) { cameraOrigin = cameraMs; clockOrigin = SystemClock.uptimeMillis() }
            val timestamp = clockOrigin + cameraMs - cameraOrigin
            if (timestamp <= previousTimestamp) { epoch++; previousHip = null; return }
            previousTimestamp = timestamp
            val raw = image.toBitmap()
            val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val bitmap = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotation, true)
            if (raw !== bitmap) raw.recycle()
            val mpImage = inferenceImage(bitmap)
            val started = SystemClock.uptimeMillis()
            val result = try { model!!.detectForVideo(mpImage, timestamp) } finally { mpImage.close() }
            val poses = result.landmarks()
            val joints = mutableMapOf<Geometry.Joint, Geometry.Point>()
            val points = MutableList<Pair<Float, Float>?>(33) { null }
            val mapping = intArrayOf(0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
            var rejection: String? = when (poses.size) { 0 -> "no_person"; 1 -> null; else -> "multiple_people" }
            if (poses.size == 1) {
                val landmarks = poses[0]
                for (i in landmarks.indices) {
                    val p = landmarks[i]
                    val confidence = min(p.visibility().orElse(0f), p.presence().orElse(0f))
                    if (confidence >= .6f && p.x() in 0f..1f && p.y() in 0f..1f)
                        points[i] = p.x() to p.y()
                }
                Geometry.Joint.values().forEachIndexed { i, name ->
                    val p = landmarks[mapping[i]]
                    joints[name] = Geometry.Point(p.x() * bitmap.width.toDouble(), p.y() * bitmap.height.toDouble(),
                        min(p.visibility().orElse(0f), p.presence().orElse(0f)).toDouble())
                }
                val required = if (side == Geometry.Side.LEFT) listOf(0, 11, 23, 25, 27) else listOf(0, 12, 24, 26, 28)
                if (required.any { points[it] == null }) rejection = "missing_landmarks"
                val hip = points[if (side == Geometry.Side.LEFT) 23 else 24]
                if (hip != null) {
                    val current = hip.first.toDouble() to hip.second.toDouble()
                    previousHip?.let { if (hypot(current.first - it.first, current.second - it.second) > .15) {
                        epoch++; rejection = "tracking_reset"
                    } }
                    previousHip = current
                }
                val left = points[11]; val right = points[12]
                val shoulder = points[if (side == Geometry.Side.LEFT) 11 else 12]
                if (left != null && right != null && shoulder != null && hip != null) {
                    val torso = hypot((shoulder.first - hip.first) * bitmap.width,
                        (shoulder.second - hip.second) * bitmap.height)
                    if (torso < bitmap.height * .08f || abs(left.first - right.first) * bitmap.width > torso * .8f)
                        rejection = "check_side_view"
                }
            } else { previousHip = null; epoch++ }
            val metrics = Geometry.frameMetrics(joints, side, .6, bitmap.width, bitmap.height)
            if ((metrics["coverage"] ?: 0.0) < .65) rejection = rejection ?: "missing_landmarks"
            if (SystemClock.uptimeMillis() - timestamp > 350) rejection = "stale_frame"
            if (!closed.get()) onFrame(Frame(bitmap, points, metrics, timestamp, epoch, rejection,
                SystemClock.uptimeMillis() - started))
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

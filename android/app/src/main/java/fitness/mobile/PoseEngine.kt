package fitness.mobile

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import fitness.mobile.core.Geometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** One worker, one ordered sequence. Shared by camera and local-video analysis. */
internal class PoseEngine(context: Context, private val side: Geometry.Side) : AutoCloseable {
    private var previousHip: Pair<Double, Double>? = null
    private var epoch = 0L
    private var lastTimestamp = -1L
    private val model = PoseLandmarker.createFromOptions(context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumPoses(2)
            .setMinPoseDetectionConfidence(.6f).setMinPosePresenceConfidence(.6f)
            .setMinTrackingConfidence(.6f).build())
    fun analyze(bitmap: Bitmap, timestamp: Long): Frame {
        require(timestamp > lastTimestamp) { "Pose timestamps must increase" }
        lastTimestamp = timestamp
        val mpImage = inferenceImage(bitmap)
        val started = SystemClock.uptimeMillis()
        val result = try { model.detectForVideo(mpImage, timestamp) } finally { mpImage.close() }
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
        return Frame(bitmap, points, metrics, timestamp, epoch, rejection,
            SystemClock.uptimeMillis() - started, poses.size)
    }
    fun resetTracking() { epoch++; previousHip = null }
    override fun close() = model.close()
}

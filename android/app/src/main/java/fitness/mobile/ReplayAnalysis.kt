package fitness.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import fitness.mobile.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ReplayMode(val label: String, val exercise: Exercise?) {
    CHEST_PRESS("器械推胸：观测与技术要点复核", null),
    OBSERVE("其他动作：仅观察", null), SQUAT("深蹲实验规则", Exercise.SQUAT),
    CURL("站姿弯举实验规则", Exercise.BICEPS_CURL)
}

internal class ReplayRules(private val mode: ReplayMode, private val sideView: Boolean, private val side: Geometry.Side = Geometry.Side.LEFT) {
    private val counter = LiveCounter()
    private val coach = mode.exercise?.let { LiveCoach(it, null) }
    private val gate = FeedbackGate()
    private val chest = ChestPressCoach()
    private var eligible = 0
    fun accept(frame: Frame): JSONObject {
        val exercise = mode.exercise
        val quality = frame.rejection ?: when {
            !sideView -> "not_eligible_front_view"
            exercise != null && (frame.metrics[exercise.metric]?.isFinite() != true ||
                frame.metrics["trunk_lean_deg"]?.isFinite() != true) -> "missing_landmarks"
            exercise == Exercise.BICEPS_CURL && frame.metrics["upper_arm_tilt_deg"]?.isFinite() != true -> "missing_landmarks"
            else -> null
        }
        val chestRequired = if (side == Geometry.Side.LEFT) listOf(11, 13, 15, 23) else listOf(12, 14, 16, 24)
        val chestQuality = when {
            frame.poseCount != 1 -> if (frame.poseCount == 0) "no_person" else "multiple_people"
            frame.rejection == "tracking_reset" -> "tracking_reset"
            chestRequired.any { frame.points.getOrNull(it) == null } -> "missing_local_landmarks"
            frame.metrics["trunk_lean_deg"]?.isFinite() != true || frame.metrics["elbow_flexion_deg"]?.isFinite() != true -> "missing_local_landmarks"
            else -> null
        }
        val local = if (mode == ReplayMode.CHEST_PRESS) chest.accept(frame.timestampMs, frame.epoch,
            frame.metrics["trunk_lean_deg"], frame.metrics["elbow_flexion_deg"], chestQuality, sideView) else null
        val reason = if (mode == ReplayMode.CHEST_PRESS) (if (!sideView) "camera_not_confirmed_fixed" else chestQuality) else
            if (exercise == null) "unsupported_exercise" else quality ?: exercise.signalReason(frame.metrics, sideView)
        if (reason == null) eligible++
        val count = if (exercise != null) counter.accept(LiveCounter.Observation(frame.timestampMs, frame.epoch,
            frame.metrics[exercise.metric], reason)) else null
        val update = if (exercise != null) coach!!.accept(frame.timestampMs, frame.epoch, frame.metrics, quality, count!!.phase) else null
        val candidates = update?.candidates?.toMutableList() ?: mutableListOf()
        local?.issue?.let { candidates.add(it) }
        count?.event?.let { candidates.add(CoachEvent.count(it)) }
        val selected = gate.select(candidates, frame.timestampMs)
        if (selected != null) gate.delivered(selected, frame.timestampMs)
        fun event(e: CoachEvent) = JSONObject().put("id", e.id).put("ruleId", e.ruleId).put("ruleVersion", e.ruleVersion)
            .put("kind", e.kind.name)
            .put("text", e.detailedText).put("evidenceStartMs", e.evidenceStartMs).put("evidenceEndMs", e.evidenceEndMs)
            .put("expiresAtMs", e.expiresAtMs).put("metric", e.metric).put("measured", e.measured ?: JSONObject.NULL)
            .put("reference", e.reference ?: JSONObject.NULL).put("priority", e.priority)
        return JSONObject().put("videoTimeMs", frame.timestampMs).put("trackingEpoch", frame.epoch)
            .put("poseCountCappedAtTwo", frame.poseCount).put("qualityReason", frame.rejection ?: JSONObject.NULL)
            .put("evaluationReason", reason ?: JSONObject.NULL).put("assessmentScope", "local_issues_only")
            .put("localAnalysis", local?.let { JSONObject().put("status", it.status)
                .put("baselineTrunkDeg", it.baselineTrunk ?: JSONObject.NULL).put("deviationDeg", it.deviation ?: JSONObject.NULL)
                .put("referenceStartMs", it.referenceStartMs).put("referenceEndMs", it.referenceEndMs)
                .put("issue", it.issue?.let { cue -> event(cue) } ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("metrics", JSONObject().apply { frame.metrics.forEach { (key, value) -> put(key, value?.takeIf { it.isFinite() } ?: JSONObject.NULL) } })
            .put("points", JSONArray().apply { frame.points.forEach { put(it?.let { p -> JSONArray().put(p.first).put(p.second) } ?: JSONObject.NULL) } })
            .put("width", frame.bitmap.width).put("height", frame.bitmap.height).put("inferenceMs", frame.inferenceMs)
            .put("candidateCount", if (eligible == 0) JSONObject.NULL else count?.count ?: JSONObject.NULL)
            .put("phase", count?.phase?.name ?: JSONObject.NULL).put("calibrated", update?.calibrated ?: false)
            .put("standingReference", if (update?.calibrated == true) JSONObject()
                .put("startMs", update.calibrationStartMs).put("endMs", update.calibrationEndMs)
                .put("trunkLeanDeg", update.baselineTrunk ?: JSONObject.NULL)
                .put("upperArmRelativeToTrunkDeg", update.baselineArm ?: JSONObject.NULL) else JSONObject.NULL)
            .put("candidates", JSONArray().apply { candidates.forEach { put(event(it)) } })
            .put("scheduledFeedback", selected?.let { event(it).put("deliveryStatus", "offline_simulation_no_audio") } ?: JSONObject.NULL)
    }
}

internal data class ReplayResult(val folder: File, val report: JSONObject) {
    val rows: JSONArray get() = report.getJSONArray("observations")
    fun save() = File(folder, "report.json").writeText(report.toString(2))
}

internal suspend fun analyzeVideo(context: Context, uri: Uri, mode: ReplayMode, side: Geometry.Side,
                                  sideView: Boolean, folder: File, exerciseId: String? = null,
                                  onProgress: suspend (Int, Long, Long) -> Unit): ReplayResult {
    val library = ExerciseLibrary.load(context)
    val entry = library.find(exerciseId)
    require(exerciseId == null || entry != null) { "动作知识条目不存在" }
    require(entry == null || entry.mode == mode) { "所选动作与分析规则不匹配" }
    check(folder.isDirectory || folder.mkdirs())
    val digest = MessageDigest.getInstance("SHA-256")
    var byteCount = 0L
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            currentCoroutineContext().ensureActive()
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size); byteCount += size
        }
    } ?: error("无法读取所选视频")
    val sourceHash = digest.digest().joinToString("") { "%02x".format(it) }
    val rules = ReplayRules(mode, sideView, side)
    val rows = JSONArray()
    val guide = if (mode == ReplayMode.CHEST_PRESS) chestPressGuide(context) else null
    var duration = 0L
    val engine = PoseEngine(context, side)
    try {
        decodeVideo(context, uri) { bitmap, time, total ->
            val frame = engine.analyze(bitmap, time)
            val row = rules.accept(frame)
            if (guide != null) row.put("technicalChecks", JSONArray().apply {
                val checks = guide.getJSONArray("checks")
                for (i in 0 until checks.length()) {
                    val check = checks.getJSONObject(i)
                    val metric = if (check.isNull("metric")) null else check.getString("metric")
                    val body = check.getString("id") == "body_support"
                    put(JSONObject().put("id", check.getString("id"))
                        .put("status", if (body) row.getJSONObject("localAnalysis").getString("status") else "manual_review_required")
                        .put("reason", check.getString("missingCapability"))
                        .put("observedMetric", metric ?: JSONObject.NULL)
                        .put("observedValue", metric?.let { frame.metrics[it] } ?: JSONObject.NULL)
                        .put("observationQualityReason", frame.rejection ?: JSONObject.NULL))
                }
            })
            val name = "frame-${rows.length()}.jpg"
            File(folder, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            row.put("image", name); rows.put(row); duration = total
            onProgress(rows.length(), time, total)
        }
    } finally { engine.close() }
    check(rows.length() > 0) { "视频没有可解码画面" }
    val reasons = JSONObject()
    var single = 0; var elbow = 0; var knee = 0; var eligible = 0; var scheduled = 0
    for (i in 0 until rows.length()) {
        val row = rows.getJSONObject(i)
        if (row.getInt("poseCountCappedAtTwo") == 1) single++
        if (!row.getJSONObject("metrics").isNull("elbow_flexion_deg")) elbow++
        if (!row.getJSONObject("metrics").isNull("knee_flexion_deg")) knee++
        if (row.isNull("evaluationReason")) eligible++
        if (!row.isNull("scheduledFeedback")) scheduled++
        val reason = if (row.isNull("qualityReason")) "usable" else row.getString("qualityReason")
        reasons.put(reason, reasons.optInt(reason) + 1)
    }
    val report = JSONObject().put("schemaVersion", 1).put("analysisKind", "offline_causal_video_replay")
        .put("sourceSha256", sourceHash).put("sourceByteCount", byteCount)
        .put("pixelConversion", "YUV420_to_JPEG95_then_RGB_max_dimension640_rotated")
        .put("countRuleVersion", LiveCounter.RULE_VERSION).put("coachRuleVersion", "coach-research-1")
        .put("mode", mode.name).put("side", side.name)
        .put("sideViewConfirmed", if (mode == ReplayMode.CHEST_PRESS) JSONObject.NULL else sideView)
        .put("cameraFixedConfirmed", if (mode == ReplayMode.CHEST_PRESS) sideView else JSONObject.NULL)
        .put("durationMs", duration).put("sampleMinimumIntervalMs", 100).put("timestampSource", "decoder_presentation_time_us_truncated_to_ms")
        .put("modelId", "pose-landmarker-lite-f16-v1").put("modelSha256", "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a")
        .put("poseThreshold", .6).put("maximumPoses", 2).put("assessmentScope", "local_issues_only")
        .put("chestLocalRuleVersion", ChestPressCoach.VERSION)
        .put("summary", JSONObject().put("sampledFrames", rows.length()).put("singlePersonFrames", single)
            .put("elbowAngleAvailableFrames", elbow).put("kneeAngleAvailableFrames", knee)
            .put("ruleEligibleFrames", eligible).put("scheduledFeedbackCount", scheduled).put("qualityReasons", reasons))
        .put("technicalReference", guide ?: JSONObject.NULL)
        .put("exerciseReference", entry?.let { library.reference(it) } ?: JSONObject.NULL)
        .put("observations", rows).put("annotations", JSONArray())
    return ReplayResult(folder, report).also { it.save() }
}

internal fun chestPressGuide(context: Context) = JSONObject(context.assets.open("machine-chest-press.v1.json")
    .bufferedReader().use { it.readText() })

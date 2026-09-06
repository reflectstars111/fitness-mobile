package fitness.mobile

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import fitness.mobile.core.Exercise
import fitness.mobile.core.Geometry
import fitness.mobile.core.LiveCounter
import fitness.mobile.core.LiveCoach
import fitness.mobile.core.CoachEvent
import fitness.mobile.core.FeedbackGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TrainingModel @JvmOverloads constructor(
    app: Application, storageName: String = "workout-local-v1",
    private val monotonicNow: () -> Long = SystemClock::uptimeMillis
) : AndroidViewModel(app) {
    var consent by mutableStateOf(false)
    var placement by mutableStateOf(false)
    var cameraOpen by mutableStateOf(false)
    var front by mutableStateOf(false)
    var side by mutableStateOf(Geometry.Side.LEFT)
    var exercise by mutableStateOf(Exercise.SQUAT)
    var voice by mutableStateOf(true)
    var coachingEnabled by mutableStateOf(true)
    var speakAngles by mutableStateOf(true)
    var squatTargetEnabled by mutableStateOf(false)
    var squatTarget by mutableStateOf(80f)
    var coachMessage by mutableStateOf("开始后先自然伸展，记录本次准备姿势")
    var calibrated by mutableStateOf(false)
    var armDeviation by mutableStateOf<Double?>(null)
    var trunkDeviation by mutableStateOf<Double?>(null)
    var highlightedRule by mutableStateOf<String?>(null)
    var active by mutableStateOf(false)
    var hasSet by mutableStateOf(false)
    var frame by mutableStateOf<Frame?>(null)
    var count by mutableStateOf(0)
    var eligibleFrames by mutableStateOf(0)
    var message by mutableStateOf("选择动作并确认机位后，打开相机")
    var history by mutableStateOf<List<String>>(emptyList())
    var speechStatus by mutableStateOf("语音初始化中")
    private var counter = LiveCounter()
    private var coach = LiveCoach(exercise, null)
    private var feedbackGate = FeedbackGate()
    private val prefs = app.getSharedPreferences(storageName, 0)
    private var events = JSONArray()
    private var feedback = JSONArray()
    private var references = JSONArray()
    private var referenceRecordedAt: Long? = null
    private var sessionId = ""
    private var startedAt = 0L
    private var startedWall = 0L
    private var lastReceived = 0L
    private var resumedAt = 0L
    private val speaker = Speaker(app, { pause("音频焦点丢失，训练已暂停") }, { id, outcome, at ->
        if (hasSet) for (i in 0 until feedback.length()) {
            val item = feedback.getJSONObject(i)
            if (item.getString("id") == id) {
                item.put("deliveryStatus", outcome).put("deliveryUpdatedAtMs", at)
                if (outcome == "spoken") {
                    item.put("spokenAtUptimeMs", at)
                    if (item.getString("kind") == "COUNT") for (j in 0 until events.length()) {
                        val rep = events.getJSONObject(j)
                        if (rep.getLong("evidenceEndMs") == item.getLong("evidenceEndMs")) rep.put("spokenAtUptimeMs", at)
                    }
                }
                saveDraft(); break
            }
        }
    })

    init { loadHistory() }
    fun startSet() {
        if (!cameraOpen || !consent || !placement) return
        if (!hasSet) {
            counter = LiveCounter(); count = 0; eligibleFrames = 0; events = JSONArray(); feedback = JSONArray()
            references = JSONArray(); referenceRecordedAt = null
            coach = LiveCoach(exercise, if (exercise == Exercise.SQUAT && squatTargetEnabled) squatTarget.toDouble() else null)
            feedbackGate = FeedbackGate()
            sessionId = UUID.randomUUID().toString()
            startedAt = monotonicNow(); startedWall = System.currentTimeMillis()
            hasSet = true
        }
        counter.pause("resume"); coach.pause(); calibrated = false
        resumedAt = monotonicNow(); active = true
        saveDraft()
        message = "先保持伸展姿势，再开始动作"
    }
    fun receive(value: Frame) {
        frame = value; lastReceived = monotonicNow(); speechStatus = speaker.status
        val now = monotonicNow()
        val quality = when {
            now - value.timestampMs > 350 || value.timestampMs > now -> "stale_frame"
            !placement -> "not_eligible_front_view"
            value.rejection != null -> value.rejection
            value.metrics[exercise.metric]?.isFinite() != true || value.metrics["trunk_lean_deg"]?.isFinite() != true -> "missing_landmarks"
            exercise == Exercise.BICEPS_CURL && value.metrics["upper_arm_tilt_deg"]?.isFinite() != true -> "missing_landmarks"
            else -> value.rejection
        }
        val reason = quality ?: exercise.signalReason(value.metrics, placement)
        if (!active) { if (!hasSet) message = reasonText(reason) ?: "画面可用，可以开始训练"; return }
        if (value.timestampMs < resumedAt) return
        if (reason == null) eligibleFrames++
        val result = counter.accept(LiveCounter.Observation(value.timestampMs, value.epoch,
            value.metrics[exercise.metric], reason))
        count = result.count
        message = reasonText(result.reason) ?: when (result.phase) {
            LiveCounter.Phase.UNAVAILABLE -> "暂停评价"
            LiveCounter.Phase.PREPARING -> "保持伸展，准备开始"
            LiveCounter.Phase.READY -> "已准备，可以开始下一次"
            LiveCounter.Phase.MOVING -> "动作进行中"
            LiveCounter.Phase.PEAK -> "已观察到屈曲，缓慢返回"
            LiveCounter.Phase.RETURNING -> "返回伸展姿势"
        }
        val available = mutableListOf<CoachEvent>()
        if (coachingEnabled) {
            val update = coach.accept(value.timestampMs, value.epoch, value.metrics, quality, result.phase)
            if (update.calibrated && !calibrated) {
                referenceRecordedAt = update.calibrationEndMs
                references.put(JSONObject().put("evidenceStartMs", update.calibrationStartMs)
                    .put("evidenceEndMs", update.calibrationEndMs)
                    .put("trunkLeanDeg", update.baselineTrunk ?: JSONObject.NULL)
                    .put("upperArmRelativeToTrunkDeg", update.baselineArm ?: JSONObject.NULL))
            }
            if (!update.calibrated) referenceRecordedAt = null
            calibrated = update.calibrated; coachMessage = update.status
            armDeviation = update.armDeviation; trunkDeviation = update.trunkDeviation
            available.addAll(update.candidates)
        }
        highlightedRule = available.firstOrNull { it.kind == CoachEvent.Kind.MOVEMENT || it.kind == CoachEvent.Kind.TARGET }?.ruleId
        result.event?.let {
            events.put(JSONObject().put("count", it.count).put("evidenceStartMs", it.evidenceStartMs)
                .put("evidenceEndMs", it.evidenceEndMs).put("expiresAtMs", it.expiresAtMs)
                .put("ruleVersion", it.ruleVersion).put("spokenAtUptimeMs", JSONObject.NULL))
            saveDraft()
            available.add(CoachEvent.count(it))
        }
        speaker.retain(available, quality == null && result.reason != "invalid_timestamp")
        deliver(available, now)
    }
    private fun deliver(available: List<CoachEvent>, now: Long) {
        val event = feedbackGate.select(available, now) ?: return
        val useSpeech = voice && speaker.isReady
        if (useSpeech && !speaker.say(event, speakAngles)) return
        feedbackGate.delivered(event, now)
        if (event.kind != CoachEvent.Kind.COUNT) coachMessage = event.detailedText
        feedback.put(JSONObject().put("id", event.id).put("sessionId", sessionId).put("kind", event.kind.name)
            .put("ruleId", event.ruleId).put("ruleVersion", event.ruleVersion).put("priority", event.priority)
            .put("evidenceStartMs", event.evidenceStartMs).put("evidenceEndMs", event.evidenceEndMs)
            .put("expiresAtMs", event.expiresAtMs).put("measured", event.measured ?: JSONObject.NULL)
            .put("reference", event.reference ?: JSONObject.NULL).put("metric", event.metric)
            .put("referenceRecordedAtMs", referenceRecordedAt ?: JSONObject.NULL)
            .put("text", if (speakAngles) event.detailedText else event.text)
            .put("deliveryStatus", if (useSpeech) "submitted" else "text_only").put("spokenAtUptimeMs", JSONObject.NULL))
        saveDraft()
    }
    fun tick() {
        speechStatus = speaker.status
        if (cameraOpen && lastReceived > 0 && monotonicNow() - lastReceived > 600) {
            counter.pause("stream_timeout"); speaker.stop(); frame = null
            coach.pause(); calibrated = false; highlightedRule = null; armDeviation = null; trunkDeviation = null
            coachMessage = "画面中断，动作提醒已停止"
            message = "画面中断，暂停评价；恢复后先站稳"
        }
        if (active && monotonicNow() - startedAt > 30 * 60 * 1000) finish()
    }
    fun pause(reason: String = "已暂停，继续后需重新准备") {
        active = false; counter.pause("paused"); coach.pause(); calibrated = false; speaker.stop(); message = reason
        highlightedRule = null; armDeviation = null; trunkDeviation = null; coachMessage = "已暂停；继续后重新记录准备姿势"
        if (hasSet) saveDraft()
    }
    fun closeCamera() { pause(); cameraOpen = false; frame = null; lastReceived = 0 }
    fun fail(reason: String) { closeCamera(); message = reason }
    fun mute() { voice = !voice; if (!voice) speaker.stop() }
    fun toggleCoaching() {
        coachingEnabled = !coachingEnabled; coach.pause(); calibrated = false; speaker.stop()
        highlightedRule = null; armDeviation = null; trunkDeviation = null
        coachMessage = if (coachingEnabled) "请保持自然伸展，重新记录准备姿势" else "动作提醒已关闭"
    }
    fun testAudio() {
        if (active) return
        val now = monotonicNow()
        speaker.stop()
        val text = "语音测试，请确认耳机能够听清"
        if (!speaker.say(CoachEvent("audio:test", CoachEvent.Kind.SETUP, 100, now, now, text, text, "audio_test", null, null), false))
            message = speaker.status
    }
    private fun report(state: String): JSONObject = JSONObject()
        .put("schemaVersion", 2).put("sessionId", sessionId).put("exercise", exercise.id)
        .put("label", exercise.label).put("side", side.name).put("frontCamera", front)
        .put("startedAtUtcMs", startedWall).put("durationMs", monotonicNow() - startedAt)
        .put("candidateCount", if (eligibleFrames == 0) JSONObject.NULL else count)
        .put("eligibleFrames", eligibleFrames)
        .put("countStatus", if (eligibleFrames == 0) "not_eligible_no_observations" else "candidate_2d")
        .put("status", state).put("events", events)
        .put("feedback", feedback).put("coachingRuleVersion", "coach-research-1")
        .put("standingReferences", references)
        .put("coachingEnabled", coachingEnabled).put("speakAngles", speakAngles)
        .put("squatTargetFlexionDeg", if (squatTargetEnabled && exercise == Exercise.SQUAT) squatTarget else JSONObject.NULL)
        .put("ruleVersion", LiveCounter.RULE_VERSION).put("modelId", "pose-landmarker-lite-f16-v1")
        .put("modelSha256", "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a")
    private fun saveDraft() { prefs.edit().putString("draft", report("interrupted").toString()).apply() }
    fun finish() {
        if (!hasSet) return
        pause()
        val list = JSONArray(prefs.getString("history", "[]"))
        list.put(report("completed"))
        val bounded = JSONArray()
        for (i in maxOf(0, list.length() - 50) until list.length()) bounded.put(list.get(i))
        prefs.edit().putString("history", bounded.toString()).remove("draft").apply()
        hasSet = false; loadHistory()
        message = if (eligibleFrames == 0) "本组已保存：无可用观测，未评价" else "本组已保存：$count 次候选"
    }
    private fun loadHistory() {
        val list = JSONArray(prefs.getString("history", "[]"))
        val rows = mutableListOf<String>()
        prefs.getString("draft", null)?.let { raw ->
            val d = JSONObject(raw)
            rows += "上次中断 · ${d.getString("label")} · ${countText(d)}（不自动续计）"
        }
        for (i in list.length() - 1 downTo maxOf(0, list.length() - 5)) {
            val item = list.getJSONObject(i)
            rows += "${item.getString("label")} · ${countText(item)}"
        }
        history = rows
    }
    fun export(): String = JSONObject().put("schemaVersion", 2)
        .put("history", JSONArray(prefs.getString("history", "[]")))
        .put("interrupted", prefs.getString("draft", null)?.let { JSONObject(it) }).toString(2)
    fun deleteHistory() { if (!hasSet) { prefs.edit().clear().apply(); loadHistory() } }
    private fun countText(item: JSONObject) = if (item.isNull("candidateCount")) "无可用观测，未评价"
        else "${item.getInt("candidateCount")} 次候选"
    override fun onCleared() { speaker.close() }
}

fun reasonText(reason: String?): String? = when (reason) {
    null -> null
    "no_person" -> "未看到完整身体，请调整机位"
    "multiple_people" -> "画面中有多人，请保持单人训练"
    "check_side_view", "not_eligible_front_view" -> "请将手机固定在身体侧面"
    "stale_frame", "observation_gap" -> "处理或画面中断，暂停评价"
    "tracking_reset" -> "位置变化，请重新站稳"
    "not_upright_upper_arm_protocol" -> "弯举机位或姿态不适配，暂停评价"
    "incomplete_excursion" -> "本次未形成完整候选，继续准备"
    "duration_exceeded" -> "本次动作超时，请重新准备"
    else -> "关键部位看不清，暂停评价"
}

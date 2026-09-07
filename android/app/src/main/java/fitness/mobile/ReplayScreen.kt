package fitness.mobile

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import fitness.mobile.core.Geometry
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Composable
internal fun ReplayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(ReplayMode.CHEST_PRESS) }
    val uriHandler = LocalUriHandler.current
    val guide = remember { chestPressGuide(context) }
    var side by remember { mutableStateOf(Geometry.Side.LEFT) }
    var sideView by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("选择不超过 2 分钟的视频，在本机分析。") }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<ReplayResult?>(null) }
    var selected by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<Frame?>(null) }
    var playing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var reviewCheck by remember { mutableStateOf("general") }
    var exportText by remember { mutableStateOf("") }
    val folders = remember { mutableListOf<File>() }
    // Analysis is deliberately cancelled on leaving this screen. Temp frames are not training history.
    DisposableEffect(Unit) { onDispose { job?.cancel(); folders.forEach { it.deleteRecursively() } } }
    BackHandler { job?.cancel(); onBack() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            status = try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray()) } ?: error("无法写入文件") }
                "分析与人工备注已导出"
            } catch (_: Exception) { "导出失败，请重试" }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            playing = false; result = null; preview = null; selected = 0; busy = true; progress = 0f
            val chosenMode = mode; val chosenSide = side; val chosenView = sideView
            val folder = File(context.cacheDir, "replay-${UUID.randomUUID()}").also { folders.add(it) }
            job = scope.launch {
                try {
                    val analysis = withContext(Dispatchers.Default) {
                        analyzeVideo(context, uri, chosenMode, chosenSide, chosenView, folder) { count, time, duration ->
                            withContext(Dispatchers.Main) {
                                progress = (time.toFloat() / duration).coerceIn(0f, 1f)
                                status = "已分析 $count 帧 · %.1f / %.1f 秒".format(time / 1000.0, duration / 1000.0)
                            }
                        }
                    }
                    result = analysis; progress = 1f; status = "分析完成，可拖动时间轴或播放分析画面"
                } catch (e: CancellationException) { status = "分析已取消"; throw e }
                catch (e: Exception) { status = "分析失败：${e.message ?: e.javaClass.simpleName}" }
                finally { busy = false; if (result == null) withContext(NonCancellable + Dispatchers.IO) { folder.deleteRecursively() } }
            }
        }
    }
    val row = result?.rows?.optJSONObject(selected)
    LaunchedEffect(result, selected) {
        playing = playing && result != null
        note = ""
        preview = null
        val current = result ?: return@LaunchedEffect
        val data = current.rows.getJSONObject(selected)
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(current.folder, data.getString("image")).path) }
        if (bitmap != null) {
            val points = data.getJSONArray("points")
            val metrics = data.getJSONObject("metrics")
            preview = Frame(bitmap, (0 until points.length()).map { i -> if (points.isNull(i)) null else
                points.getJSONArray(i).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } },
                metrics.keys().asSequence().associateWith { if (metrics.isNull(it)) null else metrics.getDouble(it) },
                data.getLong("videoTimeMs"), data.getLong("trackingEpoch"),
                if (data.isNull("qualityReason")) null else data.getString("qualityReason"), data.getLong("inferenceMs"))
        }
    }
    LaunchedEffect(playing, result) {
        val data = result ?: return@LaunchedEffect
        while (playing && selected < data.rows.length() - 1) {
            val time = data.rows.getJSONObject(selected).getLong("videoTimeMs")
            val next = data.rows.getJSONObject(selected + 1).getLong("videoTimeMs")
            delay((next - time).coerceAtLeast(1)); selected++
        }
        playing = false
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { job?.cancel(); onBack() }) { Text("返回训练") }
        Text("视频回放分析", style = MaterialTheme.typography.headlineMedium)
        Text("查看系统看到了什么，以及哪些判断还做不到。")
        if (result == null && !busy) {
            ReplayMode.entries.forEach { item -> FilterChip(mode == item, onClick = { mode = item }, label = { Text(item.label) }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Geometry.Side.values().forEach { item ->
                FilterChip(side == item, onClick = { side = item }, label = { Text(if (item == Geometry.Side.LEFT) "身体左侧" else "身体右侧") })
            } }
            if (mode.exercise != null || mode == ReplayMode.CHEST_PRESS) Row {
                Checkbox(sideView, onCheckedChange = { sideView = it }); Text(
                    if (mode == ReplayMode.CHEST_PRESS) "机位固定，可比较本组躯干姿态变化（手持移动视频请勿勾选）"
                    else "我确认素材是所选动作的固定侧面视角", Modifier.weight(1f))
            }
            Text("按部位查找问题：固定机位可检查推胸中的躯干姿态变化；其他要点结合画面逐项复核。角度为二维估计。")
            Text("视频仅在本机读取；分析帧临时缓存，离开此页清理。不会上传或写入训练历史。", style = MaterialTheme.typography.bodySmall)
        }
        Text(status)
        if (busy) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { job?.cancel() }) { Text("取消分析") }
        } else if (result == null) Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("选择视频并分析") }
        result?.let { current ->
            val summary = current.report.getJSONObject("summary")
            val total = summary.getInt("sampledFrames")
            Text("能力检查", style = MaterialTheme.typography.titleLarge)
            Text("抽样 $total 帧 · 单人检出 ${summary.getInt("singlePersonFrames")} 帧\n肘角可计算 ${summary.getInt("elbowAngleAvailableFrames")} 帧 · 规则可评价 ${summary.getInt("ruleEligibleFrames")} 帧")
            Text(if (mode == ReplayMode.OBSERVE) "当前动作仅展示观测，可按时间点标记需要复核的部位。"
                else "按部位展示实验提醒与证据；没有提醒的部位仍可能存在未识别问题。")
            Text("全身可见性参考：" + summary.getJSONObject("qualityReasons").let { reasons ->
                reasons.keys().asSequence().joinToString("；") { key -> "${if (key == "usable") "基础门槛通过" else reasonText(key)} ${reasons.getInt(key)} 帧" }
            }, style = MaterialTheme.typography.bodySmall)
            AnalysisPreview(preview, false, row?.optJSONObject("scheduledFeedback")?.optString("ruleId"), side)
            if (total > 1) Slider(selected.toFloat(), onValueChange = { playing = false; selected = it.toInt().coerceIn(0, total - 1) }, valueRange = 0f..(total - 1).toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (selected == total - 1) selected = 0; playing = !playing }, enabled = total > 1) { Text(if (playing) "暂停回放" else "播放分析画面") }
                Text("%.2f 秒".format((row?.getLong("videoTimeMs") ?: 0) / 1000.0))
            }
            row?.let { data ->
                val quality = if (data.isNull("qualityReason")) null else data.getString("qualityReason")
                Text("全身观测：${reasonText(quality) ?: "单人及基础可见性门槛通过"}")
                val metrics = data.getJSONObject("metrics")
                fun angle(key: String) = if (metrics.isNull(key)) "不可用" else "约 %.0f°".format(180 - metrics.getDouble(key))
                Text("肘夹角 ${angle("elbow_flexion_deg")} · 膝夹角 ${angle("knee_flexion_deg")}")
                Text("看不清的部位单独暂停评价；可见部位按适用规则检查。", style = MaterialTheme.typography.bodySmall)
                Text("规则状态：" + when (val reason = if (data.isNull("evaluationReason")) "" else data.getString("evaluationReason")) {
                    "unsupported_exercise" -> "暂无该动作的局部提醒规则"
                    "camera_not_confirmed_fixed" -> "机位未确认固定，暂停躯干变化提醒"
                    "missing_local_landmarks" -> "所选侧肩、肘、腕或髋不可见，暂停该部位提醒"
                    "" -> if (mode == ReplayMode.CHEST_PRESS) "上肢与躯干观测可用" else "可进入实验规则 · ${data.optString("phase")}"
                    else -> reasonText(reason)
                })
                val feedback = data.optJSONObject("scheduledFeedback")
                Text(feedback?.getString("text") ?: "本帧无模拟投递提醒")
                if (feedback != null) Text("证据 %.2f–%.2f 秒 · 仅模拟调度，不播放历史语音".format(
                    feedback.getLong("evidenceStartMs") / 1000.0, feedback.getLong("evidenceEndMs") / 1000.0), style = MaterialTheme.typography.bodySmall)
                data.optJSONObject("localAnalysis")?.let { local ->
                    Text("躯干稳定性：" + when (local.getString("status")) {
                        "camera_not_confirmed_fixed" -> "需要固定机位，避免把相机移动当成身体变化"
                        "local_landmarks_unavailable" -> "当前局部关键点不足，暂停这一项"
                        "recording_reference" -> "等待至少 1.2 秒稳定姿势作为本组参考"
                        "reference_ready" -> "本组参考已记录，允许自然靠背倾斜"
                        "local_deviation_candidate" -> "发现持续姿态变化，检查身体支撑"
                        "no_sustained_local_deviation" -> "当前没有持续躯干变化候选"
                        else -> "观测中断，重新记录参考"
                    })
                    local.optJSONObject("issue")?.let { Text(it.getString("text")) }
                }
            }
            if (mode == ReplayMode.CHEST_PRESS) {
                Text("器械推胸复核依据", style = MaterialTheme.typography.titleLarge)
                Text("无需填写器械品牌或型号。以下是通用技术要点，不是统一角度合格线。点击项目可关联本帧备注。")
                val checks = guide.getJSONArray("checks")
                for (i in 0 until checks.length()) {
                    val check = checks.getJSONObject(i)
                    FilterChip(reviewCheck == check.getString("id"), onClick = { playing = false; reviewCheck = check.getString("id") },
                        label = { Text(check.getString("title")) })
                    Text(check.getString("guidance"))
                    Text((if (check.getString("id") == "body_support") "局部实验提醒：躯干相对准备姿势变化；" else "待复核：") +
                        check.getString("missingCapability"), style = MaterialTheme.typography.bodySmall)
                }
                val sources = guide.getJSONArray("sources")
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    TextButton(onClick = { playing = false; runCatching { uriHandler.openUri(source.getString("url")) } }) { Text(source.getString("title")) }
                }
            }
            val timeline = (0 until total).filter { !current.rows.getJSONObject(it).isNull("scheduledFeedback") }
            if (timeline.isNotEmpty()) {
                Text("提醒时间点（模拟）", style = MaterialTheme.typography.titleMedium)
                timeline.take(40).forEach { index ->
                    val item = current.rows.getJSONObject(index)
                    TextButton(onClick = { playing = false; selected = index }) {
                        Text("%.2f 秒 · %s".format(item.getLong("videoTimeMs") / 1000.0, item.getJSONObject("scheduledFeedback").getString("text")))
                    }
                }
                if (timeline.size > 40) Text("界面展示前 40 条，完整时间点见导出记录。")
            }
            OutlinedTextField(note, onValueChange = { playing = false; note = it }, label = { Text("本帧人工复核备注") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                current.report.getJSONArray("annotations").put(JSONObject().put("videoTimeMs", row!!.getLong("videoTimeMs"))
                    .put("checkId", reviewCheck).put("note", note.trim()).put("source", "user_unverified"))
                current.save(); note = ""; status = "备注已记录；导出后可用于人工对照"
            }, enabled = note.isNotBlank() && !playing) { Text("记录备注") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { playing = false; exportText = current.report.toString(2); export.launch("fitness-replay.json") }) { Text("导出分析 JSON") }
                TextButton(onClick = { playing = false; preview = null; result = null; current.folder.deleteRecursively(); status = "选择下一段素材" }) { Text("清除并换视频") }
            }
        }
    }
}

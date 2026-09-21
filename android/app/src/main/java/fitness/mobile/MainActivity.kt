package fitness.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import fitness.mobile.core.Exercise
import fitness.mobile.core.Geometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8BE3C3),
                background = Color(0xFF101916), surface = Color(0xFF192720),
                secondaryContainer = Color(0xFF274538), onSecondaryContainer = Color(0xFFB8F6DB),
                surfaceContainerHighest = Color(0xFF23392F))) {
                Surface(Modifier.fillMaxSize()) {
                    var replay by remember { mutableStateOf(false) }
                    var library by remember { mutableStateOf(false) }
                    var exerciseId by remember { mutableStateOf<String?>(null) }
                    if (library) ExerciseLibraryScreen(onBack = { library = false }, onSelect = {
                        exerciseId = it; library = false; replay = true
                    })
                    else if (replay) ReplayScreen(initialExerciseId = exerciseId, onBack = { replay = false })
                    else TrainingScreen(onReplay = { exerciseId = null; replay = true }, onLibrary = { library = true })
                }
            }
        }
    }
}

@Composable
private fun TrainingScreen(model: TrainingModel = viewModel(), onReplay: () -> Unit, onLibrary: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var exportText by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法打开导出文件") }.isSuccess
            }
            model.message = if (ok) "训练记录已导出" else "导出失败，请重试"
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.cameraOpen = true else model.message = "需要相机权限；可在系统设置中允许后重试"
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) model.closeCamera()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(model.active) {
        val window = (context as? ComponentActivity)?.window
        if (model.active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(model.cameraOpen, model.front, model.side) {
        var alive = true
        val main = Handler(Looper.getMainLooper())
        val pending = AtomicReference<Frame?>(null)
        val posted = AtomicBoolean(false)
        val camera = if (model.cameraOpen) PoseCamera(context, owner, model.front, model.side,
            { value ->
                pending.set(value)
                if (posted.compareAndSet(false, true)) main.post {
                    val latest = pending.getAndSet(null)
                    posted.set(false)
                    if (alive && model.cameraOpen && latest != null) model.receive(latest)
                }
            },
            { error -> main.post { if (alive) model.fail(error) } }).also { it.start() } else null
        onDispose { alive = false; pending.set(null); camera?.close() }
    }
    LaunchedEffect(Unit) { while (true) { delay(250); model.tick() } }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FITNESS MOBILE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("让每一组更专注", style = MaterialTheme.typography.headlineLarge)
        Text("视觉陪练实验版 · 观察、提醒、复核", style = MaterialTheme.typography.bodyMedium)
        if (!model.cameraOpen && !model.hasSet) {
            OutlinedButton(onClick = onReplay, modifier = Modifier.fillMaxWidth()) { Text("导入视频 · 回放分析") }
            OutlinedButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth()) { Text("动作知识库 · 胸背肩腿等") }
            Text("选择动作", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Exercise.SQUAT, Exercise.BICEPS_CURL).forEach { exercise ->
                    FilterChip(selected = model.exercise == exercise, onClick = { model.exercise = exercise },
                        label = { Text(exercise.label) })
                }
            }
            Text(model.exercise.instructions)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !model.front, onClick = { model.front = false }, label = { Text("后置相机") })
                FilterChip(selected = model.front, onClick = { model.front = true }, label = { Text("前置相机") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Geometry.Side.values().forEach { side ->
                    FilterChip(selected = model.side == side, onClick = { model.side = side },
                        label = { Text(if (side == Geometry.Side.LEFT) "观察左侧" else "观察右侧") })
                }
            }
            if (model.exercise == Exercise.SQUAT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(model.squatTargetEnabled, onCheckedChange = { model.squatTargetEnabled = it })
                    Spacer(Modifier.width(8.dp)); Text("自设深蹲幅度目标")
                }
                if (model.squatTargetEnabled) {
                    Text("目标屈膝 ${model.squatTarget.toInt()}°（伸直为 0°）")
                    Slider(model.squatTarget, onValueChange = { model.squatTarget = it }, valueRange = 40f..120f, steps = 15)
                    Text("这是你选择的训练目标，不是通用合格角度；只在舒适范围内调整。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(model.consent, onCheckedChange = { model.consent = it })
                Text("我同意在本机处理相机画面；不保存或上传视频", modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(model.placement, onCheckedChange = { model.placement = it })
                Text("手机已固定在侧面，画面内只有我一人且全身入镜", modifier = Modifier.weight(1f))
            }
        }
        if (model.cameraOpen) {
            AnalysisPreview(model.frame, model.front, model.highlightedRule, model.side)
            Text("${model.exercise.label} · ${if (model.side == Geometry.Side.LEFT) "左侧" else "右侧"} · 姿态分析画面",
                style = MaterialTheme.typography.labelMedium)
        } else {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    model.cameraOpen = true else permission.launch(Manifest.permission.CAMERA)
            }, enabled = model.consent && model.placement, modifier = Modifier.fillMaxWidth()) { Text("打开相机") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (model.active) model.pause() else model.startSet() },
                enabled = model.cameraOpen, modifier = Modifier.weight(1f)) {
                Text(if (model.active) "暂停" else if (model.hasSet) "继续本组" else "开始一组")
            }
            OutlinedButton(onClick = { model.finish() }, enabled = model.hasSet) { Text("结束保存") }
        }
        if (model.cameraOpen || model.hasSet) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("实时动作提醒 · 实验", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(model.coachMessage)
                if (model.calibrated && model.coachingEnabled) {
                    model.armDeviation?.let { Text("上臂相对准备姿势偏移约 ${it.toInt()}°", style = MaterialTheme.typography.bodySmall) }
                    if (model.exercise == Exercise.BICEPS_CURL) model.trunkDeviation?.let {
                        Text("躯干相对准备姿势偏移约 ${it.toInt()}°", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本组候选次数", style = MaterialTheme.typography.labelLarge)
                Text(if (model.eligibleFrames == 0) "—" else "${model.count}",
                    style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Text(model.message, style = MaterialTheme.typography.titleMedium)
                model.frame?.let { f ->
                    val angle = f.metrics[model.exercise.metric]
                    Text("${if (model.exercise == Exercise.SQUAT) "膝" else "肘"}夹角 ${angle?.let { "约 %.0f°".format(180 - it) } ?: "不可用"} · 屈曲 ${angle?.let { "%.0f°".format(it) } ?: "不可用"}",
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(model.coachingEnabled, onCheckedChange = { model.toggleCoaching() })
            Spacer(Modifier.width(8.dp)); Text("动作提醒（需稳定准备姿势）", modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(model.voice, onCheckedChange = { model.mute() })
            Spacer(Modifier.width(8.dp)); Text("语音 · ${model.speechStatus}", modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(model.speakAngles, onCheckedChange = { model.speakAngles = it })
            Text("语音包含大约角度")
            TextButton(onClick = { model.testAudio() }, enabled = !model.active) { Text("测试耳机") }
        }
        if (model.cameraOpen) TextButton(onClick = { model.closeCamera() }) { Text("关闭相机") }
        Text("近期训练", style = MaterialTheme.typography.titleLarge)
        if (model.history.isEmpty()) Text("完成第一组后，记录会保存在本机。")
        model.history.forEach { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportText = model.export(); export.launch("fitness-sessions.json") },
                enabled = !model.active) { Text("导出记录") }
            TextButton(onClick = { model.deleteHistory() }, enabled = !model.hasSet) { Text("删除历史") }
        }
        Text("角度来自二维画面估计；未触发提醒不代表动作达标。本版尚未完成教练与真人校准。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AnalysisPreview(frame: Frame?, front: Boolean, rule: String?, side: Geometry.Side) {
    val ratio = frame?.let { it.bitmap.width.toFloat() / it.bitmap.height } ?: (3f / 4f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(280.dp).background(Color.Black), contentAlignment = Alignment.Center) {
        val fitWidth = minOf(maxWidth, 280.dp * ratio)
        if (frame == null) Text("正在准备姿态画面…", color = Color.White)
        else Box(Modifier.size(fitWidth, fitWidth / ratio).graphicsLayer { scaleX = if (front) -1f else 1f }) {
            Image(frame.bitmap.asImageBitmap(), "相机姿态分析画面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val edges = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
                    11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)
                fun point(p: Pair<Float, Float>) = Offset(p.first * size.width, p.second * size.height)
                val selected = when (rule) {
                    "curl:arm" -> if (side == Geometry.Side.LEFT) setOf(11, 13) else setOf(12, 14)
                    "curl:trunk" -> if (side == Geometry.Side.LEFT) setOf(11, 23) else setOf(12, 24)
                    "squat:target", "squat:goal_reached" -> if (side == Geometry.Side.LEFT) setOf(23, 25, 27) else setOf(24, 26, 28)
                    else -> emptySet()
                }
                for ((a, b) in edges) {
                    val first = frame.points[a]; val last = frame.points[b]
                    if (first != null && last != null) drawLine(
                        if (a in selected && b in selected) Color(0xFFFFC278) else Color(0xFF8BE3C3),
                        point(first), point(last), strokeWidth = 5f)
                }
                frame.points.filterNotNull().forEach { drawCircle(Color.White, 5f, point(it)) }
            }
        }
    }
}

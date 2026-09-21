package fitness.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun ExerciseLibraryScreen(onBack: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val library = remember { ExerciseLibrary.load(context) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("全部") }
    var detailId by remember { mutableStateOf<String?>(null) }
    val detail = library.find(detailId)
    fun back() { if (detailId != null) detailId = null else onBack() }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { back() }) { Text(if (detail != null) "返回动作库" else "返回") }
        Text("动作知识库", style = MaterialTheme.typography.headlineMedium)
        if (detail == null) {
            Text("${library.exercises.size} 个动作 · 技术要点、局部问题与拍摄条件 · 离线可用")
            OutlinedTextField(query, onValueChange = { query = it }, label = { Text("搜索动作或器械") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("全部") + library.groups).forEach { name -> FilterChip(group == name, onClick = { group = name }, label = { Text(name) }) }
            }
            val filtered = library.exercises.filter { (group == "全部" || it.group == group) &&
                (it.name + it.data.getString("variant")).contains(query.trim(), ignoreCase = true) }
            if (filtered.isEmpty()) Text("未找到匹配动作，请换个名称或分类。")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { entry ->
                    OutlinedCard(onClick = { detailId = entry.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${entry.group} · ${entry.name}", style = MaterialTheme.typography.titleMedium)
                            Text(entry.capability, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    ExerciseReference(library, detail)
                }
            }
            Button(onClick = { onSelect(detail.id) }, modifier = Modifier.fillMaxWidth()) { Text("按此动作分析视频") }
        }
    }
}

@Composable
internal fun ExerciseReference(library: ExerciseLibrary, entry: ExerciseEntry, selectedCheck: String? = null,
                               onCheck: ((String) -> Unit)? = null) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember(entry.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(entry.name, style = MaterialTheme.typography.titleLarge)
        Text(entry.data.getString("variant"))
        Text(entry.capability, color = MaterialTheme.colorScheme.primary)
        entry.data.getJSONArray("steps").strings().forEachIndexed { i, step -> Text("${i + 1}. $step") }
        Text(entry.data.getString("anglePolicy"), style = MaterialTheme.typography.bodySmall)
        Text("局部复核项目", style = MaterialTheme.typography.titleMedium)
        Text("以下是需要检查的项目，不代表已经在视频中发现问题。", style = MaterialTheme.typography.bodySmall)
        entry.data.getJSONArray("reviewTargets").strings().forEach { id ->
            val issue = library.issues.getValue(id)
            if (onCheck != null) FilterChip(selectedCheck == id, onClick = { onCheck(id) }, label = { Text(issue.getString("title")) })
            else Text(issue.getString("title"), style = MaterialTheme.typography.titleSmall)
            Text("${issue.getString("phase")}：${issue.getString("evidence")}")
            Text("确认问题后的提示：${issue.getString("cue")}")
            Text("拍摄：${issue.getString("view")}\n边界：${issue.getString("limitation")}", style = MaterialTheme.typography.bodySmall)
        }
        val sources = entry.data.getJSONArray("sources")
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            TextButton(onClick = { linkError = runCatching { uriHandler.openUri(source.getString("url")) }.isFailure }) { Text(source.getString("title")) }
        }
        if (linkError) Text("无法打开来源链接，请检查浏览器。")
        Text("技术摘要已按来源整理；复核项目为产品设计，尚待教练审定。", style = MaterialTheme.typography.bodySmall)
    }
}

package fitness.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.strings() = (0 until length()).map { getString(it) }

internal data class ExerciseEntry(val data: JSONObject) {
    val id: String get() = data.getString("id")
    val name: String get() = data.getString("name")
    val group: String get() = data.getString("group")
    // Explicit code registration: adding reference text can never enable a detector.
    val mode: ReplayMode get() = when (id) {
        "machine_chest_press" -> ReplayMode.CHEST_PRESS
        "bodyweight_squat" -> ReplayMode.SQUAT
        "barbell_curl" -> ReplayMode.CURL
        else -> ReplayMode.OBSERVE
    }
    val capability: String get() = when (mode) {
        ReplayMode.CHEST_PRESS -> "实验提醒：固定机位下的躯干变化；其余项目待复核"
        ReplayMode.SQUAT -> "实验计数；回放未设置个人幅度目标，其他项目待复核"
        ReplayMode.CURL -> "实验计数与上臂、躯干变化提醒；其余项目待复核"
        ReplayMode.OBSERVE -> "已载入技术要点 · 当前仅观测与人工复核"
    }
}

internal class ExerciseLibrary(val data: JSONObject) {
    val version: String = data.getString("version")
    val groups: List<String> = data.getJSONArray("groups").strings()
    val issues: Map<String, JSONObject> = data.getJSONArray("issues").let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("id") }
    }
    val exercises: List<ExerciseEntry> = data.getJSONArray("exercises").let { list ->
        (0 until list.length()).map { ExerciseEntry(list.getJSONObject(it)) }
    }
    init {
        require(data.getInt("schemaVersion") == 1)
        require(exercises.map { it.id }.distinct().size == exercises.size)
        require(exercises.all { e -> e.group in groups && e.data.getJSONArray("reviewTargets").strings().all { it in issues } })
    }
    fun find(id: String?): ExerciseEntry? = exercises.find { it.id == id }
    fun reference(entry: ExerciseEntry): JSONObject = JSONObject()
        .put("libraryVersion", version).put("reviewedOn", data.getString("reviewedOn"))
        .put("exercise", JSONObject(entry.data.toString()))
        .put("capability", entry.capability).put("automaticMode", entry.mode.name)
        .put("checks", JSONArray().apply { entry.data.getJSONArray("reviewTargets").strings().forEach { put(issues.getValue(it)) } })
    companion object {
        fun load(context: Context) = ExerciseLibrary(JSONObject(context.assets.open("exercise-library.v1.json")
            .bufferedReader().use { it.readText() }))
    }
}

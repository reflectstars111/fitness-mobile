package fitness.mobile

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fitness.mobile.core.Geometry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReplayIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unsupportedExerciseRetainsAnglesButNeverInventsCorrectness() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val rules = ReplayRules(ReplayMode.OBSERVE, true)
            for (t in 0L..2000L step 100) {
                val row = rules.accept(Frame(bitmap, List(33) { null }, mapOf("elbow_flexion_deg" to 80.0), t, 0, null, 1, 1))
                assertEquals(80.0, row.getJSONObject("metrics").getDouble("elbow_flexion_deg"), .001)
                assertEquals("unsupported_exercise", row.getString("evaluationReason"))
                assertEquals("local_issues_only", row.getString("assessmentScope"))
                assertTrue(row.isNull("candidateCount")); assertTrue(row.isNull("scheduledFeedback"))
            }
        } finally { bitmap.recycle() }
    }
    @Test fun replayRefusesUnconfirmedViewAndMissingSignal() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val frame = Frame(bitmap, List(33) { null }, emptyMap(), 0, 0, null, 1)
            val unconfirmed = ReplayRules(ReplayMode.CURL, false).accept(frame)
            assertEquals("not_eligible_front_view", unconfirmed.getString("evaluationReason"))
            val missing = ReplayRules(ReplayMode.CURL, true).accept(frame)
            assertEquals("missing_landmarks", missing.getString("evaluationReason"))
            assertTrue(missing.isNull("candidateCount")); assertTrue(missing.getJSONObject("metrics").isNull("elbow_flexion_deg"))
        } finally { bitmap.recycle() }
    }
    @Test fun chestLocalIssueIgnoresMissingKneeButClearsWhenShoulderIsLost() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val rules = ReplayRules(ReplayMode.CHEST_PRESS, true)
            val points = MutableList<Pair<Float, Float>?>(33) { null }
            listOf(11, 13, 15, 23).forEach { points[it] = .5f to .5f }
            fun frame(t: Long, trunk: Double, flex: Double) = Frame(bitmap, points.toList(),
                mapOf("trunk_lean_deg" to trunk, "elbow_flexion_deg" to flex), t, 0, "missing_landmarks", 1, 1)
            for (t in 0L..1200L step 100) rules.accept(frame(t, -25.0, 90.0))
            var row = rules.accept(frame(1300, -5.0, 65.0))
            for (t in 1400L..2000L step 100) row = rules.accept(frame(t, -5.0, 65.0))
            assertTrue(row.isNull("evaluationReason"))
            assertEquals("chest:trunk_change", row.getJSONObject("scheduledFeedback").getString("ruleId"))
            assertEquals(-25.0, row.getJSONObject("localAnalysis").getDouble("baselineTrunkDeg"), .001)
            assertFalse(row.has("standardVerdict"))
            points[11] = null
            row = rules.accept(frame(2100, -5.0, 65.0))
            assertEquals("missing_local_landmarks", row.getString("evaluationReason"))
            assertTrue(row.getJSONObject("localAnalysis").isNull("issue"))
        } finally { bitmap.recycle() }
    }
    @Test fun sharedPoseEngineKeepsBlankImageOwnershipAndOrderedTime() {
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val engine = PoseEngine(context, Geometry.Side.LEFT)
        try {
            val frame = engine.analyze(bitmap, 0)
            assertEquals("no_person", frame.rejection); assertEquals(0, frame.poseCount)
            assertFalse(bitmap.isRecycled)
            try { engine.analyze(bitmap, 0); fail("duplicate timestamp accepted") } catch (_: IllegalArgumentException) { }
            engine.resetTracking()
            assertTrue(engine.analyze(bitmap, 100).epoch > frame.epoch)
        } finally { engine.close(); bitmap.recycle() }
    }
    /** Opt-in local evidence run; no personal media is packaged in the test APK. */
    @Test fun suppliedLocalVideoUsesProductionDecoderAndModel() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("replayVideo")
        assumeTrue("Pass -e replayVideo <app-readable local path> to analyze a local clip", path != null)
        val source = File(path!!)
        assertTrue("Input clip not readable", source.canRead())
        val args = InstrumentationRegistry.getArguments()
        val mode = ReplayMode.valueOf(args.getString("replayMode") ?: "CHEST_PRESS")
        val side = Geometry.Side.valueOf(args.getString("replaySide") ?: "LEFT")
        val view = args.getString("replayViewConfirmed") == "true"
        val folder = File(context.filesDir, "replay-evidence-${System.currentTimeMillis()}")
        val exerciseId = args.getString("replayExerciseId")
        val result = analyzeVideo(context, Uri.fromFile(source), mode, side, view, folder, exerciseId) { _, _, _ -> }
        if (exerciseId != null) {
            val saved = org.json.JSONObject(File(folder, "report.json").readText())
            val reference = saved.getJSONObject("exerciseReference")
            assertEquals(exerciseId, reference.getJSONObject("exercise").getString("id"))
            assertEquals(mode.name, reference.getString("automaticMode"))
            assertTrue(reference.getJSONArray("checks").length() >= 2)
        }
        assertTrue(result.rows.length() > 1)
        var last = -1L
        for (i in 0 until result.rows.length()) {
            val row = result.rows.getJSONObject(i)
            val time = row.getLong("videoTimeMs")
            assertTrue(time > last); last = time
            if (mode == ReplayMode.CHEST_PRESS) {
                if (!view) assertEquals("camera_not_confirmed_fixed", row.getString("evaluationReason"))
                assertEquals(6, row.getJSONArray("technicalChecks").length())
            }
            if (mode == ReplayMode.OBSERVE) {
                assertEquals("unsupported_exercise", row.getString("evaluationReason"))
                assertTrue(row.isNull("candidateCount")); assertTrue(row.isNull("scheduledFeedback"))
            }
            assertTrue(File(folder, row.getString("image")).length() > 0)
        }
        println("REPLAY_REPORT=${File(folder, "report.json")}")
        println("REPLAY_SUMMARY=${result.report.getJSONObject("summary")}")
    }
}

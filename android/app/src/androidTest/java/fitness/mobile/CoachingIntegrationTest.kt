package fitness.mobile

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fitness.mobile.core.Exercise
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic observations exercise the actual controller/export path, without pretending to be camera accuracy data. */
@RunWith(AndroidJUnit4::class)
class CoachingIntegrationTest {
    private class Scenario(val app: Application, val name: String) {
        var now = 10000L
        val model = TrainingModel(app, name) { now }
        val store = ViewModelStore().apply { put("model", model) }
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        fun frame(offset: Long, flex: Double, trunk: Double = 0.0, arm: Double = 0.0, quality: String? = null) {
            now = 10000 + offset
            model.receive(Frame(bitmap, List(33) { null }, mapOf("knee_flexion_deg" to flex,
                "elbow_flexion_deg" to flex, "trunk_lean_deg" to trunk, "upper_arm_tilt_deg" to arm), now, 0, quality, 20))
        }
        fun start(exercise: Exercise, target: Boolean = false) {
            model.exercise = exercise; model.squatTargetEnabled = target; model.squatTarget = 80f
            model.voice = false; model.consent = true; model.placement = true; model.cameraOpen = true
            model.startSet()
            for (t in 0L..1200L step 100) frame(t, 10.0)
            assertTrue(model.calibrated)
        }
        fun draft() = JSONObject(model.export()).getJSONObject("interrupted")
        fun feedback(rule: String): JSONObject? {
            val log = draft().getJSONArray("feedback")
            return (0 until log.length()).map { log.getJSONObject(it) }.firstOrNull { it.getString("ruleId") == rule }
        }
    }
    private fun scenario(test: (Scenario) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val name = "test-coach-${UUID.randomUUID()}"
        instrumentation.runOnMainSync {
            val s = Scenario(app, name)
            try { test(s) } finally { s.store.clear(); s.bitmap.recycle(); app.getSharedPreferences(name, 0).edit().clear().commit() }
        }
    }
    @Test fun observableArmErrorIsCoachedEvenWhenCountingProtocolRejectsIt() = scenario { s ->
        s.start(Exercise.BICEPS_CURL)
        for (t in 1300L..2900L step 100) s.frame(t, 90.0, arm = 45.0)
        val cue = s.feedback("curl:arm")
        assertNotNull(cue)
        assertEquals(45.0, cue!!.getDouble("measured"), .001)
        assertEquals(0.0, cue.getDouble("reference"), .001)
        assertEquals(11200L, cue.getLong("referenceRecordedAtMs"))
        val reference = s.draft().getJSONArray("standingReferences").getJSONObject(0)
        assertEquals(10000L, reference.getLong("evidenceStartMs"))
        assertEquals(11200L, reference.getLong("evidenceEndMs"))
        assertEquals(0.0, reference.getDouble("upperArmRelativeToTrunkDeg"), .001)
        assertEquals("text_only", cue.getString("deliveryStatus"))
        assertTrue(cue.isNull("spokenAtUptimeMs"))
        assertEquals(11300L, cue.getLong("evidenceStartMs"))
        assertTrue(cue.getLong("evidenceEndMs") >= 12000)
        assertEquals(0, s.model.count)
        assertEquals("curl:arm", s.model.highlightedRule)
        s.frame(3000, 90.0, arm = 45.0, quality = "missing_landmarks")
        assertFalse(s.model.calibrated)
        assertNull(s.model.highlightedRule)
        assertNull(s.model.armDeviation)
        s.model.finish()
        val saved = JSONObject(s.model.export()).getJSONArray("history").getJSONObject(0)
        assertEquals(2, saved.getInt("schemaVersion"))
        assertTrue(saved.getJSONArray("feedback").length() >= 2)
    }
    @Test fun targetComparisonWaitsForReturnAndCancelsAtNextCycle() = scenario { s ->
        s.start(Exercise.SQUAT, target = true)
        // Wait for the preparation utterance's global cooldown before the cycle.
        for (t in 1300L..3000L step 100) s.frame(t, 10.0)
        val shallow = listOf(40.0, 50.0, 55.0, 50.0, 30.0, 20.0, 10.0, 10.0)
        shallow.forEachIndexed { i, flex ->
            s.frame(3100 + i * 100L, flex)
            if (i < shallow.lastIndex) assertNull(s.feedback("squat:target"))
        }
        val cue = s.feedback("squat:target")!!
        assertEquals(55.0, cue.getDouble("measured"), .001)
        assertEquals(80.0, cue.getDouble("reference"), .001)
        assertTrue(cue.getString("text").contains("下次"))
        s.frame(3900, 40.0)
        assertNull(s.model.highlightedRule)
    }
    @Test fun disabledCoachingProducesNoMovementAdvice() = scenario { s ->
        s.start(Exercise.BICEPS_CURL)
        s.model.toggleCoaching()
        for (t in 1300L..2900L step 100) s.frame(t, 90.0, arm = 45.0)
        assertNull(s.feedback("curl:arm"))
        assertNull(s.model.highlightedRule)
        assertFalse(s.model.calibrated)
    }
}

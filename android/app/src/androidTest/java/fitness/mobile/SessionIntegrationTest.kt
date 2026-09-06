package fitness.mobile

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated preference names prevent tests from modifying actual workout history. */
@RunWith(AndroidJUnit4::class)
class SessionIntegrationTest {
    private fun sessionTest(block: (Application, String, ViewModelStore) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val name = "test-session-${UUID.randomUUID()}"
        val store = ViewModelStore()
        try { instrumentation.runOnMainSync { block(app, name, store) } }
        finally {
            instrumentation.runOnMainSync { store.clear() }
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }
    private fun prepared(app: Application, name: String, store: ViewModelStore): TrainingModel {
        val model = TrainingModel(app, name)
        store.put(UUID.randomUUID().toString(), model)
        model.consent = true; model.placement = true; model.cameraOpen = true
        return model
    }
    @Test fun unobservedSetIsUnavailableAndExportPreservesNull() = sessionTest { app, name, store ->
        val model = prepared(app, name, store)
        model.startSet(); model.finish()
        val history = JSONObject(model.export()).getJSONArray("history")
        assertEquals(1, history.length())
        val report = history.getJSONObject(0)
        assertTrue(report.isNull("candidateCount"))
        assertEquals("not_eligible_no_observations", report.getString("countStatus"))
        assertEquals("completed", report.getString("status"))
        assertFalse(model.hasSet)
        assertFalse(model.active)
        assertFalse(app.getSharedPreferences(name, 0).contains("draft"))
        model.deleteHistory()
        assertEquals(0, JSONObject(model.export()).getJSONArray("history").length())
    }
    @Test fun backgroundAndNewControllerDoNotResumeInterruptedSet() = sessionTest { app, name, store ->
        val model = prepared(app, name, store)
        model.startSet()
        assertTrue(model.active)
        model.closeCamera()
        assertFalse(model.active)
        assertFalse(model.cameraOpen)
        assertTrue(model.hasSet)
        val draft = JSONObject(model.export()).getJSONObject("interrupted")
        assertEquals("interrupted", draft.getString("status"))
        assertTrue(draft.isNull("candidateCount"))
        val restored = TrainingModel(app, name)
        store.put("restored", restored)
        assertFalse(restored.active)
        assertFalse(restored.hasSet)
        assertFalse(restored.cameraOpen)
        assertTrue(restored.history.single().contains("不自动续计"))
    }
    @Test fun authorizationAndPlacementAreRequiredToStart() = sessionTest { app, name, store ->
        val model = TrainingModel(app, name)
        store.put("unprepared", model)
        model.cameraOpen = true; model.startSet()
        assertFalse(model.active)
        model.consent = true; model.startSet()
        assertFalse(model.active)
        model.placement = true; model.startSet()
        assertTrue(model.active)
    }
}

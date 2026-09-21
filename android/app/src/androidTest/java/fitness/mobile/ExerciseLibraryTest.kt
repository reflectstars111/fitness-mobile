package fitness.mobile

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExerciseLibraryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packagedLibraryHasResolvableEvidenceAndConservativeCapabilities() {
        val library = ExerciseLibrary.load(context)
        assertEquals(32, library.exercises.size)
        assertEquals(6, library.groups.size)
        for (entry in library.exercises) {
            val ref = library.reference(entry)
            assertEquals(entry.id, ref.getJSONObject("exercise").getString("id"))
            assertTrue(ref.getJSONArray("checks").length() >= 2)
            assertFalse(ref.has("standardVerdict"))
            val sources = entry.data.getJSONArray("sources")
            assertTrue(sources.length() > 0)
            for (i in 0 until sources.length()) {
                val uri = Uri.parse(sources.getJSONObject(i).getString("url"))
                assertEquals("https", uri.scheme)
                assertTrue(uri.host in listOf("www.nasm.org", "www.acefitness.org"))
            }
        }
        assertEquals(ReplayMode.OBSERVE, library.find("single_arm_row")!!.mode)
        assertEquals(ReplayMode.OBSERVE, library.find("back_squat")!!.mode)
        assertEquals(ReplayMode.OBSERVE, library.find("hammer_curl")!!.mode)
        assertEquals(ReplayMode.CHEST_PRESS, library.find("machine_chest_press")!!.mode)
        assertEquals(ReplayMode.CURL, library.find("barbell_curl")!!.mode)
        assertEquals(ReplayMode.SQUAT, library.find("bodyweight_squat")!!.mode)
        // A knowledge entry claiming a mode cannot register executable rules by itself.
        assertEquals(ReplayMode.OBSERVE, ExerciseEntry(JSONObject().put("id", "new_curl_variant")
            .put("automaticMode", "CURL")).mode)
    }

    @Test fun replayRejectsMismatchedKnowledgeBeforeReadingVideo() = runBlocking {
        val unused = File(context.cacheDir, "library-mismatch-must-not-exist")
        try {
            analyzeVideo(context, Uri.EMPTY, ReplayMode.CURL, fitness.mobile.core.Geometry.Side.LEFT,
                true, unused, "single_arm_row") { _, _, _ -> }
            fail("row must not use curl rules")
        } catch (e: IllegalArgumentException) {
            assertEquals("所选动作与分析规则不匹配", e.message)
        }
        try {
            analyzeVideo(context, Uri.EMPTY, ReplayMode.OBSERVE, fitness.mobile.core.Geometry.Side.LEFT,
                false, unused, "missing-entry") { _, _, _ -> }
            fail("unknown id accepted")
        } catch (e: IllegalArgumentException) {
            assertEquals("动作知识条目不存在", e.message)
        }
        assertFalse(unused.exists())
    }
}

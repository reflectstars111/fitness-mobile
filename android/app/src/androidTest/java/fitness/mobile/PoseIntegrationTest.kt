package fitness.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PoseIntegrationTest {
    @Test fun packagedAppDoesNotRequestNetworkPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
        assertFalse(permissions.contains(android.Manifest.permission.ACCESS_NETWORK_STATE))
    }
    @Test fun releasingInferenceImageDoesNotRecyclePreview() {
        val display = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        inferenceImage(display).close()
        assertFalse(display.isRecycled)
        Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)).drawBitmap(display, 0f, 0f, null)
        display.recycle()
    }
    @Test fun bundledModelProcessesBlankFramesWithoutInventingPerson() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumPoses(2).build())
        val display = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        try {
            for (t in listOf(100L, 200L, 300L)) {
                val image = inferenceImage(display)
                try { assertTrue(model.detectForVideo(image, t).landmarks().isEmpty()) }
                finally { image.close() }
                assertFalse(display.isRecycled)
            }
        } finally { model.close(); display.recycle() }
    }
}

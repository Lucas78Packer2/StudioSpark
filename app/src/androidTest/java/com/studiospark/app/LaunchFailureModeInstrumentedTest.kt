package com.studiospark.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device versions of the four launch failure modes (icon tap bounces to home, bubble works).
 * Run: ./gradlew connectedDebugAndroidTest  (needs a phone or emulator; NOT run on the build box).
 */
@RunWith(AndroidJUnit4::class)
class LaunchFailureModeInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(ctx, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Before fun freshInstall() { File(ctx.filesDir, "session").deleteRecursively(); ctx.getSharedPreferences("studiospark", 0).edit().clear().commit() }

    private fun launch(): Pair<ActivityScenario<MainActivity>, StudioVM> {
        val sc = ActivityScenario.launch<MainActivity>(launcherIntent()); var vm: StudioVM? = null
        sc.onActivity { vm = ViewModelProvider(it)[StudioVM::class.java] }
        compose.waitUntil(30_000) { vm!!.ready }
        return sc to vm!!
    }

    @Test fun failureMode1_launcherStartReachesResumedWithoutCrash() {
        val (sc, _) = launch(); Thread.sleep(3_000)
        assertEquals(Lifecycle.State.RESUMED, sc.state)
        sc.onActivity { assertFalse(it.isFinishing) }; sc.close()
    }

    @Test fun failureMode2_freshStartComposesBasicStudioWithNoNulls() {
        val (sc, vm) = launch()
        assertTrue(vm.project.tracks.isEmpty()); assertNull(vm.selectedTrack()); assertNotNull(vm.mixer.snap)
        compose.onNodeWithText("Add instrument or gear").assertExists(); sc.close()
    }

    @Test fun failureMode3_launcherIconAndThemeResolveOnThisDevice() {
        val ai = ctx.applicationInfo; assertNotEquals(0, ai.icon); assertNotNull(ctx.getDrawable(ai.icon))
        assertNotNull(ctx.packageManager.getActivityInfo(ComponentName(ctx, MainActivity::class.java), 0).themeResource)
        val (sc, _) = launch(); sc.onActivity { assertTrue(it.window.decorView.width > 0) }; sc.close()
    }

    @Test fun failureMode4_fullscreenNotMultiWindowAndNoPermissionNeeded() {
        val (sc, vm) = launch()
        sc.onActivity { a -> assertFalse(a.isInMultiWindowMode); assertFalse(a.isInPictureInPictureMode); assertFalse(a.isFinishing)
            val dm = a.resources.displayMetrics; assertTrue(a.window.decorView.width >= dm.widthPixels * 0.95) }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) assertFalse(vm.engine.inputActive)
        assertEquals(Lifecycle.State.RESUMED, sc.state); sc.close()
    }
}

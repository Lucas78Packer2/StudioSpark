package com.studiospark.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.studiospark.app.ui.MainScreen
import com.studiospark.app.ui.StudioTheme
import com.studiospark.app.ui.StudioVM

class MainActivity : ComponentActivity() {
    private val vm: StudioVM by viewModels()
    private var pendingMic: ((Boolean) -> Unit)? = null
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> pendingMic?.invoke(granted); pendingMic = null; vm.refreshInputs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        vm.requestMicPermission = { cb -> if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) cb(true) else { pendingMic = cb; micLauncher.launch(Manifest.permission.RECORD_AUDIO) } }
        setContent { StudioTheme(vm.gold) { MainScreen(vm) } }
        val first = intent; vm.whenReady { openFromIntent(first) }
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); vm.whenReady { openFromIntent(intent) } }

    /** Debug-only deep links used for on-device smoke checks: adb shell am start -n com.studiospark.app/.MainActivity --es open JAM */
    private fun openFromIntent(i: android.content.Intent?) {
        if (!BuildConfig.DEBUG) return
        when (val o = i?.getStringExtra("open") ?: return) {
            "STUDIO", "IMPORT", "SONGWRITER", "EAR", "STEMS", "RECORD", "JAM", "BASS" -> { vm.closeUpload(); vm.workbench = null; vm.screen = com.studiospark.app.ui.Screen.valueOf(o) }
            "MIX" -> { vm.screen = com.studiospark.app.ui.Screen.STUDIO; vm.mixWindow = true }
            "RIG" -> { vm.screen = com.studiospark.app.ui.Screen.STUDIO; vm.openUpload("rig") }
            "UPLOAD" -> vm.openUpload("import", null)
            "GALLERY" -> vm.galleryOpen = true
            "SETTINGS" -> vm.settingsOpen = true
            "DRUMS" -> { vm.selectedTrackId = vm.project.tracks.firstOrNull { it.family == com.studiospark.app.dsp.Family.DRUMS }?.id; vm.closeUpload(); vm.workbench = null; vm.screen = com.studiospark.app.ui.Screen.DRUMS }
            "GUITAR" -> { vm.selectedTrackId = vm.project.tracks.firstOrNull { it.family == com.studiospark.app.dsp.Family.GUITAR }?.id; vm.closeUpload(); vm.workbench = null; vm.screen = com.studiospark.app.ui.Screen.GUITAR }
            "PLAY" -> vm.play()
        }
    }
}

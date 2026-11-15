package com.studiospark.app.ui

import androidx.compose.runtime.*
import com.studiospark.app.eartraining.EarTrainingScreen
import com.studiospark.app.eartraining.PreviewEarPlayer
import com.studiospark.app.eartraining.StemLibraryClips

/**
 * Ear-training tab (package com.studiospark.app.eartraining, written by StudioSpark Builder).
 * Lazy: nothing is created until this tab first composes; the stem library is only read when a quiz asks for clips.
 */
@Composable fun EarTrainingSlot(vm: StudioVM) {
    CompositionLocalProvider(LocalPalette provides ImportPalette) {
        EarTrainingScreen(player = remember { PreviewEarPlayer { vm.engine.preview(it) } }, stemClips = remember { StemLibraryClips({ vm.importer().libraries }) })
    }
}

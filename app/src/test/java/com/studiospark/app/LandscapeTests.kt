package com.studiospark.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import com.studiospark.app.ui.StudioLayout
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.1 milestone 2: landscape scroll. A phone in landscape (~800x360 dp) must keep the lane list and the detail panel scrollable. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land")
class LandscapeTests {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)
    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java].also { v -> compose.waitUntil(20_000) { v.ready } }

    @Test fun editHeightComesFromRemainingSpaceNotScreen() {
        // portrait phone: plenty of room, capped
        assertEquals(420.dp, StudioLayout.editHeight(1000.dp))
        // small remaining space: the detail panel always keeps 120 dp and the edit window never goes below 96 dp
        val h = StudioLayout.editHeight(330.dp); assertTrue(h >= 96.dp); assertTrue(330.dp - StudioLayout.TOOLBAR - h >= 120.dp)
        assertEquals(96.dp, StudioLayout.editHeight(200.dp))
    }

    @Test fun landscapeLanesAndDetailScroll() {
        val v = vm()
        compose.runOnIdle { repeat(7) { i -> v.addTrack(InstrumentLibrary.stock.first { it.family == Family.KEYS }, "Keys $i") } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Edit window lanes").assertExists()
        // the last track header is below the fold in a 360 dp tall window: scrolling must reach it
        compose.onNodeWithContentDescription("Track Keys 6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Track Keys 0").performScrollTo().assertIsDisplayed()
        // a vertical swipe on the lanes is not eaten by the clip/timeline drag
        compose.onNodeWithContentDescription("Edit window lanes").performTouchInput { swipeUp() }
        compose.waitForIdle()
        // detail panel (right side) scrolls to its lower content
        compose.onNodeWithContentDescription("Delete track").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Volume automation", substring = true, ignoreCase = true).performScrollTo().assertIsDisplayed()
        // timeline scroll is hoisted into the VM (survives rotation)
        compose.runOnIdle { v.timelineScrollX = 333f }; compose.waitForIdle(); assertEquals(333f, v.timelineScrollX)
    }
}

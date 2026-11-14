package com.studiospark.app

import com.studiospark.app.core.*
import com.studiospark.app.core.TrackPlay.Action
import org.junit.Assert.*
import org.junit.Test

/** 2.1 milestone 4: per-track play/pause follows the global transport. */
class TrackPlayTests {
    @Test fun tapRules() {
        assertEquals(Action.UNPAUSE_AND_START, TrackPlay.tap(paused = true, rolling = false))
        assertEquals(Action.UNPAUSE, TrackPlay.tap(paused = true, rolling = true))
        assertEquals(Action.START, TrackPlay.tap(paused = false, rolling = false))
        assertEquals(Action.PAUSE, TrackPlay.tap(paused = false, rolling = true))
    }
    @Test fun globalPlayStartsOnlyNonPausedTracks() {
        assertTrue(TrackPlay.sounds(paused = false, rolling = true)); assertFalse(TrackPlay.sounds(paused = true, rolling = true)); assertFalse(TrackPlay.sounds(paused = false, rolling = false))
    }
    @Test fun pausedRoundTripsAndDefaultsOff() {
        val p = Project(); p.tracks += Track(name = "G", family = com.studiospark.app.dsp.Family.GUITAR, instrumentId = "gtr_0", color = 0, paused = true)
        assertTrue(ProjectJson.read(ProjectJson.write(p)).tracks[0].paused)
        assertFalse(ProjectJson.read(ProjectJson.write(p).replace("\"paused\":true,", "")).tracks[0].paused)
    }
}

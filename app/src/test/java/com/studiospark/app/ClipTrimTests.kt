package com.studiospark.app

import com.studiospark.app.core.*
import org.junit.Assert.*
import org.junit.Test

/** 2.1 milestone 3: non-destructive clip trimming (left/right handles, silence trim, fades). Pure JVM. */
class ClipTrimTests {
    private val sr = ENGINE_SR.toLong()

    @Test fun leftTrimMovesStartOffsetAndLengthTogether() {
        val c = Clip(audioId = "a", start = sr, offset = 0, length = sr * 2)
        ClipEdit.trimLeft(c, sr + sr / 2)
        assertEquals(sr + sr / 2, c.start); assertEquals(sr / 2, c.offset); assertEquals(sr + sr / 2, c.length)
        assertEquals("the right edge does not move", sr * 3, c.end)
        // dragging back out restores, but never past the source start
        ClipEdit.trimLeft(c, 0); assertEquals(sr, c.start); assertEquals(0, c.offset); assertEquals(sr * 2, c.length)
    }

    @Test fun leftTrimRespectsRateAndMinimumLength() {
        val c = Clip(audioId = "a", start = 0, offset = 0, length = sr * 2, rate = 2f)   // plays in 1 s
        ClipEdit.trimLeft(c, sr / 2); assertEquals(sr / 2, c.start); assertEquals(sr, c.offset); assertEquals(sr, c.length); assertEquals(sr, c.end)
        ClipEdit.trimLeft(c, sr * 5); assertTrue(c.length >= ClipEdit.MIN_LEN); assertTrue(c.end <= sr)
    }

    @Test fun rightTrimLimitedBySourceAudio() {
        val c = Clip(audioId = "a", start = 100, offset = sr / 2, length = sr / 2)
        ClipEdit.trimRight(c, 100 + sr * 10, srcLen = sr * 2)
        assertEquals("cannot extend past the pooled audio", sr * 2 - sr / 2, c.length)
        ClipEdit.trimRight(c, 0, srcLen = sr * 2); assertEquals(ClipEdit.MIN_LEN, c.length)
    }

    @Test fun silenceTrimFindsTheSoundAndKeepsAudioUntouched() {
        val data = ShortArray(sr.toInt() * 3); for (i in sr.toInt() until sr.toInt() * 2) data[i] = (if (i % 2 == 0) 8000 else -8000).toShort()
        val copy = data.copyOf()
        val c = Clip(audioId = "a", start = sr * 4, offset = 0, length = sr * 3)
        assertTrue(ClipEdit.silenceTrim(c, data))
        val pad = sr / 100
        assertEquals(sr - pad, c.offset); assertEquals(sr * 4 + sr - pad, c.start); assertEquals(sr + 2 * pad, c.length)
        assertArrayEquals("non-destructive", copy, data)
        assertFalse("all-silence clips are left alone", ClipEdit.silenceTrim(Clip(audioId = "s", start = 0, length = 1000), ShortArray(1000)))
    }

    @Test fun fadesClampAndRoundTrip() {
        val c = Clip(audioId = "a", start = 0, length = 1000, fadeIn = 800, fadeOut = 800); ClipEdit.clampFades(c)
        assertEquals(800, c.fadeIn); assertEquals(200, c.fadeOut)
        val p = Project(); val t = Track(name = "A", family = com.studiospark.app.dsp.Family.AUDIO, instrumentId = "vox_mic", color = 0); t.clips += c; p.tracks += t
        val q = ProjectJson.read(ProjectJson.write(p)).tracks[0].clips[0]; assertEquals(800, q.fadeIn); assertEquals(200, q.fadeOut)
        assertEquals(800, c.copy("x").fadeIn)
    }
}

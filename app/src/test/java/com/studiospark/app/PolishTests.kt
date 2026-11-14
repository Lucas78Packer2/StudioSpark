package com.studiospark.app

import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import com.studiospark.app.ui.dbToFader
import com.studiospark.app.ui.faderDb
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PolishTests {
    private val sr = 48000f
    private fun tone() = FloatArray(4800) { (0.4 * sin(2 * PI * 196.0 * it / sr)).toFloat() }
    private fun diff(a: FloatArray, b: FloatArray) = a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size

    @Test fun customKitWithAddedPiecesAndStickSurvivesSave() {
        var k = DrumKits.studio.copy(id = "ck1", name = "Mine", custom = true, stick = Stick.BRUSHES)
        k = DrumKits.addPiece(k, "cowbell-0"); k = DrumKits.addPiece(k, "low_tom-1", choke = 3)
        val p = Project(); p.customKits.add(k)
        val back = ProjectJson.read(ProjectJson.write(p)).customKits.single()
        assertEquals(k.pieces.size, back.pieces.size); assertEquals(Stick.BRUSHES, back.stick)
        assertEquals(k.pieces.map { it.midi }, back.pieces.map { it.midi }); assertEquals(3, back.pieces.last().chokeGroup)
        assertEquals("added pieces get unique MIDI notes", back.pieces.count { it.sounding }, back.pieces.filter { it.sounding }.map { it.midi }.toSet().size)
    }

    @Test fun sticksDifferInFeel() { assertTrue(Stick.values().map { it.velScale to it.brightness }.toSet().size == Stick.values().size) }

    @Test fun sharedRigEditsReachTheRunningProcessor() {
        // The Tone step edits the track's own Rig instance; the running processor must hear power and gain changes without a rebuild.
        val r = Rig(); r.add("citrus_valve"); r.add("cab_412"); val proc = RigProcessor(sr, r)
        val a = tone(); proc.process(a, a.size)
        r.outputGainDb = -12f; val b = tone(); proc.process(b, b.size); assertTrue(diff(a, b) > 0.005)
        r.power = false; val c = tone(); proc.process(c, c.size); assertEquals(0.0, diff(tone(), c), 1e-6)
        r.power = true; r.add("hall_verb"); assertFalse("structure change requires a rebuilt processor", proc.matches(r))
    }

    @Test fun faderLawIsMonotonicAndUnityAtThreeQuarters() {
        assertEquals(0f, faderDb(0.75f), 0.01f); assertEquals(0.75f, dbToFader(0f), 0.001f)
        var prev = -200f; for (i in 1..100) { val db = faderDb(i / 100f); assertTrue(db >= prev); prev = db }
        for (db in listOf(-40f, -12f, -3f, 0f, 3f, 6f)) assertEquals(db, faderDb(dbToFader(db)), 0.3f)
    }

    @Test fun trackCountTemplatesAreOffered() { val p = Project(trackCount = 16); assertEquals(16, ProjectJson.read(ProjectJson.write(p)).trackCount) }

    @Test fun importPolicyBlocksStreamingAndDrm() {
        listOf("https://open.spotify.com/track/x" to null, "magnet:?xt=urn:btih:abc" to null, "content://x/song.m4p" to "song.m4p").forEach { (u, n) ->
            assertTrue(u, ImportPolicy.classify(u, n, null) is ImportPolicy.Decision.Reject) }
        assertTrue(ImportPolicy.classify("content://media/1", "riff.wav", "audio/wav") is ImportPolicy.Decision.Accept)
    }
}

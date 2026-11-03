package com.aimusicstudio.app

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.*
import kotlin.random.Random

/** Pedalboard, amp sims, guitar voices, drum kits, sends. Lightweight offline DSP. */
object Fx {
    fun pedal(x: FloatArray, p: PedalSlot, bpm: Int) {
        if (!p.on) return
        pedalCore(x, p, bpm)
        // Second (TONE) knob: tilt filter after the effect — dark below 0.5, bright above.
        val tn = p.tone.coerceIn(0f, 1f); if (abs(tn - 0.5f) > 0.02f) { var lp = 0f; val c = 0.05f + tn * 0.9f
            for (i in x.indices) { lp += c * (x[i] - lp); x[i] = if (tn < 0.5f) lp else x[i] + (x[i] - lp) * (tn - 0.5f) * 1.2f } }
    }
    private fun pedalCore(x: FloatArray, p: PedalSlot, bpm: Int) {
        val a = p.amount.coerceIn(0f, 1f)
        when (p.type) {
            PedalType.OVERDRIVE -> { val g = 1 + a * 8; for (i in x.indices) x[i] = tanh(x[i] * g) / tanh(g).coerceAtLeast(0.3f) * 0.7f }
            PedalType.DISTORTION -> { val g = 4 + a * 30; for (i in x.indices) x[i] = (x[i] * g).coerceIn(-0.6f, 0.6f) }
            PedalType.CHORUS -> { val base = (0.012 * SR).toInt(); val dep = 0.004 * SR; val src = x.copyOf()
                for (i in x.indices) { val d = base + (dep * sin(2 * PI * 0.8 * i / SR)).toInt(); val j = i - d; if (j >= 0) x[i] = src[i] * (1 - a * 0.5f) + src[j] * a * 0.6f } }
            PedalType.DELAY -> delay(x, (60.0 / bpm * 0.75 * SR).toInt(), 0.35f + a * 0.4f, a)
            PedalType.REVERB -> reverb(x, a)
            PedalType.WAH -> { var lp = 0f; var bp = 0f
                for (i in x.indices) { val f = (2 * sin(PI * (400 + 1800 * (0.5 + 0.5 * sin(2 * PI * (0.5 + a * 3) * i / SR))) / SR)).toFloat()
                    val hp = x[i] - lp - 0.3f * bp; bp += f * hp; lp += f * bp; x[i] = x[i] * (1 - a) + bp * a * 2.5f } }
            PedalType.COMPRESSOR -> MixRenderer.applyCompressor(x, TrackCompressorSettings(true, -10f - a * 25f, 2f + a * 8f, 8f, 120f, a * 10f))
            PedalType.TREMOLO -> for (i in x.indices) x[i] *= (1 - a * (0.5f + 0.5f * sin(2 * PI * 5 * i / SR).toFloat()))
            PedalType.FUZZ -> { val g = 10 + a * 60; for (i in x.indices) { val y = x[i] * g; x[i] = (sign(y) * (1 - exp(-abs(y))) * 0.55f + 0.05f * abs(y).coerceAtMost(1f)) } }
            PedalType.METAL_DIST -> { var hp = 0f; var prev = 0f; val g = 30 + a * 80
                for (i in x.indices) { hp = 0.97f * (hp + x[i] - prev); prev = x[i]; val y = tanh(hp * g); x[i] = tanh(y * 3f) * 0.55f } }
            PedalType.BOOST -> { val g = 1 + a * 3; for (i in x.indices) x[i] = (x[i] * g).coerceIn(-1f, 1f) }
            PedalType.PHASER -> { val ap = FloatArray(4); val st = FloatArray(4)
                for (i in x.indices) { val c = (0.3 + 0.6 * (0.5 + 0.5 * sin(2 * PI * (0.3 + a) * i / SR))).toFloat(); var y = x[i] + st[3] * 0.5f * a
                    for (k in 0 until 4) { val o = -c * y + ap[k]; ap[k] = y + c * o; y = o }; st[3] = y; x[i] = x[i] * 0.6f + y * 0.6f * (0.4f + a) } }
            PedalType.FLANGER -> { val src = x.copyOf(); for (i in x.indices) { val d = (0.001 * SR + 0.004 * SR * (0.5 + 0.5 * sin(2 * PI * 0.25 * i / SR))).toInt(); val j = i - d
                if (j >= 0) { x[i] = src[i] * 0.7f + x[j] * (0.3f + a * 0.5f) } } }
            PedalType.VIBRATO -> { val src = x.copyOf(); for (i in x.indices) { val d = 0.003 * SR + 0.003 * SR * a * sin(2 * PI * 5.5 * i / SR); val j = i - d; val k = j.toInt()
                if (k >= 0 && k + 1 < src.size) x[i] = src[k] + (src[k + 1] - src[k]) * (j - k).toFloat() } }
            PedalType.OCTAVER -> { var lp = 0f; var flip = 1f; var last = 0f
                for (i in x.indices) { lp += 0.02f * (x[i] - lp); if (lp > 0 && last <= 0) flip = -flip; last = lp; x[i] = x[i] * (1 - a * 0.5f) + abs(lp) * flip * a * 2.5f } }
            PedalType.BITCRUSH -> { val lv = (64 - a * 58).coerceAtLeast(3f); val hold = 1 + (a * 12).toInt(); var h = 0f
                for (i in x.indices) { if (i % hold == 0) h = round(x[i] * lv) / lv; x[i] = h } }
            PedalType.AUTOWAH -> { var env = 0f; var lp = 0f; var bp = 0f
                for (i in x.indices) { env = max(abs(x[i]), env * 0.9995f); val f = (2 * sin(PI * (300 + 3000 * min(1f, env * 4) * (0.3f + a)) / SR)).toFloat().coerceAtMost(0.9f)
                    val hp = x[i] - lp - 0.25f * bp; bp += f * hp; lp += f * bp; x[i] = x[i] * (1 - a) + bp * a * 2.5f } }
            PedalType.RING_MOD -> { val fr = 80 + a * 900; for (i in x.indices) x[i] = x[i] * (1 - a) + x[i] * sin(2 * PI * fr * i / SR).toFloat() * a }
            PedalType.NOISE_GATE -> { var env = 0f; val th = 0.005f + a * 0.08f; var g = 1f
                for (i in x.indices) { env = max(abs(x[i]), env * 0.999f); g += ((if (env > th) 1f else 0f) - g) * 0.01f; x[i] *= g } }
            PedalType.LOFI -> { var lp = 0f; for (i in x.indices) { val w = 1 + 0.003f * a * sin(2 * PI * 0.7 * i / SR).toFloat(); lp += (0.25f - a * 0.18f) * (x[i] * w - lp)
                x[i] = tanh(lp * (1.5f + a)) * 0.8f } }
        }
    }

    fun amp(x: FloatArray, m: AmpModel) = amp(x, m, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.7f)
    fun amp(x: FloatArray, t: Track) = amp(x, t.amp, t.ampGain, t.ampBass, t.ampMid, t.ampTreble, t.ampPresence, t.ampMaster)
    /** Synthesized amp model; the six front-panel knobs (0..1, 0.5 = model default) scale gain/EQ/presence/master live. */
    fun amp(x: FloatArray, m: AmpModel, gain: Float, bass: Float, mid: Float, treble: Float, presence: Float, master: Float) {
        if (m == AmpModel.NONE) return
        MixRenderer.applyEQ(x, TrackEQSettings(m.bass + (bass - 0.5f) * 18f, (mid - 0.5f) * 16f, m.treble + (treble - 0.5f) * 18f))
        val g = (1 + m.gain) * (0.15f + gain * 1.7f) + gain * 2f; val norm = 1f / tanh(g * 0.5f).coerceAtLeast(0.2f)
        for (i in x.indices) x[i] = tanh(x[i] * g) * norm * 0.5f
        var lp = 0f; val c = 0.2f + presence * 0.45f // cabinet roll-off, opened up by Presence
        for (i in x.indices) { lp += c * (x[i] - lp); x[i] = lp }
        val out = 0.2f + master * 1.4f; for (i in x.indices) x[i] = (x[i] * out).coerceIn(-1f, 1f)
    }

    fun delay(x: FloatArray, d: Int, fb: Float, mix: Float) {
        if (d <= 0) return
        val y = x.copyOf(); for (i in d until y.size) y[i] += y[i - d] * fb
        for (i in x.indices) x[i] = x[i] * (1 - mix * 0.5f) + (y[i] - x[i]) * mix + x[i] * mix * 0.5f
    }

    /** Freeverb-lite: 4 parallel combs + 2 allpass. */
    fun reverb(x: FloatArray, mix: Float) {
        val combs = intArrayOf(1557, 1617, 1491, 1422); val out = FloatArray(x.size)
        for (d in combs) { val buf = FloatArray(d); var k = 0; var lp = 0f
            for (i in x.indices) { val y = buf[k]; lp = y * 0.8f + lp * 0.2f; buf[k] = x[i] + lp * 0.84f; k = (k + 1) % d; out[i] += y * 0.25f } }
        for (d in intArrayOf(556, 441)) { val buf = FloatArray(d); var k = 0
            for (i in out.indices) { val b = buf[k]; val y = -out[i] + b; buf[k] = out[i] + b * 0.5f; k = (k + 1) % d; out[i] = y } }
        for (i in x.indices) x[i] = x[i] * (1 - mix * 0.5f) + out[i] * mix
    }

    /** Karplus-Strong plucked string coloured by guitar model. */
    fun guitarNote(g: GuitarModel, pitch: Int, dur: Double, vel: Float = 0.8f): FloatArray {
        val f = InstrumentSoundGenerator.freq(pitch); val p = max(2, (SR / f).toInt()); val n = ((dur + 0.6) * SR).toInt()
        val rnd = Random(pitch); val buf = FloatArray(p) { rnd.nextFloat() * 2 - 1 }
        var lpb = 0f; for (i in buf.indices) { lpb += (1 - g.brightness) * 0.6f * (buf[i] - lpb); buf[i] = buf[i] * g.brightness + lpb * (1 - g.brightness) }
        val out = FloatArray(n); var k = 0; val damp = 0.996f - (1 - g.brightness) * 0.004f
        for (i in 0 until n) { val nx = (k + 1) % p; val v = (buf[k] + buf[nx]) * 0.5f * damp; out[i] = buf[k]; buf[k] = v; k = nx
            if (i.toDouble() / SR > dur) out[i] *= exp(-(i.toDouble() / SR - dur) * 12).toFloat() }
        var body = 0f; for (i in out.indices) { body += 0.05f * (out[i] - body); out[i] = (out[i] + body * g.body * 3) * vel * 0.5f }
        return out
    }

    private val kitCache = HashMap<Pair<DrumKit, DrumPad>, FloatArray>()
    private const val KIT_VERSION = 2
    private class Kit(val tune: Double, val decay: Double, val noise: Double, val drive: Double, val room: Float, val crush: Int = 0, val dark: Float = 1f)
    private fun kitSpec(k: DrumKit) = when (k) {
        DrumKit.ROCK -> Kit(1.0, 1.0, 1.0, 1.3, 0.22f)
        DrumKit.ACOUSTIC -> Kit(1.08, 1.25, 0.8, 1.0, 0.32f, dark = 0.8f)
        DrumKit.METAL -> Kit(1.15, 0.7, 1.2, 2.2, 0.15f)
        DrumKit.JAZZ_BRUSH -> Kit(1.25, 1.4, 0.6, 1.0, 0.35f, dark = 0.6f)
        DrumKit.TR808 -> Kit(0.85, 1.0, 0.5, 1.1, 0f)
        DrumKit.VINTAGE909 -> Kit(1.0, 0.8, 1.0, 1.5, 0.05f)
        DrumKit.TRAP -> Kit(0.8, 1.0, 0.7, 1.8, 0f)
        DrumKit.HOUSE -> Kit(1.0, 0.7, 0.9, 1.3, 0.08f)
        DrumKit.DNB -> Kit(1.2, 0.6, 1.1, 1.6, 0.1f)
        DrumKit.ELECTRONIC -> Kit(1.1, 0.8, 0.9, 1.6, 0.06f)
        DrumKit.LOFI -> Kit(0.9, 0.9, 0.7, 1.2, 0.12f, crush = 10, dark = 0.5f)
        DrumKit.PROG_ARENA -> Kit(0.95, 1.5, 1.0, 1.2, 0.38f)       // deep shells, long tom ring, big hall
        DrumKit.MASK_METAL -> Kit(1.2, 0.55, 1.3, 2.6, 0.12f)       // clicky triggered kick, cracking snare
        DrumKit.DJENT -> Kit(1.1, 0.5, 1.1, 2.0, 0.08f, dark = 0.9f) // tight, dry, gated
    }
    /** Per-kit synthesized drum voices (no licensed samples): layered acoustic kick thud + beater click, snare crack + wires,
     *  brush swishes, long 808 sub, 909 punch, tight metal kicks. Each kit has its own tuning/decay/drive/room. */
    fun kitDrum(kit: DrumKit, pad: DrumPad): FloatArray = synchronized(kitCache) { kitCache.getOrPut(kit to pad) {
        val k = kitSpec(kit); val rnd = Random(kit.ordinal * 31 + pad.ordinal + KIT_VERSION * 1000)
        fun n() = rnd.nextDouble(-1.0, 1.0)
        fun gen(dur: Double, f: (Double) -> Double) = FloatArray((dur * SR).toInt()) { f(it.toDouble() / SR).toFloat() }
        val electronic = kit in setOf(DrumKit.TR808, DrumKit.VINTAGE909, DrumKit.TRAP, DrumKit.HOUSE, DrumKit.ELECTRONIC)
        var y: FloatArray = when (pad) {
            DrumPad.KICK -> when (kit) {
                DrumKit.TR808 -> gen(1.4) { t -> sin(2 * PI * (48 + 55 * exp(-30 * t)) * t) * exp(-2.2 * t) }
                DrumKit.TRAP -> gen(1.8) { t -> tanh(sin(2 * PI * (42 + 100 * exp(-28 * t)) * t) * 2.2) * exp(-1.5 * t) }
                DrumKit.METAL -> gen(0.22) { t -> sin(2 * PI * (70 + 160 * exp(-60 * t)) * t) * exp(-16 * t) + n() * 0.7 * exp(-500 * t) + sin(2 * PI * 3200 * t) * 0.3 * exp(-300 * t) }
                DrumKit.VINTAGE909 -> gen(0.5) { t -> tanh(sin(2 * PI * (55 + 170 * exp(-45 * t)) * t) * 1.6 + n() * 0.3 * exp(-400 * t)) * exp(-5 * t) }
                DrumKit.HOUSE -> gen(0.45) { t -> tanh(sin(2 * PI * (52 + 110 * exp(-38 * t)) * t) * 1.4) * exp(-6 * t) }
                else -> gen(0.6 * k.decay) { t -> val f0 = 58 * k.tune  // acoustic: shell thud + head overtone + beater click
                    sin(2 * PI * (f0 + 70 * exp(-40 * t)) * t) * exp(-7 / k.decay * t) + sin(2 * PI * f0 * 1.6 * t) * 0.25 * exp(-14 * t) + n() * 0.45 * k.noise * exp(-250 * t) }
            }
            DrumPad.SNARE -> when (kit) {
                DrumKit.JAZZ_BRUSH -> gen(0.45) { t -> n() * 0.45 * min(1.0, t / 0.04) * exp(-7 * t) + sin(2 * PI * 210 * t) * 0.12 * exp(-25 * t) }
                DrumKit.TR808 -> gen(0.25) { t -> sin(2 * PI * 238 * t) * 0.5 * exp(-20 * t) + sin(2 * PI * 476 * t) * 0.2 * exp(-25 * t) + n() * 0.45 * exp(-16 * t) }
                DrumKit.VINTAGE909 -> gen(0.3) { t -> sin(2 * PI * (190 + 60 * exp(-60 * t)) * t) * 0.45 * exp(-25 * t) + n() * 0.6 * exp(-12 * t) }
                else -> { var hp = 0.0; var prev = 0.0
                    gen(0.35 * k.decay) { t -> val f = 185 * k.tune; val wn = n(); hp = 0.85 * (hp + wn - prev); prev = wn  // crack (shell) + wires (hp noise)
                        sin(2 * PI * (f + 40 * exp(-80 * t)) * t) * 0.55 * exp(-28 * t) + sin(2 * PI * f * 1.5 * t) * 0.2 * exp(-35 * t) + hp * 0.55 * k.noise * exp(-13 / k.decay * t) } }
            }
            DrumPad.CLAP -> gen(0.35) { t -> n() * (exp(-70 * (t % 0.011)) * (if (t < 0.034) 1.0 else 0.0) + exp(-13 * t) * 0.55) * (if (kit == DrumKit.JAZZ_BRUSH) 0.4 else 1.0) }
            DrumPad.CLOSED_HAT -> { var hp = 0.0; var pv = 0.0; gen(if (kit == DrumKit.TRAP) 0.04 else 0.08 * k.decay) { t ->
                val m = if (electronic) n() else (sign(sin(2 * PI * 540 * k.tune * t)) + sign(sin(2 * PI * 800 * t)) + sign(sin(2 * PI * 1170 * t))) * 0.3 + n() * 0.4
                hp = 0.6 * (hp + m - pv); pv = m; hp * 0.5 * exp(-(if (kit == DrumKit.JAZZ_BRUSH) 30 else 60) * t) } }
            DrumPad.OPEN_HAT -> { var hp = 0.0; var pv = 0.0; gen(0.5 * k.decay) { t ->
                val m = if (electronic) n() else (sign(sin(2 * PI * 540 * t)) + sign(sin(2 * PI * 800 * t)) + sign(sin(2 * PI * 1170 * t))) * 0.3 + n() * 0.4
                hp = 0.6 * (hp + m - pv); pv = m; hp * 0.45 * exp(-7 / k.decay * t) } }
            DrumPad.CYMBAL -> { var hp = 0.0; var pv = 0.0; gen(if (kit == DrumKit.JAZZ_BRUSH) 2.2 else 1.6) { t ->
                val m = n() * 0.6 + (sign(sin(2 * PI * 4200 * k.tune * t)) + sign(sin(2 * PI * 6100 * t))) * 0.2
                hp = 0.7 * (hp + m - pv); pv = m; hp * 0.5 * min(1.0, t / 0.003) * exp(-(if (kit == DrumKit.METAL) 1.8 else 2.4) * t) } }
            DrumPad.COWBELL -> gen(0.4) { t -> (sign(sin(2 * PI * 540 * t)) + sign(sin(2 * PI * 800 * t))) * 0.25 * exp(-8 * t) }
            else -> resample(InstrumentSoundGenerator.drum(pad), (k.tune).toFloat())
        }
        if (k.dark < 1f) { var lp = 0f; for (i in y.indices) { lp += k.dark * 0.9f * (y[i] - lp); y[i] = lp } }
        if (k.drive > 1.05) { val d = k.drive.toFloat(); for (i in y.indices) y[i] = tanh(y[i] * d) / tanh(d) }
        if (k.crush > 0) for (i in y.indices) y[i] = round(y[i] * k.crush) / k.crush
        var pk = 0f; for (v in y) pk = max(pk, abs(v)); if (pk > 0f) { val g = 0.9f / pk; for (i in y.indices) y[i] *= g }
        if (k.room > 0f) reverbCopy(y, k.room) else y
    } }
    private fun reverbCopy(x: FloatArray, m: Float): FloatArray { val y = x.copyOf(x.size + SR / 2); reverb(y, m); return y }
    fun resample(x: FloatArray, rate: Float): FloatArray = FloatArray((x.size / rate).toInt()) { i -> val s = i * rate; val j = s.toInt(); if (j + 1 < x.size) x[j] + (x[j + 1] - x[j]) * (s - j) else 0f }

    fun autoValue(pts: List<AutoPoint>, t: Double, default: Float): Float {
        if (pts.isEmpty()) return default
        val s = pts.sortedBy { it.time }
        if (t <= s.first().time) return s.first().value; if (t >= s.last().time) return s.last().value
        val i = s.indexOfLast { it.time <= t }; val a = s[i]; val b = s[i + 1]
        return a.value + (b.value - a.value) * ((t - a.time) / (b.time - a.time)).toFloat()
    }
}

/** Port of OnDeviceStemSeparator.swift: 4-way Linkwitz-Riley crossover (bass <150, vocals 150-2.5k, drums 2.5k-9k, other >9k). */
object StemSeparator {
    private class Biquad(lp: Boolean, fc: Double) {
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0; var z1 = 0.0; var z2 = 0.0
        init { val w = 2 * PI * min(fc, SR * 0.45) / SR; val c = cos(w); val al = sin(w) / (2 * 0.70710678); val a0 = 1 + al
            if (lp) { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0 } else { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0 }
            a1 = -2 * c / a0; a2 = (1 - al) / a0 }
        fun p(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
    }
    val names = listOf("vocals" to "Vocals", "drums" to "Drums", "bass" to "Bass", "other" to "Guitar / Other")

    /** Returns list of (displayName, storedFileName). */
    fun separate(src: File, outDir: File, progress: (String, Float) -> Unit): List<Pair<String, String>> {
        progress("Decoding…", 0.02f)
        val x = AudioDecoder.decode(src)
        val bL1 = Biquad(true, 150.0); val bL2 = Biquad(true, 150.0); val mH1 = Biquad(false, 150.0); val mH2 = Biquad(false, 150.0)
        val vL1 = Biquad(true, 2500.0); val vL2 = Biquad(true, 2500.0); val hH1 = Biquad(false, 2500.0); val hH2 = Biquad(false, 2500.0)
        val dL1 = Biquad(true, 9000.0); val dL2 = Biquad(true, 9000.0); val oH1 = Biquad(false, 9000.0); val oH2 = Biquad(false, 9000.0)
        val out = Array(4) { FloatArray(x.size) }
        for (i in x.indices) { val s = x[i].toDouble()
            val bass = bL2.p(bL1.p(s)); val mid = mH2.p(mH1.p(s)); val voc = vL2.p(vL1.p(mid)); val hi = hH2.p(hH1.p(mid))
            val dr = dL2.p(dL1.p(hi)); val ot = oH2.p(oH1.p(hi))
            out[0][i] = voc.toFloat(); out[1][i] = dr.toFloat(); out[2][i] = bass.toFloat(); out[3][i] = ot.toFloat()
            if (i % 44100 == 0) progress("Splitting into stems…", 0.05f + 0.85f * i / x.size) }
        progress("Writing stems…", 0.95f)
        val tag = java.util.UUID.randomUUID().toString().take(8); val base = src.nameWithoutExtension.take(24).replace(Regex("[^A-Za-z0-9]"), "_")
        return names.mapIndexed { k, (key, disp) -> val fn = "stem-$tag-$base-$key.wav"
            MixRenderer.writeWavInterleaved(File(outDir, fn), out[k], 1); disp to fn }
            .also { progress("Done", 1f) }
    }
}

/** Autocorrelation pitch detector for the tuner. */
object Tuner {
    val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    fun detect(x: FloatArray, n: Int): Float? {
        var rms = 0.0; for (i in 0 until n) rms += x[i] * x[i]; if (sqrt(rms / n) < 0.01) return null
        val minLag = SR / 1200; val maxLag = min(SR / 60, n / 2); var best = -1; var bestV = 0.0
        for (lag in minLag..maxLag) { var s = 0.0; for (i in 0 until n - lag) s += x[i] * x[i + lag]; if (s > bestV) { bestV = s; best = lag } }
        return if (best > 0) SR.toFloat() / best else null
    }
    fun describe(f: Float): Pair<String, Int> { val m = 69 + 12 * ln(f / 440.0) / ln(2.0); val r = m.roundToInt()
        return (names[((r % 12) + 12) % 12] + (r / 12 - 1)) to ((m - r) * 100).roundToInt() }
}

/** Standard MIDI File (type 0) read/write for synth/guitar track notes. */
object MidiFile {
    private const val PPQ = 480
    fun write(f: File, notes: List<SynthNote>, bpm: Int) {
        val ev = ByteArrayOutputStream(); fun vlq(v: Int) { var b = v and 0x7F; var x = v shr 7; val st = ArrayList<Int>(); st.add(b)
            while (x > 0) { st.add((x and 0x7F) or 0x80); x = x shr 7 }; st.reversed().forEach { ev.write(it) } }
        val tpq = 60_000_000 / bpm; vlq(0); ev.write(byteArrayOf(0xFF.toByte(), 0x51, 3, (tpq shr 16).toByte(), (tpq shr 8).toByte(), tpq.toByte()))
        val spt = 60.0 / bpm / PPQ
        val list = notes.flatMap { listOf(Triple((it.startTime / spt).toInt(), 0x90, it), Triple((it.endTime / spt).toInt(), 0x80, it)) }.sortedBy { it.first }
        var last = 0; for ((t, st, n) in list) { vlq(t - last); last = t; ev.write(st); ev.write(n.pitch); ev.write(if (st == 0x90) (n.velocity * 127).toInt().coerceIn(1, 127) else 0) }
        vlq(0); ev.write(byteArrayOf(0xFF.toByte(), 0x2F, 0)); val body = ev.toByteArray()
        f.outputStream().use { o -> o.write("MThd".toByteArray()); o.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, (PPQ shr 8).toByte(), PPQ.toByte()))
            o.write("MTrk".toByteArray()); o.write(byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte())); o.write(body) }
    }
    /** Reads note on/off from all tracks (assumes constant tempo = session bpm unless file tempo present). */
    fun read(b: ByteArray, bpmDefault: Int): List<SynthNote> {
        fun u16(i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
        fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
        val ppq = u16(12).takeIf { it > 0 && it and 0x8000 == 0 } ?: 480; var tempo = 60_000_000 / bpmDefault
        val out = ArrayList<SynthNote>(); var p = 14
        while (p + 8 <= b.size) { val len = u32(p + 4); if (String(b, p, 4) != "MTrk") { p += 8 + len; continue }
            var i = p + 8; val end = min(b.size, i + len); var tick = 0L; var run = 0; val open = HashMap<Int, Pair<Long, Int>>()
            fun vlq(): Int { var v = 0; while (i < end) { val c = b[i++].toInt() and 255; v = (v shl 7) or (c and 0x7F); if (c and 0x80 == 0) break }; return v }
            while (i < end) { tick += vlq(); if (i >= end) break; var st = b[i].toInt() and 255
                if (st < 0x80) st = run else i++
                when { st == 0xFF -> { val ty = b[i++].toInt() and 255; val l = vlq(); if (ty == 0x51 && l == 3) tempo = ((b[i].toInt() and 255) shl 16) or ((b[i + 1].toInt() and 255) shl 8) or (b[i + 2].toInt() and 255); i += l }
                    st == 0xF0 || st == 0xF7 -> i += vlq()
                    else -> { run = st; val hi = st and 0xF0; val d1 = b[i++].toInt() and 255; val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else b[i++].toInt() and 255
                        val sec = tempo / 1e6 / ppq
                        if (hi == 0x90 && d2 > 0) open[d1] = tick to d2
                        else if (hi == 0x80 || hi == 0x90) open.remove(d1)?.let { (t0, v) -> out.add(SynthNote(pitch = d1, startTime = t0 * sec, duration = max(0.05, (tick - t0) * sec), velocity = v / 127f)) } } } }
            p += 8 + len }
        return out
    }
}

package com.aimusicstudio.app

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.*
import kotlin.random.Random

/** Pedalboard, amp sims, guitar voices, drum kits, sends. Lightweight offline DSP (all synthesized — no licensed samples). */
object Fx {
    /** RBJ biquad (lowpass / highpass / bandpass / peak / shelves) used by amps, cabs, pedals and guitar bodies. */
    class BQ private constructor(private val b0: Float, private val b1: Float, private val b2: Float, private val a1: Float, private val a2: Float) {
        private var z1 = 0f; private var z2 = 0f
        fun p(x: Float): Float { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
        companion object {
            private fun mk(type: Int, fc: Double, q: Double, gainDb: Double = 0.0): BQ {
                val w = 2 * PI * fc.coerceIn(10.0, SR * 0.45) / SR; val c = cos(w); val s = sin(w); val al = s / (2 * q); val A = 10.0.pow(gainDb / 40)
                val (b, a) = when (type) {
                    0 -> doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2) to doubleArrayOf(1 + al, -2 * c, 1 - al)
                    1 -> doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2) to doubleArrayOf(1 + al, -2 * c, 1 - al)
                    2 -> doubleArrayOf(al, 0.0, -al) to doubleArrayOf(1 + al, -2 * c, 1 - al)
                    3 -> doubleArrayOf(1 + al * A, -2 * c, 1 - al * A) to doubleArrayOf(1 + al / A, -2 * c, 1 - al / A)
                    4 -> { val r = 2 * sqrt(A) * al  // low shelf
                        doubleArrayOf(A * ((A + 1) - (A - 1) * c + r), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - r)) to
                            doubleArrayOf((A + 1) + (A - 1) * c + r, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - r) }
                    else -> { val r = 2 * sqrt(A) * al // high shelf
                        doubleArrayOf(A * ((A + 1) + (A - 1) * c + r), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - r)) to
                            doubleArrayOf((A + 1) - (A - 1) * c + r, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - r) }
                }
                return BQ((b[0] / a[0]).toFloat(), (b[1] / a[0]).toFloat(), (b[2] / a[0]).toFloat(), (a[1] / a[0]).toFloat(), (a[2] / a[0]).toFloat())
            }
            fun lp(fc: Double, q: Double = 0.707) = mk(0, fc, q); fun hp(fc: Double, q: Double = 0.707) = mk(1, fc, q)
            fun bp(fc: Double, q: Double = 1.0) = mk(2, fc, q); fun peak(fc: Double, q: Double, db: Double) = mk(3, fc, q, db)
            fun lowShelf(fc: Double, db: Double) = mk(4, fc, 0.707, db); fun highShelf(fc: Double, db: Double) = mk(5, fc, 0.707, db)
        }
    }
    private fun FloatArray.run(f: BQ) { for (i in indices) this[i] = f.p(this[i]) }
    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }
    private fun rms(x: FloatArray): Float { if (x.isEmpty()) return 0f; var s = 0.0; for (v in x) s += v * v; return sqrt(s / x.size).toFloat() }
    /** Keep a processed block near its input loudness so drive/fuzz pedals don't blow up levels. */
    private fun matchLevel(x: FloatArray, refRms: Float, trim: Float = 1f) { val r = rms(x); if (r > 1e-6f && refRms > 1e-6f) { val g = (refRms / r * trim).coerceIn(0.1f, 4f); for (i in x.indices) x[i] *= g } }
    private fun interp(src: FloatArray, j: Double): Float { val k = floor(j).toInt(); if (k < 0 || k + 1 >= src.size) return 0f; val f = (j - k).toFloat(); return src[k] + (src[k + 1] - src[k]) * f }

    fun pedal(x: FloatArray, p: PedalSlot, bpm: Int) {
        if (!p.on) return
        pedalCore(x, p, bpm)
        // Second (TONE) knob: tilt filter after the effect — dark below 0.5, bright above.
        val tn = p.tone.coerceIn(0f, 1f); if (abs(tn - 0.5f) > 0.02f) { var lp = 0f; val c = 0.05f + tn * 0.9f
            for (i in x.indices) { lp += c * (x[i] - lp); x[i] = if (tn < 0.5f) lp else x[i] + (x[i] - lp) * (tn - 0.5f) * 1.2f } }
        // Safety: soft-limit anything a pedal pushes past full scale.
        if (peakOf(x) > 0.98f) for (i in x.indices) x[i] = tanh(x[i])
    }
    private fun pedalCore(x: FloatArray, p: PedalSlot, bpm: Int) {
        val a = p.amount.coerceIn(0f, 1f); val inRms = rms(x)
        when (p.type) {
            // Screamer-style OD: low cut before clipping (tight bass), mid hump, asymmetric soft clip, smoothed top, level-matched.
            PedalType.OVERDRIVE -> { val pre = BQ.hp(720.0, 0.6); val hump = BQ.peak(800.0, 0.8, 4.0 + a * 4); val post = BQ.lp(5200.0 - a * 1800); val g = 2 + a * 22
                for (i in x.indices) { val clean = x[i]; val d = hump.p(pre.p(clean) * g + clean * 0.4f); val y = if (d >= 0) tanh(d) else tanh(d * 0.8f) * 1.1f
                    x[i] = post.p(y + clean * 0.25f) }
                matchLevel(x, inRms, 1.15f) }
            // Rat-style distortion: op-amp gain into hard-ish diode clip, filter sweep, scooped slightly.
            PedalType.DISTORTION -> { val pre = BQ.hp(120.0); val post = BQ.lp(3800.0 + (1 - a) * 2400); val scoop = BQ.peak(650.0, 0.7, -3.0); val g = 8 + a * 60
                for (i in x.indices) { val d = pre.p(x[i]) * g; val y = d / (1 + abs(d)).pow(0.6f) ; x[i] = scoop.p(post.p(y.coerceIn(-1.2f, 1.2f))) }
                matchLevel(x, inRms, 1.2f) }
            // Two-voice stereo-style chorus summed to mono with interpolated modulated delays.
            PedalType.CHORUS -> { val src = x.copyOf(); val base = 0.011 * SR; val dep = (0.0015 + 0.003 * a) * SR
                for (i in x.indices) { val ph = 2 * PI * 0.7 * i / SR
                    val w1 = interp(src, i - base - dep * (0.5 + 0.5 * sin(ph))); val w2 = interp(src, i - base * 1.35 - dep * (0.5 + 0.5 * sin(ph + 2.1)))
                    x[i] = src[i] * (1 - a * 0.35f) + (w1 + w2) * 0.5f * (0.35f + a * 0.45f) } }
            // Analog-flavoured delay: dotted-8th, repeats darken + lose highs each pass.
            PedalType.DELAY -> { val d = (60.0 / bpm * 0.75 * SR).toInt(); if (d > 0) { val fb = 0.3f + a * 0.45f; val y = x.copyOf(); val lp = BQ.lp(3200.0); val hp = BQ.hp(140.0)
                for (i in d until y.size) y[i] = x[i] + hp.p(lp.p(y[i - d])) * fb
                for (i in x.indices) x[i] = x[i] + (y[i] - x[i]) * (0.35f + a * 0.5f) } }
            PedalType.REVERB -> reverb(x, a)
            // Cry-baby-style wah: resonant bandpass swept 450 Hz → 2.2 kHz (LFO-driven "auto" foot), Q ~ 4.
            // Foot sweep is tempo-synced (one rock per beat, slower at low amount), shaped like a real treadle (eased raised-cosine),
            // nudged by pick dynamics, and parameter-smoothed so the filter never zips.
            PedalType.WAH -> { var lp = 0f; var bp = 0f; val q = 0.2f; var env = 0f; var fcS = 800.0
                val rate = 60.0 / bpm * (if (a < 0.4f) 2.0 else 1.0)
                for (i in x.indices) { env = max(abs(x[i]), env * 0.9993f)
                    val ph = (i.toDouble() / SR / rate) % 1.0; val c = 0.5 - 0.5 * cos(2 * PI * ph); val sw = (c * c * (3 - 2 * c) * 0.8 + min(1.0, env * 3.0) * 0.2).coerceIn(0.0, 1.0)
                    fcS += (420 * (2300.0 / 420).pow(sw) - fcS) * 0.004
                    val f = (2 * sin(PI * fcS / SR)).toFloat(); val hp = x[i] - lp - q * bp; bp += f * hp; lp += f * bp; x[i] = x[i] * (1 - a * 0.85f) + bp * (0.6f + a) * 1.3f }
                matchLevel(x, inRms, 1.1f) }
            PedalType.COMPRESSOR -> MixRenderer.applyCompressor(x, TrackCompressorSettings(true, -10f - a * 25f, 2f + a * 8f, 8f, 120f, a * 10f))
            PedalType.TREMOLO -> for (i in x.indices) x[i] *= (1 - a * (0.5f + 0.5f * sin(2 * PI * 5 * i / SR).toFloat()))
            // Fuzz-Face-style: massive gain, asymmetric (germanium-ish bias), gated sputter at low input, woolly top.
            PedalType.FUZZ -> { val g = 20 + a * 120; val post = BQ.lp(3000.0); val hp = BQ.hp(90.0)
                for (i in x.indices) { val y = hp.p(x[i]) * g + 0.15f; val c = if (y > 0) 1 - exp(-y) else -(1 - exp(y * 1.4f)) * 0.85f; x[i] = post.p(c - 0.13f) }
                matchLevel(x, inRms, 1.25f) }
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
            PedalType.OCTAVER -> { val t1 = BQ.lp(380.0, 0.6); val t2 = BQ.lp(380.0, 0.6); val hp = BQ.hp(60.0); val sm1 = BQ.lp(420.0); val sm2 = BQ.lp(600.0)
                var env = 0f; var flip = 1f; var armed = true; var wasPos = false
                for (i in x.indices) { val tr = t2.p(t1.p(hp.p(x[i]))); env = max(abs(tr), env * 0.9985f); val h = env * 0.12f
                    if (armed && tr > h && !wasPos) { flip = -flip; wasPos = true; armed = false } else if (tr < -h) { wasPos = false; armed = true }
                    val sub = sm2.p(sm1.p(flip * env)) * 1.8f
                    x[i] = x[i] * (1 - a * 0.45f) + sub * a * 1.6f }
                matchLevel(x, inRms, 1.1f) }
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
        val inRms = rms(x)
        val isBass = m.label.contains("Bass") || m == AmpModel.AMPEG_SVT || m == AmpModel.GALLIEN
        val acoustic = m == AmpModel.ACOUSTIC_DI
        val hiGain = m.gain >= 12f
        val name = m.name
        // Voicing: British = mid-forward, American clean = scooped/sparkly, Mesa/Recto = tight + saggy low end, AC30-style = chimey upper mids.
        val (midHz, midDb) = when {
            name.contains("BRIT") || name.contains("PLEXI") || name.contains("JCM") || name.contains("JTM") || name.contains("FRIEDMAN") || name.contains("SOLDANO") -> 900.0 to 3.5
            name.contains("TWIN") || name.contains("DELUXE") || name == "CLEAN" || name.contains("ROLAND") -> 500.0 to -3.0
            name.contains("RECT") || name.contains("RECTO") || name.contains("5150") || name.contains("6505") || name == "METAL" || name == "EVH" -> 600.0 to -4.0
            name.contains("CHIME") -> 2200.0 to 3.0
            name.contains("MARK") -> 750.0 to 2.0
            isBass -> 800.0 to 1.0
            else -> 1000.0 to 0.0
        }
        // Tone stack (knobs) + per-model mid voicing, before the preamp like a real amp's interstage EQ.
        MixRenderer.applyEQ(x, TrackEQSettings(m.bass + (bass - 0.5f) * 18f, (mid - 0.5f) * 16f, m.treble + (treble - 0.5f) * 18f))
        x.run(BQ.peak(midHz, 0.9, midDb))
        // High-gain amps tighten the low end before the clipping stages (like a TS boost / tight switch).
        if (hiGain && !isBass) x.run(BQ.hp(110.0 + m.gain * 2.5))
        val g = (1 + m.gain) * (0.15f + gain * 1.7f) + gain * 2f
        val bias = if (acoustic) 0f else 0.08f + min(m.gain, 30f) * 0.004f // tube-like asymmetry → even harmonics
        val norm = 1f / tanh(g * 0.5f).coerceAtLeast(0.2f)
        val coup = BQ.hp(25.0) // removes DC from asymmetric clipping
        if (hiGain) { val g1 = sqrt(g) * 1.6f; val g2 = sqrt(g) * 1.3f; val inter = BQ.lp(6500.0)
            for (i in x.indices) { val s1 = tanh(x[i] * g1 + bias) - tanh(bias); val s2 = tanh(inter.p(s1) * g2 + bias * 0.5f) - tanh(bias * 0.5f); x[i] = coup.p(s2) * 0.55f } }
        else for (i in x.indices) { val y = x[i] * g; x[i] = coup.p(tanh(y + bias) - tanh(bias)) * norm * 0.5f }
        // Cabinet: speaker low cut, resonance bump, cone break-up roll-off (opened by Presence), presence peak.
        if (acoustic) { x.run(BQ.highShelf(6000.0, 2.0)) } else cab(x, cabFor(m, isBass, hiGain), presence)
        matchLevel(x, inRms, 1.6f)
        val out = 0.2f + master * 1.4f; for (i in x.indices) x[i] = tanh(x[i] * out)
    }


    /** Speaker cabinet families, chosen from the amp's character (no UI needed). */
    enum class Cab { V30_4X12, GREENBACK_4X12, OPEN_2X12, TWEED_1X12, BASS_8X10 }
    fun cabFor(m: AmpModel, isBass: Boolean, hiGain: Boolean): Cab { val n = m.name
        return when { isBass -> Cab.BASS_8X10; hiGain || n.contains("RECT") || n.contains("5150") || n.contains("6505") || n.contains("MARK") -> Cab.V30_4X12
            n.contains("BRIT") || n.contains("PLEXI") || n.contains("JCM") || n.contains("JTM") -> Cab.GREENBACK_4X12
            n.contains("TWEED") || n.contains("DELUXE") -> Cab.TWEED_1X12; else -> Cab.OPEN_2X12 } }
    /** IR-like cabinet: a close dynamic mic on the cone (proximity bass, cone resonance, 2–5 kHz presence ridge, breakup notches, steep
     *  rolloff) blended with a darker room mic (~1.3 ms later → gentle comb, less highs). Presence opens the close mic's top. */
    fun cab(x: FloatArray, c: Cab, presence: Float) {
        // (resonance Hz, low-mid dip Hz, presence ridge Hz, notch Hz, rolloff Hz)
        val (res, dip, ridge, notch, roll) = when (c) {
            Cab.V30_4X12 -> listOf(105.0, 420.0, 2800.0, 1900.0, 5200.0); Cab.GREENBACK_4X12 -> listOf(95.0, 500.0, 2200.0, 3600.0, 4800.0)
            Cab.OPEN_2X12 -> listOf(120.0, 350.0, 3400.0, 2400.0, 6200.0); Cab.TWEED_1X12 -> listOf(130.0, 700.0, 2600.0, 3900.0, 5000.0)
            Cab.BASS_8X10 -> listOf(60.0, 300.0, 1600.0, 1100.0, 3600.0) }
        val room = x.copyOf()
        val close = listOf(BQ.hp(if (c == Cab.BASS_8X10) 35.0 else 75.0, 0.8), BQ.peak(res, 2.2, 5.0), BQ.lowShelf(180.0, 1.5), BQ.peak(dip, 1.0, -3.0),
            BQ.peak(ridge, 1.3, 3.5 + (presence - 0.5) * 6.0), BQ.peak(ridge * 1.6, 2.0, 1.5), BQ.peak(notch, 4.0, -4.5), BQ.peak(notch * 2.3, 5.0, -3.0),
            BQ.lp(roll + presence * 2200.0, 0.9), BQ.lp((roll + presence * 2200.0) * 1.25, 0.6))
        for (i in x.indices) { var v = x[i]; for (f in close) v = f.p(v); x[i] = v }
        val rf = listOf(BQ.hp(70.0), BQ.peak(res * 0.9, 1.5, 3.0), BQ.peak(800.0, 0.8, 1.5), BQ.lp(roll * 0.55, 0.7), BQ.lp(roll * 0.7, 0.6))
        for (i in room.indices) { var v = room[i]; for (f in rf) v = f.p(v); room[i] = v }
        val dly = (0.0013 * SR).toInt(); val mixR = if (c == Cab.OPEN_2X12 || c == Cab.TWEED_1X12) 0.32f else 0.22f
        for (i in x.indices.reversed()) x[i] = x[i] * (1 - mixR) + (if (i >= dly) room[i - dly] else 0f) * mixR
    }

    /** Live instrument chain: pads the note with an FX tail (delay repeats, reverb, octave/amp ring) so time-based pedals
     *  aren't cut off at the note end, then runs pedals → amp. */
    fun liveChain(x0: FloatArray, t: Track, bpm: Int): FloatArray {
        val timeFx = t.pedals.any { it.on && (it.type == PedalType.DELAY || it.type == PedalType.REVERB) }
        val tail = if (timeFx) (60.0 / bpm * 2.5 * SR).toInt().coerceAtMost(SR * 3) else (0.25 * SR).toInt()
        val x = x0.copyOf(x0.size + tail)
        t.pedals.forEach { pedal(x, it, bpm) }; amp(x, t)
        var end = x.size; while (end > x0.size && abs(x[end - 1]) < 1e-4f) end--
        val y = x.copyOf(end); val fade = min(256, y.size); for (i in 0 until fade) y[y.size - 1 - i] *= i.toFloat() / fade
        return y
    }

    /** Make TTS phrasing feel sung: fit each line to the beat grid with an overlap-add stretch (pitch kept),
     *  add delayed-onset vibrato on the tail of the phrase, and a gender/style formant-safe pitch nudge by resample. */
    fun singify(x: FloatArray, bpm: Int, pitchShift: Float = 1f, vibrato: Float = 0.5f): FloatArray {
        if (x.size < SR / 10) return x
        val beat = 60.0 / bpm * SR
        val d = x.size.toDouble(); val beats = max(1.0, round(d / beat)); val target = (beats * beat).coerceIn(d * 0.85, d * 1.2)
        var y = olaStretch(x, target / d)
        if (abs(pitchShift - 1f) > 0.01f) y = olaStretch(resample(y, pitchShift), pitchShift.toDouble())
        // Vibrato: ~5.5 Hz, depth ramps in after 250 ms (singers settle then vibrate), ±~30 cents.
        val src = y.copyOf(); val base = 0.006 * SR; val maxDep = 0.00035 * SR * vibrato * 2
        for (i in y.indices) { val t = i.toDouble() / SR; val dep = maxDep * min(1.0, max(0.0, (t - 0.25) / 0.6))
            val j = i - base - dep * sin(2 * PI * 5.5 * t); y[i] = if (j >= 0) interp(src, j) else src[i] }
        return y
    }
    /** Simple windowed overlap-add time stretch (ratio > 1 = longer). */
    fun olaStretch(x: FloatArray, ratio: Double): FloatArray {
        if (abs(ratio - 1.0) < 0.01) return x
        val win = (0.04 * SR).toInt(); val hopOut = win / 4; val hopIn = hopOut / ratio
        val n = (x.size * ratio).toInt(); val out = FloatArray(n + win); val norm = FloatArray(n + win)
        val w = FloatArray(win) { (0.5 - 0.5 * cos(2 * PI * it / win)).toFloat() }
        var o = 0; var ii = 0.0
        while (o < n) { val s = ii.toInt(); for (k in 0 until win) { val si = s + k; if (si >= x.size) break; out[o + k] += x[si] * w[k]; norm[o + k] += w[k] }
            o += hopOut; ii += hopIn }
        return FloatArray(n) { if (norm[it] > 1e-3f) out[it] / norm[it] else 0f }
    }

    /** Velocity + humanised variation on top of the cached kit voice: louder = brighter + slightly sharper,
     *  softer = darker; each hit gets a seeded ±~1.5% tune and ±1 dB level wobble so repeats aren't clones. */
    fun drumHit(kit: DrumKit, pad: DrumPad, vel: Float = 0.85f, variation: Int = 0): FloatArray {
        val base = kitDrum(kit, pad); val v = vel.coerceIn(0.05f, 1f); val r = Random(variation * 7919 + pad.ordinal * 31 + kit.ordinal)
        val rate = (1f + (r.nextFloat() - 0.5f) * 0.03f + (v - 0.8f) * 0.02f)
        val y = resample(base, rate)
        val gain = v.pow(1.4f) * 10f.pow((r.nextFloat() - 0.5f) * 0.1f) / 0.85f.pow(1.4f)
        val c = (0.25f + v * 0.75f).coerceAtMost(1f); var lp = 0f
        for (i in y.indices) { lp += c * (y[i] - lp); y[i] = (lp + (y[i] - lp) * (v - 0.4f).coerceAtLeast(0f)) * gain }
        return y
    }

    /** Post-TTS vocal chain: rumble cut, de-box, presence, air, gentle compression, optional doubler + short plate.
     *  Pop/Rock/Country get a light ADT-style double (slightly detuned, ~18 ms late); Ballad/Soul get more space. */
    fun vocalPolish(x: FloatArray, style: VocalStyle): FloatArray {
        if (x.isEmpty()) return x
        val inPk = peakOf(x)
        x.run(BQ.hp(95.0)); x.run(BQ.peak(350.0, 1.0, -2.5)); x.run(BQ.peak(3200.0, 0.9, 3.0)); x.run(BQ.highShelf(9000.0, 2.5))
        MixRenderer.applyCompressor(x, TrackCompressorSettings(true, -20f, 3f, 6f, 90f, 4f))
        val y = if (style == VocalStyle.POP || style == VocalStyle.ROCK || style == VocalStyle.COUNTRY) {
            val dbl = resample(x, 1.004f); val off = (0.018 * SR).toInt(); val o = x.copyOf(x.size + off)
            for (i in dbl.indices) if (i + off < o.size) o[i + off] += dbl[i] * 0.32f; o } else x.copyOf(x.size + SR / 3)
        reverb(y, when (style) { VocalStyle.BALLAD, VocalStyle.SOUL -> 0.28f; VocalStyle.RAP -> 0.08f; else -> 0.16f })
        val pk = peakOf(y); if (pk > 0f) { val g = (max(inPk, 0.5f) * 0.95f / pk).coerceAtMost(4f); for (i in y.indices) y[i] *= g }
        return y
    }

    fun delay(x: FloatArray, d: Int, fb: Float, mix: Float) {
        if (d <= 0) return
        val y = x.copyOf(); for (i in d until y.size) y[i] += y[i - d] * fb
        for (i in x.indices) x[i] = x[i] * (1 - mix * 0.5f) + (y[i] - x[i]) * mix + x[i] * mix * 0.5f
    }

    /** Freeverb-lite: 4 parallel combs + 2 allpass. */
    fun reverb(x: FloatArray, mix: Float, size: Float = 0.5f, damp: Float = 0.35f) {
        // Full Freeverb topology: 8 damped combs (decorrelated tunings), 4 series allpasses, ~14 ms predelay, HP'd input so lows don't mud up.
        val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617); val out = FloatArray(x.size)
        val pre = (0.014 * SR).toInt(); val fb = 0.78f + size.coerceIn(0f, 1f) * 0.18f; val d1 = damp.coerceIn(0f, 0.9f); val inHp = BQ.hp(180.0)
        val inp = FloatArray(x.size) { if (it >= pre) x[it - pre] else 0f }; for (i in inp.indices) inp[i] = inHp.p(inp[i])
        for (d in combs) { val buf = FloatArray(d); var k = 0; var lp = 0f
            for (i in inp.indices) { val y = buf[k]; lp = y * (1 - d1) + lp * d1; buf[k] = inp[i] * 0.3f + lp * fb; k = (k + 1) % d; out[i] += y * 0.125f } }
        for (d in intArrayOf(556, 441, 341, 225)) { val buf = FloatArray(d); var k = 0
            for (i in out.indices) { val b = buf[k]; val y = -out[i] + b; buf[k] = out[i] + b * 0.5f; k = (k + 1) % d; out[i] = y } }
        for (i in x.indices) x[i] = x[i] * (1 - mix * 0.5f) + out[i] * mix * 2.2f
    }

    /** Karplus-Strong plucked string with pick-position comb, pick transient, interpolated tuning and body resonances
     *  shaped by the guitar model (brightness = pickup/string brightness, body = resonant mass). */
    fun guitarNote(g: GuitarModel, pitch: Int, dur: Double, vel: Float = 0.8f): FloatArray {
        val f = InstrumentSoundGenerator.freq(pitch); val bass = g.label.contains("Bass")
        val acoustic = g in setOf(GuitarModel.ACOUSTIC, GuitarModel.NYLON, GuitarModel.TAYLOR, GuitarModel.RESO, GuitarModel.JAZZBOX)
        val pd = SR / f; val p = max(2, pd.toInt()); val frac = (pd - p).toFloat(); val n = ((dur + (if (bass) 0.8 else 0.6)) * SR).toInt()
        val rnd = Random(pitch * 7 + g.ordinal)
        // Excitation: noise burst, darker at low velocity, then pick-position comb (picked ~1/8–1/4 from bridge).
        val buf = FloatArray(p) { rnd.nextFloat() * 2 - 1 }
        val vb = (0.35f + vel * 0.6f) * (0.4f + g.brightness * 0.6f)
        var lpb = 0f; for (i in buf.indices) { lpb += vb * (buf[i] - lpb); buf[i] = lpb }
        val pick = max(1, (p * (0.12f + (1 - g.brightness) * 0.14f)).toInt()); val ex = buf.copyOf(); for (i in buf.indices) buf[i] = ex[i] - ex[(i - pick + p) % p] * 0.9f
        var m = 0f; for (v in buf) m += v; m /= p; for (i in buf.indices) buf[i] -= m
        val out = FloatArray(n); var k = 0
        val damp = (if (bass) 0.9975f else 0.9962f) + g.brightness * 0.002f - (1 - g.brightness) * 0.002f
        val blend = 0.5f - g.brightness * 0.12f // loss filter weighting: brighter models keep more top end
        var ap = 0f; val apc = (1 - frac) / (1 + frac) // allpass fractional delay → in-tune high notes
        for (i in 0 until n) { val nx = (k + 1) % p; val lpv = (buf[k] * (1 - blend) + buf[nx] * blend) * damp
            val y = apc * lpv + ap; ap = lpv - apc * y
            out[i] = buf[k]; buf[k] = y; k = nx
            val t = i.toDouble() / SR; if (t > dur) out[i] *= exp(-(t - dur) * 14).toFloat() }
        // Pick attack click (short bright noise) — stronger on bright/electric models.
        val clickN = (0.004 * SR).toInt(); for (i in 0 until min(clickN, n)) out[i] += (rnd.nextFloat() * 2 - 1) * (1 - i.toFloat() / clickN) * 0.25f * vel * (0.4f + g.brightness)
        // Body: two resonant modes (air + top plate) mixed by body amount; acoustics get a third higher mode.
        val b1 = BQ.bp(if (bass) 70.0 else 98.0 + g.body * 20, 2.5); val b2 = BQ.bp(if (bass) 160.0 else 205.0 + g.body * 40, 3.0); val b3 = BQ.bp(420.0, 2.0)
        val hs = BQ.highShelf(3500.0, (g.brightness - 0.5) * 8.0)
        for (i in out.indices) { val s = out[i]; var y = s + (b1.p(s) * 1.4f + b2.p(s)) * g.body * 1.2f
            if (acoustic) y += b3.p(s) * 0.5f * g.body; out[i] = hs.p(y) }
        val pk = peakOf(out); val norm = if (pk > 0) 0.9f / pk else 1f
        for (i in out.indices) out[i] *= norm * vel * 0.6f
        return out
    }

    private val kitCache = HashMap<Pair<DrumKit, DrumPad>, FloatArray>()
    const val KIT_VERSION = 3
    /** cym = cymbal brightness/size multiplier, hatTight = closed-hat tightness. */
    private class Kit(val tune: Double, val decay: Double, val noise: Double, val drive: Double, val room: Float, val crush: Int = 0, val dark: Float = 1f,
                      val cym: Double = 1.0, val hatTight: Double = 1.0)
    private fun kitSpec(k: DrumKit) = when (k) {
        DrumKit.ROCK -> Kit(1.0, 1.0, 1.0, 1.3, 0.22f)
        DrumKit.ACOUSTIC -> Kit(1.08, 1.25, 0.8, 1.0, 0.32f, dark = 0.8f, cym = 0.9)
        DrumKit.METAL -> Kit(1.15, 0.7, 1.2, 2.2, 0.15f, cym = 1.1, hatTight = 1.3)
        DrumKit.JAZZ_BRUSH -> Kit(1.25, 1.4, 0.6, 1.0, 0.35f, dark = 0.6f, cym = 0.8, hatTight = 0.8)
        DrumKit.TR808 -> Kit(0.85, 1.0, 0.5, 1.1, 0f)
        DrumKit.VINTAGE909 -> Kit(1.0, 0.8, 1.0, 1.5, 0.05f)
        DrumKit.TRAP -> Kit(0.8, 1.0, 0.7, 1.8, 0f, hatTight = 1.5)
        DrumKit.HOUSE -> Kit(1.0, 0.7, 0.9, 1.3, 0.08f)
        DrumKit.DNB -> Kit(1.2, 0.6, 1.1, 1.6, 0.1f, hatTight = 1.2)
        DrumKit.ELECTRONIC -> Kit(1.1, 0.8, 0.9, 1.6, 0.06f)
        DrumKit.LOFI -> Kit(0.9, 0.9, 0.7, 1.2, 0.12f, crush = 10, dark = 0.5f)
        DrumKit.PROG_ARENA -> Kit(0.95, 1.5, 1.0, 1.2, 0.38f, cym = 1.15)       // deep shells, long tom ring, big hall
        DrumKit.MASK_METAL -> Kit(1.2, 0.55, 1.3, 2.6, 0.12f, hatTight = 1.4)    // clicky triggered kick, cracking snare
        DrumKit.DJENT -> Kit(1.1, 0.5, 1.1, 2.0, 0.08f, dark = 0.9f, hatTight = 1.5) // tight, dry, gated
        DrumKit.PEARL_STAGE -> Kit(1.03, 1.05, 1.05, 1.25, 0.24f, cym = 1.05)   // punchy maple/birch stage kit, focused attack
        DrumKit.DW_COLLECTOR -> Kit(0.96, 1.3, 0.9, 1.12, 0.3f, dark = 0.93f, cym = 0.95) // warm deep shells, long sustain
        DrumKit.SABIAN_AA -> Kit(1.0, 1.0, 1.0, 1.2, 0.22f, cym = 1.3, hatTight = 1.1)   // bright, cutting cast-bronze cymbal focus
    }
    /** Inharmonic metal: sum of sine (acoustic) or square (analog-machine) partials, HP'd noise mixed in. */
    private fun metal(dur: Double, freqs: DoubleArray, square: Boolean, noiseAmt: Double, hpHz: Double, seed: Int, env: (Double) -> Double): FloatArray {
        val rnd = Random(seed); val n = (dur * SR).toInt(); val y = FloatArray(n)
        val ph = DoubleArray(freqs.size) { rnd.nextDouble() }
        for (i in 0 until n) { val t = i.toDouble() / SR; var s = 0.0
            for (j in freqs.indices) { val v = sin(2 * PI * (freqs[j] * t + ph[j])); s += if (square) sign(v) else v }
            y[i] = ((s / freqs.size) * (1 - noiseAmt) + rnd.nextDouble(-1.0, 1.0) * noiseAmt).toFloat() * env(t).toFloat() }
        val h1 = BQ.hp(hpHz); val h2 = BQ.hp(hpHz * 1.1); for (i in y.indices) y[i] = h2.p(h1.p(y[i]))
        return y
    }
    private val HAT_ACOUSTIC = doubleArrayOf(3150.0, 4270.0, 5340.0, 6720.0, 8130.0, 9870.0, 11400.0)
    private val HAT_ANALOG = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0).map { it * 2.0 }.toDoubleArray() // classic six-oscillator metal bank, an octave up
    private fun cymbalPartials(n: Int, lo: Double, hi: Double, seed: Int): DoubleArray { val r = Random(seed); return DoubleArray(n) { lo * (hi / lo).pow(r.nextDouble()) } }
    /** Per-kit synthesized drum voices (no licensed samples): layered acoustic kick thud + beater click, snare crack + wires,
     *  brush swishes, long 808 sub, 909 punch, tight metal kicks. Each kit has its own tuning/decay/drive/room. */
    fun kitDrum(kit: DrumKit, pad: DrumPad): FloatArray = synchronized(kitCache) { kitCache.getOrPut(kit to pad) {
        val k = kitSpec(kit); val rnd = Random(kit.ordinal * 31 + pad.ordinal + KIT_VERSION * 1000)
        fun n() = rnd.nextDouble(-1.0, 1.0)
        fun gen(dur: Double, f: (Double) -> Double) = FloatArray((dur * SR).toInt()) { f(it.toDouble() / SR).toFloat() }
        val electronic = kit in setOf(DrumKit.TR808, DrumKit.VINTAGE909, DrumKit.TRAP, DrumKit.HOUSE, DrumKit.ELECTRONIC)
        val seed = kit.ordinal * 97 + pad.ordinal
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
            // Closed hat: tight metallic partials + filtered noise, plus a low "chick" of the two plates closing on acoustic kits.
            DrumPad.CLOSED_HAT -> if (electronic) { val tight = 55.0 * k.hatTight / k.decay
                metal(if (kit == DrumKit.TRAP) 0.05 else 0.09, HAT_ANALOG.map { it * k.tune }.toDoubleArray(), true, 0.35, 6500.0, seed) { t -> min(1.0, t / 0.0005) * exp(-tight * t) } }
                else { val tight = 38.0 * k.hatTight / k.decay * (if (kit == DrumKit.JAZZ_BRUSH) 0.7 else 1.0)
                    val m = metal(0.16 * k.decay, HAT_ACOUSTIC.map { it * k.tune }.toDoubleArray(), false, 0.45, 5200.0, seed) { t -> min(1.0, t / 0.0008) * (0.75 * exp(-tight * t) + 0.25 * exp(-tight * 2.5 * t)) }
                    val chick = BQ.bp(1100.0, 1.4); for (i in m.indices) { val t = i.toDouble() / SR; if (t < 0.012) m[i] += chick.p((n() * exp(-300 * t)).toFloat()) * 1.6f }
                    m }
            // Open hat: same plates ringing loose — slow decay, slight wobble shimmer, a sizzle tail.
            DrumPad.OPEN_HAT -> if (electronic) metal(0.45 * k.decay, HAT_ANALOG.map { it * k.tune }.toDoubleArray(), true, 0.3, 6000.0, seed) { t -> min(1.0, t / 0.0007) * exp(-7.5 / k.decay * t) }
                else metal(0.9 * k.decay, HAT_ACOUSTIC.map { it * k.tune * 0.97 }.toDoubleArray(), false, 0.5, 4200.0, seed) { t ->
                    min(1.0, t / 0.001) * (0.6 * exp(-4.2 / k.decay * t) + 0.4 * exp(-14 * t)) * (1 + 0.12 * sin(2 * PI * 6.5 * t)) }
            // Crash-style wash: dense inharmonic partial cloud, bloom after the stick hit, long bright decay.
            DrumPad.CYMBAL -> { val d = (if (kit == DrumKit.JAZZ_BRUSH) 2.4 else 1.9) * k.cym
                metal(d, cymbalPartials(16, 2400.0 * k.tune, 12500.0, seed), false, 0.55, 2600.0, seed) { t ->
                    min(1.0, t / 0.002) * (0.55 * exp(-(if (kit == DrumKit.METAL) 1.6 else 2.1) / k.cym * t) + 0.45 * exp(-9 * t)) * (1 + 0.25 * min(1.0, t / 0.08) * exp(-6 * t)) } }
            DrumPad.CRASH -> { val d = 2.6 * k.cym
                metal(d, cymbalPartials(22, 1900.0 * k.tune, 13500.0, seed + 5), false, 0.6, 2000.0, seed) { t ->
                    min(1.0, t / 0.0025) * (0.6 * exp(-1.5 / k.cym * t) + 0.4 * exp(-7 * t)) * (1 + 0.35 * min(1.0, t / 0.12) * exp(-4 * t)) } }
            // Ride: defined stick "ping" + bell partials over a low, controlled wash.
            DrumPad.RIDE -> { val wash = metal(2.8 * k.cym, cymbalPartials(14, 3000.0 * k.tune, 11000.0, seed + 9), false, 0.35, 2800.0, seed) { t -> 0.35 * min(1.0, t / 0.003) * exp(-1.1 / k.cym * t) }
                for (i in wash.indices) { val t = i.toDouble() / SR; val f0 = 520.0 * k.tune
                    wash[i] += ((sin(2 * PI * f0 * 2.76 * t) * 0.5 + sin(2 * PI * f0 * 5.4 * t) * 0.35 + sin(2 * PI * f0 * 8.9 * t) * 0.2) * exp(-6 * t) + n() * 0.6 * exp(-400 * t)).toFloat() * 0.45f }
                wash }
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

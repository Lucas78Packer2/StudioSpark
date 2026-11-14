package com.studiospark.app.dsp

import kotlin.math.*

/** One real-time audio stage. [p] is the live parameter array owned by the rig slot; knobs write it, the audio thread reads it once per block. */
abstract class Processor(val sr: Float, val p: FloatArray) {
    private val last = FloatArray(p.size) { Float.NaN }
    /** Called once per block; recompute coefficients only when a knob moved. */
    fun block(buf: FloatArray, n: Int) {
        var changed = false
        for (i in p.indices) if (p[i] != last[i]) { last[i] = p[i]; changed = true }
        if (changed) update()
        process(buf, n)
    }
    protected open fun update() {}
    protected abstract fun process(buf: FloatArray, n: Int)
    open fun reset() {}
}

// ---------------- Dynamics ----------------
class NoiseGate(sr: Float, p: FloatArray) : Processor(sr, p) { // threshold dB, release ms, range dB
    private val env = EnvelopeFollower(); private var g = 1f; private var thr = 0f; private var relC = 0f; private var floor = 0f
    override fun update() { thr = Shapers.dbToLin(p[0]); env.set(sr, 0.5f, 30f); relC = exp(-1f / (sr * p[1] / 1000f)); floor = Shapers.dbToLin(-p[2]) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val e = env.process(buf[i]); val target = if (e > thr) 1f else floor
        g = if (target > g) target + 0.2f * (g - target) else target + relC * (g - target); buf[i] *= g } }
}

class Compressor(sr: Float, p: FloatArray) : Processor(sr, p) { // threshold dB, ratio, attack ms, release ms, makeup dB, mix
    private val env = EnvelopeFollower(); private var thrDb = 0f; private var ratio = 4f; private var makeup = 1f; private var mix = 1f
    var gainReductionDb = 0f; private set
    override fun update() { thrDb = p[0]; ratio = p[1].coerceAtLeast(1f); env.set(sr, p[2], p[3]); makeup = Shapers.dbToLin(p[4]); mix = p.getOrElse(5) { 1f } }
    override fun process(buf: FloatArray, n: Int) { var gr = 0f
        for (i in 0 until n) { val x = buf[i]; val lvl = Shapers.linToDb(env.process(x)); val over = lvl - thrDb
            val red = if (over > 0) over * (1f - 1f / ratio) else 0f; if (red > gr) gr = red
            val y = x * Shapers.dbToLin(-red) * makeup; buf[i] = x * (1 - mix) + y * mix }
        gainReductionDb = gr }
}

class Limiter(sr: Float, p: FloatArray) : Processor(sr, p) { // ceiling dB, release ms, input dB
    private var g = 1f; private var ceil = 1f; private var rel = 0f; private var inG = 1f
    override fun update() { ceil = Shapers.dbToLin(p[0]); rel = exp(-1f / (sr * p[1] / 1000f)); inG = Shapers.dbToLin(p[2]) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val x = buf[i] * inG; val a = abs(x); val need = if (a * g > ceil) ceil / a else 1f
        g = if (need < g) need else need + rel * (g - need); buf[i] = (x * g).coerceIn(-ceil, ceil) } }
}

class TransientShaper(sr: Float, p: FloatArray) : Processor(sr, p) { // attack -1..1, sustain -1..1, output dB
    private val fast = EnvelopeFollower(); private val slow = EnvelopeFollower(); private var out = 1f
    override fun update() { fast.set(sr, 0.3f, 40f); slow.set(sr, 15f, 120f); out = Shapers.dbToLin(p[2]) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val x = buf[i]; val f = fast.process(x); val s = slow.process(x)
        val diff = (f - s).coerceAtLeast(0f) / (f + 1e-4f); val gain = 1f + p[0] * 2f * diff + p[1] * (1f - diff) * 0.8f
        buf[i] = x * gain.coerceIn(0.05f, 4f) * out } }
}

// ---------------- Drive family ----------------
class Booster(sr: Float, p: FloatArray) : Processor(sr, p) { // gain dB, tone
    private val tone = Biquad(); private var g = 1f
    override fun update() { g = Shapers.dbToLin(p[0]); tone.set(Biquad.Kind.HighShelf, sr, 2500f, 0.7f, (p[1] - 0.5f) * 12f) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) buf[i] = Shapers.soft(tone.process(buf[i]) * g * 0.9f) / 0.9f }
}

/** Mid-push overdrive: pre-emphasis high-pass, asymmetric soft clip, tone low-pass. */
class Overdrive(sr: Float, p: FloatArray, private val hard: Boolean = false, private val scoop: Boolean = false) : Processor(sr, p) { // drive, tone, level
    private val pre = Biquad(); private val mid = Biquad(); private val post = Biquad(); private val dc = OnePole(); private var drive = 1f; private var level = 1f
    override fun update() {
        drive = 1f + p[0] * p[0] * (if (hard) 220f else 60f)
        pre.set(Biquad.Kind.HighPass, sr, if (scoop) 120f else 650f, 0.6f)
        mid.set(Biquad.Kind.Peak, sr, if (scoop) 750f else 900f, 0.8f, if (scoop) -9f else 5f)
        post.set(Biquad.Kind.LowPass, sr, 1200f + p[1] * 7800f, 0.7f)
        level = Shapers.dbToLin((p[2] - 0.5f) * 24f) * (if (hard) 0.35f else 0.55f)
        dc.setLowPass(sr, 12f)
    }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val dry = buf[i]
        var x = pre.process(dry) * drive + dry * 0.3f
        x = if (hard) Shapers.hard(Shapers.soft(x * 1.5f) * 1.4f, 0.9f) else Shapers.asym(x, 0.18f)
        x = mid.process(x); x -= dc.lp(x); buf[i] = post.process(x) * level } }
}

class Fuzz(sr: Float, p: FloatArray) : Processor(sr, p) { // fuzz, tone, volume
    private val lp = Biquad(); private val hp = Biquad(); private var g = 1f; private var vol = 1f
    override fun update() { g = 4f + p[0] * 300f; lp.set(Biquad.Kind.LowPass, sr, 800f + p[1] * 6000f); hp.set(Biquad.Kind.HighPass, sr, 70f); vol = Shapers.dbToLin((p[2] - 0.5f) * 24f) * 0.4f }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) buf[i] = lp.process(Shapers.fuzz(hp.process(buf[i]) * g)) * vol }
}

class BitCrusher(sr: Float, p: FloatArray) : Processor(sr, p) { // bits, downsample, mix
    private var hold = 0f; private var cnt = 0f
    override fun process(buf: FloatArray, n: Int) { val levels = 2f.pow(p[0]); val step = 1f + p[1] * 30f
        for (i in 0 until n) { cnt += 1f; if (cnt >= step) { cnt -= step; hold = (buf[i] * levels).roundToInt() / levels }; buf[i] = buf[i] * (1 - p[2]) + hold * p[2] } }
}

class TapeSaturator(sr: Float, p: FloatArray) : Processor(sr, p) { // drive, warmth, output dB
    private val lp = Biquad(); private val low = Biquad(); private var drv = 1f; private var out = 1f
    override fun update() { drv = 1f + p[0] * 6f; lp.set(Biquad.Kind.LowPass, sr, 16000f - p[1] * 9000f); low.set(Biquad.Kind.LowShelf, sr, 110f, 0.7f, p[1] * 3f); out = Shapers.dbToLin(p[2]) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) buf[i] = lp.process(low.process(Shapers.asym(buf[i] * drv, 0.08f) / sqrt(drv))) * out }
}

// ---------------- Filters / EQ ----------------
class Wah(sr: Float, p: FloatArray) : Processor(sr, p) { // pedal 0..1, Q, auto amount, mix
    private val bp = Biquad(); private val env = EnvelopeFollower(); private var cnt = 0
    override fun update() { env.set(sr, 3f, 120f) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val e = env.process(buf[i])
        if (cnt++ % 16 == 0) { val pos = (p[0] + p[2] * e * 6f).coerceIn(0f, 1f); bp.set(Biquad.Kind.BandPass, sr, 350f * 2f.pow(pos * 3.3f), 1.5f + p[1] * 8f) }
        val w = bp.process(buf[i]) * 3.2f; buf[i] = buf[i] * (1 - p[3]) + w * p[3] } }
}

class EnvelopeFilter(sr: Float, p: FloatArray) : Processor(sr, p) { // sensitivity, base freq, Q
    private val f = Biquad(); private val env = EnvelopeFollower(); private var cnt = 0
    override fun update() { env.set(sr, 2f, 90f) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val e = env.process(buf[i])
        if (cnt++ % 16 == 0) f.set(Biquad.Kind.LowPass, sr, (200f + p[1] * 1200f) * (1f + p[0] * e * 40f), 1f + p[2] * 9f); buf[i] = f.process(buf[i]) } }
}

class GraphicEq(sr: Float, p: FloatArray, private val freqs: FloatArray) : Processor(sr, p) { // one gain dB per band, last = output dB
    private val bands = Array(freqs.size) { Biquad() }; private var out = 1f
    override fun update() { for (b in freqs.indices) bands[b].set(Biquad.Kind.Peak, sr, freqs[b], 1.1f, p[b]); out = Shapers.dbToLin(p[freqs.size]) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { var x = buf[i]; for (b in bands) x = b.process(x); buf[i] = x * out } }
}

class ParametricEq(sr: Float, p: FloatArray) : Processor(sr, p) { // HPF Hz, low dB, lowmid Hz, lowmid dB, himid Hz, himid dB, high dB
    private val hp = Biquad(); private val lo = Biquad(); private val lm = Biquad(); private val hm = Biquad(); private val hi = Biquad()
    override fun update() { hp.set(Biquad.Kind.HighPass, sr, p[0]); lo.set(Biquad.Kind.LowShelf, sr, 100f, 0.7f, p[1]); lm.set(Biquad.Kind.Peak, sr, p[2], 1f, p[3]); hm.set(Biquad.Kind.Peak, sr, p[4], 1f, p[5]); hi.set(Biquad.Kind.HighShelf, sr, 8000f, 0.7f, p[6]) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) buf[i] = hi.process(hm.process(lm.process(lo.process(hp.process(buf[i]))))) }
}

// ---------------- Modulation ----------------
class Chorus(sr: Float, p: FloatArray, private val flanger: Boolean = false) : Processor(sr, p) { // rate Hz, depth, mix, feedback
    private val d = DelayLine((sr * 0.05f).toInt()); private val lfo = Lfo(); private var fb = 0f
    override fun process(buf: FloatArray, n: Int) { val base = if (flanger) sr * 0.0025f else sr * 0.012f; val depth = (if (flanger) sr * 0.002f else sr * 0.006f) * p[1]
        for (i in 0 until n) { val m = lfo.next(sr, p[0]); val w = d.read(base + depth * (m + 1f) * 0.5f + 1f); d.write(buf[i] + w * p[3] * if (flanger) 0.9f else 0.2f); buf[i] = buf[i] * (1 - p[2] * 0.5f) + w * p[2] } }
}

class Phaser(sr: Float, p: FloatArray) : Processor(sr, p) { // rate, depth, feedback, mix
    private val z = FloatArray(6); private val lfo = Lfo(); private var last = 0f
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val m = (lfo.next(sr, p[0]) + 1f) * 0.5f * p[1]
        val f = 200f * 2f.pow(m * 4f); val t = tan(PI.toFloat() * f / sr); val a = (t - 1f) / (t + 1f)
        var x = buf[i] + last * p[2] * 0.85f
        for (s in z.indices) { val y = a * x + z[s]; z[s] = x - a * y; x = y }
        last = x; buf[i] = buf[i] * (1 - p[3] * 0.5f) + x * p[3] * 0.8f } }
}

class Tremolo(sr: Float, p: FloatArray) : Processor(sr, p) { // rate, depth, shape(0 sine..1 square)
    private val lfo = Lfo()
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { var m = lfo.next(sr, p[0]); if (p[2] > 0f) m = m * (1 - p[2]) + sign(m) * p[2]; buf[i] *= 1f - p[1] * (0.5f + 0.5f * m) } }
}

/** Rotating-speaker model: amplitude + Doppler modulation of horn and drum bands with slow/fast inertia. */
class Rotary(sr: Float, p: FloatArray) : Processor(sr, p) { // speed (0 slow, 1 fast), drive, mix
    private val split = Biquad(); private val d = DelayLine((sr * 0.01f).toInt()); private var hornHz = 0.8f; private var drumHz = 0.7f; private val lH = Lfo(); private val lD = Lfo()
    override fun process(buf: FloatArray, n: Int) { val tgtH = if (p[0] > 0.5f) 6.7f else 0.8f; val tgtD = if (p[0] > 0.5f) 5.8f else 0.7f
        split.set(Biquad.Kind.LowPass, sr, 800f)
        for (i in 0 until n) { hornHz += (tgtH - hornHz) * 0.00004f; drumHz += (tgtD - drumHz) * 0.00002f
            val x = Shapers.soft(buf[i] * (1f + p[1] * 4f)) / (1f + p[1]); val lo = split.process(x); val hi = x - lo
            val mh = lH.next(sr, hornHz); val md = lD.next(sr, drumHz)
            d.write(hi); val horn = d.read(sr * 0.0015f * (1.2f + mh)) * (0.75f + 0.25f * mh)
            val drum = lo * (0.85f + 0.15f * md); buf[i] = buf[i] * (1 - p[2]) + (horn + drum) * p[2] } }
}

// ---------------- Pitch ----------------
class Octaver(sr: Float, p: FloatArray) : Processor(sr, p) { // sub level, dry level, tone
    private var flip = 1f; private var prev = 0f; private val lp = Biquad(); private val env = EnvelopeFollower()
    override fun update() { lp.set(Biquad.Kind.LowPass, sr, 200f + p[2] * 1500f); env.set(sr, 2f, 60f) }
    override fun process(buf: FloatArray, n: Int) { for (i in 0 until n) { val x = buf[i]; val e = env.process(x); if (prev < 0f && x >= 0f) flip = -flip; prev = x
        buf[i] = x * p[1] + lp.process(flip * e) * p[0] * 1.5f } }
}

/** Two-tap crossfaded delay-line pitch shifter. */
class PitchShifter(sr: Float, p: FloatArray) : Processor(sr, p) { // semitones -12..12, mix, detune cents
    private val d = DelayLine((sr * 0.1f).toInt()); private var ph = 0f
    override fun process(buf: FloatArray, n: Int) { val ratio = 2f.pow((p[0] + p[2] / 100f) / 12f); val win = sr * 0.05f; val inc = (1f - ratio) / win
        for (i in 0 until n) { d.write(buf[i]); ph += inc; if (ph >= 1f) ph -= 1f; if (ph < 0f) ph += 1f
            val p2 = (ph + 0.5f) % 1f; val a = d.read(ph * win + 2f); val b = d.read(p2 * win + 2f)
            val ga = sin(PI.toFloat() * ph); val gb = sin(PI.toFloat() * p2); val w = a * ga + b * gb
            buf[i] = buf[i] * (1 - p[1]) + w * p[1] } }
}

// ---------------- Time ----------------
class Delay(sr: Float, p: FloatArray, private val tape: Boolean = false) : Processor(sr, p) { // time ms, feedback, mix, tone
    private val d = DelayLine((sr * 2.1f).toInt()); private val lp = Biquad(); private val lfo = Lfo(); private var smooth = -1f
    override fun update() { lp.set(Biquad.Kind.LowPass, sr, 1500f + p[3] * 12000f) }
    override fun process(buf: FloatArray, n: Int) { val target = p[0] / 1000f * sr; if (smooth < 0) smooth = target
        for (i in 0 until n) { smooth += (target - smooth) * 0.0005f; val wob = if (tape) lfo.next(sr, 0.6f) * sr * 0.0008f else 0f
            var w = lp.process(d.read(smooth + wob)); if (tape) w = Shapers.soft(w * 1.2f) / 1.2f
            d.write(buf[i] + w * p[1].coerceAtMost(0.95f)); buf[i] = buf[i] + w * p[2] } }
}

/** Freeverb-style reverb (8 combs, 4 allpasses); [spring] adds a chirpy dispersion stage. */
class Reverb(sr: Float, p: FloatArray, private val spring: Boolean = false) : Processor(sr, p) { // size, damping, mix, pre-delay ms
    private val scale = sr / 44100f
    private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { DelayLine((it * scale).toInt() + 2) to (it * scale) }
    private val combStore = FloatArray(8)
    private val aps = intArrayOf(556, 441, 341, 225).map { DelayLine((it * scale).toInt() + 2) to (it * scale) }
    private val pre = DelayLine((sr * 0.25f).toInt()); private val disp = Array(4) { DelayLine(64) }
    override fun process(buf: FloatArray, n: Int) { val fb = 0.7f + p[0] * 0.28f; val damp = p[1] * 0.6f
        for (i in 0 until n) { pre.write(buf[i]); var x = pre.read(1f + p[3] / 1000f * sr) * 0.12f
            if (spring) for (dl in disp) { val y = dl.read(17f) * -0.6f + x; dl.write(x + y * 0.6f); x = y }
            var acc = 0f
            for ((k, c) in combs.withIndex()) { val y = c.first.read(c.second); combStore[k] = y * (1 - damp) + combStore[k] * damp; c.first.write(x + combStore[k] * fb); acc += y }
            for (a in aps) { val b = a.first.read(a.second); a.first.write(acc + b * 0.5f); acc = b - acc }
            buf[i] = buf[i] * (1 - p[2] * 0.5f) + acc * p[2] } }
}

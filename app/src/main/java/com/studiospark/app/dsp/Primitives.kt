package com.studiospark.app.dsp

import kotlin.math.*

/** RBJ biquad, transposed direct form II. Coefficients may be recomputed from any thread; state is touched only by the audio thread. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    enum class Kind { LowPass, HighPass, BandPass, Peak, LowShelf, HighShelf, Notch }

    fun set(kind: Kind, sr: Float, freq: Float, q: Float = 0.707f, gainDb: Float = 0f) {
        val f = freq.coerceIn(10f, sr * 0.49f).toDouble()
        val w0 = 2 * PI * f / sr
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2 * q.coerceAtLeast(0.05f))
        val a = 10.0.pow(gainDb / 40.0)
        var nb0: Double; var nb1: Double; var nb2: Double; var na0: Double; var na1: Double; var na2: Double
        when (kind) {
            Kind.LowPass -> { nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Kind.HighPass -> { nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Kind.BandPass -> { nb0 = alpha; nb1 = 0.0; nb2 = -alpha; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Kind.Notch -> { nb0 = 1.0; nb1 = -2 * cw; nb2 = 1.0; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            Kind.Peak -> { nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a; na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a }
            Kind.LowShelf -> {
                val s = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) - (a - 1) * cw + s); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - s)
                na0 = (a + 1) + (a - 1) * cw + s; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - s
            }
            Kind.HighShelf -> {
                val s = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) + (a - 1) * cw + s); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - s)
                na0 = (a + 1) - (a - 1) * cw + s; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - s
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        if (abs(z1) < 1e-20) z1 = 0.0
        if (abs(z2) < 1e-20) z2 = 0.0
        return y.toFloat()
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }
}

class OnePole {
    private var a = 0f; private var z = 0f
    fun setLowPass(sr: Float, freq: Float) { a = exp(-2f * PI.toFloat() * freq.coerceIn(5f, sr * 0.45f) / sr) }
    fun lp(x: Float): Float { z = x + a * (z - x); return z }
    fun hp(x: Float): Float = x - lp(x)
    fun reset() { z = 0f }
}

class DelayLine(maxSamples: Int) {
    private val buf = FloatArray(maxSamples.coerceAtLeast(4))
    private var w = 0
    val capacity get() = buf.size
    fun write(x: Float) { buf[w] = x; w++; if (w >= buf.size) w = 0 }
    /** Fractional read, [delay] samples behind the last written sample. */
    fun read(delay: Float): Float {
        val d = delay.coerceIn(1f, (buf.size - 2).toFloat())
        var pos = w - 1 - d
        while (pos < 0) pos += buf.size
        var i = pos.toInt(); val frac = pos - i; if (i >= buf.size) i -= buf.size
        val a = buf[i]; val b = buf[(i + 1) % buf.size]
        return a + (b - a) * frac
    }
    fun clear() { buf.fill(0f) }
}

class EnvelopeFollower {
    private var env = 0f; private var att = 0f; private var rel = 0f
    fun set(sr: Float, attackMs: Float, releaseMs: Float) {
        att = exp(-1f / (sr * attackMs.coerceAtLeast(0.05f) / 1000f)); rel = exp(-1f / (sr * releaseMs.coerceAtLeast(1f) / 1000f))
    }
    fun process(x: Float): Float { val a = abs(x); env = if (a > env) a + att * (env - a) else a + rel * (env - a); return env }
    val value get() = env
}

class Lfo { private var ph = 0.0
    fun next(sr: Float, hz: Float): Float { ph += hz / sr; if (ph >= 1.0) ph -= 1.0; return sin(2 * PI * ph).toFloat() }
    fun tri(sr: Float, hz: Float): Float { ph += hz / sr; if (ph >= 1.0) ph -= 1.0; return (if (ph < 0.5) 4 * ph - 1 else 3 - 4 * ph).toFloat() }
}

object Shapers {
    fun soft(x: Float): Float = tanh(x)
    fun asym(x: Float, bias: Float): Float = tanh(x + bias) - tanh(bias)
    fun hard(x: Float, t: Float = 1f): Float = x.coerceIn(-t, t)
    fun fuzz(x: Float): Float = sign(x) * (1f - exp(-abs(x * 3f)))
    fun dbToLin(db: Float): Float = 10f.pow(db / 20f)
    fun linToDb(l: Float): Float = 20f * log10(l.coerceAtLeast(1e-6f))
}

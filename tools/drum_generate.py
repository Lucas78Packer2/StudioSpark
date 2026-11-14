"""Original in-house drum/cymbal synthesis for the StudioSpark drum catalog (expansion set).
Pure NumPy equations + deterministic pseudo-random excitation. No input audio is read. Output PCM16 mono 44.1 kHz,
five velocity layers per sound (1 = softest .. 5 = hardest), same naming scheme as assets/original_drums."""
import numpy as np, wave, os, sys
SR = 44100
OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/original_drums"

def write(name, x):
    x = x / max(1e-9, np.max(np.abs(x))) * 0.89
    fade = int(0.01 * SR); x[-fade:] *= np.linspace(1, 0, fade)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype("<i2").tobytes())

def t(sec): return np.arange(int(sec * SR)) / SR
def shaped_noise(n, rng, lo, hi, tilt=0.0):
    X = np.fft.rfft(rng.standard_normal(n)); f = np.fft.rfftfreq(n, 1 / SR)
    band = 1 / (1 + (lo / np.maximum(f, 1)) ** 4) / (1 + (f / hi) ** 4) * (np.maximum(f, 20) / 1000) ** tilt
    return np.fft.irfft(X * band, n)
def partials(tt, rng, count, lo, hi, dmin, dmax, spread=1.0):
    y = np.zeros_like(tt)
    for _ in range(count):
        f = lo * (hi / lo) ** (rng.random() ** spread); d = dmin + (dmax - dmin) * rng.random()
        y += np.sin(2 * np.pi * f * tt + rng.random() * 6.28) * np.exp(-tt / d) * (0.4 + rng.random()) / np.sqrt(f / lo)
    return y

def cymbal(seed, v, dur, lo, hi, dmin, dmax, noise_hi, trash=0.0, count=140, bright=1.0):
    rng = np.random.default_rng(seed + v * 7); tt = t(dur); vel = v / 5
    y = partials(tt, rng, count, lo, hi * (0.7 + 0.3 * vel) * bright, dmin, dmax)
    nz = shaped_noise(len(tt), rng, 3000, noise_hi * (0.6 + 0.4 * vel)) * np.exp(-tt / (dmax * 0.35))
    y = y / np.max(np.abs(y)) + nz / np.max(np.abs(nz)) * (0.35 + 0.4 * vel)
    if trash > 0:  # china/stack: ring modulation + fast amplitude flutter
        y = y * (1 - trash + trash * np.sin(2 * np.pi * (437 + 90 * rng.random()) * tt)) * (1 + 0.25 * trash * np.sin(2 * np.pi * 23 * tt))
    attack = np.minimum(1, tt / 0.0015); y *= attack
    return y * (0.35 + 0.65 * vel)

def membrane(seed, v, dur, f0, drop, decay, wires=0.0, wire_decay=0.12, click=0.3, body2=1.6):
    rng = np.random.default_rng(seed + v * 11); tt = t(dur); vel = v / 5
    f = f0 * (1 + drop * vel * np.exp(-tt / 0.025)); ph = 2 * np.pi * np.cumsum(f) / SR
    y = np.sin(ph) * np.exp(-tt / decay) + 0.35 * np.sin(body2 * ph + 1) * np.exp(-tt / (decay * 0.6))
    y += click * vel * shaped_noise(len(tt), rng, 1500, 9000) * np.exp(-tt / 0.004)
    if wires: y += wires * (0.5 + 0.5 * vel) * shaped_noise(len(tt), rng, 2500, 11000) * np.exp(-tt / wire_decay)
    return y * (0.3 + 0.7 * vel)

def bell(seed, v, dur, f0, decay):
    rng = np.random.default_rng(seed + v); tt = t(dur); vel = v / 5
    y = sum(a * np.sin(2 * np.pi * f0 * r * tt) * np.exp(-tt / (decay / (1 + 0.6 * i))) for i, (r, a) in enumerate([(1, 1), (2.32, .6), (4.25, .45), (6.63, .3), (9.38, .18)]))
    y += 0.3 * vel * shaped_noise(len(tt), rng, 4000, 14000) * np.exp(-tt / 0.01)
    return y * (0.3 + 0.7 * vel)

def jingles(seed, v, dur):
    rng = np.random.default_rng(seed + v); tt = t(dur); vel = v / 5; y = np.zeros_like(tt)
    for k in range(10):
        off = int(abs(rng.normal(0.004, 0.006)) * SR); seg = tt[: len(tt) - off]
        y[off:] += partials(seg, rng, 6, 5000, 12000, 0.03, 0.12) * (0.5 + rng.random())
    y += 0.3 * membrane(seed, v, dur, 420, 0.1, 0.03, click=0.1)
    return y * (0.3 + 0.7 * vel)

def block(seed, v, dur, f1, f2):
    rng = np.random.default_rng(seed + v); tt = t(dur); vel = v / 5
    y = np.sin(2 * np.pi * f1 * tt) * np.exp(-tt / 0.045) + 0.6 * np.sin(2 * np.pi * f2 * tt) * np.exp(-tt / 0.02)
    y += 0.2 * vel * shaped_noise(len(tt), rng, 1500, 8000) * np.exp(-tt / 0.003)
    return y * (0.3 + 0.7 * vel)

SOUNDS = {
    "china-0":        lambda v: cymbal(101, v, 2.6, 300, 9000, 0.25, 1.6, 12000, trash=0.55, count=170),
    "splash-0":       lambda v: cymbal(102, v, 1.2, 700, 14000, 0.08, 0.55, 16000, count=110, bright=1.2),
    "crash_dark-0":   lambda v: cymbal(103, v, 3.2, 250, 7000, 0.4, 2.4, 9000, count=180, bright=0.8),
    "stack-0":        lambda v: cymbal(104, v, 0.8, 500, 10000, 0.04, 0.3, 13000, trash=0.8, count=120),
    "ride_bell-0":    lambda v: bell(105, v, 3.0, 1180, 1.6) + 0.25 * cymbal(105, v, 3.0, 400, 9000, 0.6, 2.8, 9000, count=60),
    "hat_pedal-0":    lambda v: cymbal(106, v, 0.25, 2500, 12000, 0.01, 0.05, 14000, count=60) + 0.4 * membrane(106, v, 0.25, 180, 0.0, 0.02, click=0.0),
    "piccolo_snare-0": lambda v: membrane(107, v, 0.9, 330, 0.35, 0.08, wires=0.9, wire_decay=0.11, click=0.5, body2=1.59),
    "kick_deep-0":    lambda v: membrane(108, v, 1.4, 46, 1.6, 0.42, click=0.25, body2=1.5),
    "tambourine-0":   lambda v: jingles(109, v, 0.7),
    "woodblock-0":    lambda v: block(110, v, 0.4, 920, 2480),
}
if __name__ == "__main__":
    for key, fn in SOUNDS.items():
        for v in range(1, 6): write(f"{key}-{v}", fn(v))
    print("wrote", len(SOUNDS) * 5, "files")

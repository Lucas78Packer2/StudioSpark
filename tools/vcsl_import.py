#!/usr/bin/env python3
"""Builds the drop-in recorded snare / hi-hat / cymbal layers from the CC0 Versilian Community Sample Library (VCSL).

Source: https://github.com/sgossner/VCSL at commit c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e (LICENSE = CC0 1.0 Universal).
Usage: tools/vcsl_import.py <dir with the downloaded VCSL wavs> <assets/original_drums> [dir with the original synthesized layers for level reference]
Processing only: stereo->mono average, onset trim (2 ms pre-roll), length cap with a 60 ms end fade, each layer level-matched to the
original synthesized layer it replaces (RMS of the first 120 ms), peak-limited to -1 dBFS. Layers 1..5 soft->loud.
Where VCSL has fewer than five dynamics, a real recording is reused at a lower gain (marked 'gain' in PROVENANCE.txt).
"""
import sys, wave, numpy as np, os

SR = 44100
# key -> (max seconds, [(file, gain), ... five layers soft -> loud])
MAP = {
    "snare-0":     (1.0, [("Snare4_HitSN_v2_rr1_Mid.wav", 1), ("Snare4_HitSN_v2_rr2_Mid.wav", 1), ("Snare4_HitSN_v4_rr1_Mid.wav", 1), ("Snare4_HitSN_v4_rr2_Mid.wav", 1), ("Snare4_HitSN_v5_rr1_Mid.wav", 1)]),
    "snare-1":     (1.1, [("Snare2_HitSN_v3_rr1_Mid.wav", 1), ("Snare2_HitSN_v5_rr1_Mid.wav", 1), ("Snare2_HitSN_v6_rr1_Mid.wav", 1), ("Snare2_HitSN_v7_rr1_Mid.wav", 1), ("Snare2_HitSN_v9_rr1_Mid.wav", 1)]),
    "rimshot-0":   (1.0, [("Snare4_rimshot_v2_rr1_Mid.wav", .7), ("Snare4_rimshot_v2_rr2_Mid.wav", 1), ("Snare4_rimshot_v4_rr1_Mid.wav", .85), ("Snare4_rimshot_v4_rr2_Mid.wav", .93), ("Snare4_rimshot_v4_rr1_Mid.wav", 1)]),
    "closed_hat-0":(0.6, [("HiHat_HitC_v1_rr1_Mid.wav", 1), ("HiHat_HitC_v2_rr1_Mid.wav", 1), ("HiHat_HitC_v3_rr1_Mid.wav", 1), ("HiHat_HitC_v4_rr1_Mid.wav", 1), ("HiHat_HitC_v4_rr2_Mid.wav", 1)]),
    "open_hat-0":  (1.6, [("HiHat_HitO_rr1_Mid.wav", .45), ("HiHat_HitO_rr2_Mid.wav", .6), ("HiHat_HitO_rr1_Mid.wav", .75), ("HiHat_HitO_rr2_Mid.wav", .88), ("HiHat_HitO_rr1_Mid.wav", 1)]),
    "hat_pedal-0": (0.6, [("HiHat_Close_rr1_Mid.wav", .5), ("HiHat_Close_rr2_Mid.wav", .65), ("HiHat_Close_rr1_Mid.wav", .8), ("HiHat_Close_rr2_Mid.wav", .9), ("HiHat_Close_rr1_Mid.wav", 1)]),
    "crash-0":     (3.0, [("susCymb1_hit_stick_pp1.wav", 1), ("susCymb1_hit_stick_mp1.wav", .8), ("susCymb1_hit_stick_mp1.wav", 1), ("susCymb1_hit_stick_f1.wav", .85), ("susCymb1_hit_stick_f1.wav", 1)]),
    "ride-0":      (2.5, [("susCymb2_hit_stick_pp1.wav", 1), ("susCymb2_hit_stick_mp1.wav", .8), ("susCymb2_hit_stick_mp1.wav", 1), ("susCymb2_hit_stick_mf1.wav", .85), ("susCymb2_hit_stick_mf1.wav", 1)]),
    "ride_bell-0": (2.5, [("susCymb1_hit_bell_pp1.wav", 1), ("susCymb1_hit_bell_mf1.wav", .8), ("susCymb1_hit_bell_mf1.wav", 1), ("susCymb1_hit_bell_fff1.wav", .85), ("susCymb1_hit_bell_fff1.wav", 1)]),
}

def read(path):
    w = wave.open(path); assert w.getsampwidth() == 2 and w.getframerate() == SR, path
    x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return x.reshape(-1, w.getnchannels()).mean(axis=1)

def onset_trim(x, max_s):
    thr = max(1e-4, np.abs(x).max() * 0.05); i = int(np.argmax(np.abs(x) > thr)); i = max(0, i - int(0.002 * SR))
    y = x[i:i + int(max_s * SR)].copy(); f = int(0.06 * SR)
    if len(y) > f: y[-f:] *= np.linspace(1, 0, f) ** 2
    return y

def rms120(x): return float(np.sqrt(np.mean(x[:int(0.12 * SR)] ** 2)) + 1e-12)

def write(path, x):
    w = wave.open(path, "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes((np.clip(x, -1, 1) * 32767).round().astype("<i2").tobytes()); w.close()

def main(src, dst, ref_dir=None):
    ref_dir = ref_dir or dst
    for key, (max_s, layers) in MAP.items():
        ys = [onset_trim(read(os.path.join(src, f)), max_s) * g for f, g in layers]
        # each layer matches the loudness of the original layer it replaces (same mix balance and velocity curve), peak <= -1 dBFS
        for i in range(5):
            rp = os.path.join(ref_dir, f"{key}-{i + 1}.wav")
            if os.path.exists(rp): ys[i] = ys[i] * (rms120(read_any(rp)) / rms120(ys[i]))
            pk = np.abs(ys[i]).max()
            if pk > 0.89: ys[i] *= 0.89 / pk
        for i in range(3, -1, -1):   # keep layers strictly soft -> loud (>= 1.5 dB apart)
            lim = rms120(ys[i + 1]) * 10 ** (-1.5 / 20)
            if rms120(ys[i]) > lim: ys[i] *= lim / rms120(ys[i])
        for i, y in enumerate(ys): write(os.path.join(dst, f"{key}-{i + 1}.wav"), y)
        print(key, [f"{20 * np.log10(rms120(y)):.1f}dB" for y in ys])

def read_any(path):
    w = wave.open(path); sr = w.getframerate(); ch = w.getnchannels()
    x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    x = x.reshape(-1, ch).mean(axis=1)
    if sr != SR: x = np.interp(np.arange(0, len(x) * SR / sr) * sr / SR, np.arange(len(x)), x)
    return x

if __name__ == "__main__": main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)

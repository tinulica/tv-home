#!/usr/bin/env python3
"""Makes TV Home's Cinema sounds (launcher/assets/sounds/cinema_*.ogg), all our own.

- cinema_in:   the lights go down on the home screen: a warm low pad swells with a breath of air.
- cinema_logo: the logo: a rising shimmer while its ring draws, a soft pop for the play sign, one
               plucked note per letter of "cinema" (an F major arpeggio, left to right), then a
               low boom, a warm F major (add 9) chord and three bells as the gradient blooms.
- cinema_out:  the lights come back on: air rising, bells falling, a warm chord settling.

The times match CinemaActivity's animation. Pure Python (no numpy): wavetables and a
Freeverb-style hall; ffmpeg turns the result into Ogg Vorbis. Run: python tools/cinema_sound.py
"""

import math
import random
import struct
import subprocess
import wave
from pathlib import Path

SR = 44100
OUT = Path(__file__).resolve().parent.parent / "launcher" / "assets" / "sounds"


def table(partials, size=4096):
    t = [0.0] * size
    for k, a in partials:
        for i in range(size):
            t[i] += a * math.sin(2 * math.pi * k * i / size)
    peak = max(abs(x) for x in t)
    return [x / peak for x in t]


SOFT = table([(1, 1.0), (2, 0.35), (3, 0.16), (4, 0.06)])        # a round, dark pad
WARM = table([(k, 1.0 / k ** 1.6) for k in range(1, 10)])      # a soft brass / string
PLUCK = table([(1, 1.0), (2, 0.32), (3, 0.1), (5, 0.03)])      # a gentle pluck
SINE = table([(1, 1.0)])


def cents(f, c):
    return f * 2 ** (c / 1200.0)


class Track:
    def __init__(self, seconds):
        self.n = int(seconds * SR)
        self.left = [0.0] * self.n
        self.right = [0.0] * self.n

    def voice(self, freq, start, attack, tau, sustain, release_at, release, amp, pan, wav, vibrato=0.0, glide=None):
        """Raised-cosine attack, exponential fall to `sustain`, cosine release; glide=(tau, from)."""
        size = len(wav)
        phase = random.random() * size
        gl = math.cos((pan + 1) * math.pi / 4)
        gr = math.sin((pan + 1) * math.pi / 4)
        for n in range(int(start * SR), min(self.n, int((release_at + release) * SR))):
            t = n / SR - start
            if t < attack:
                e = 0.5 - 0.5 * math.cos(math.pi * t / attack)
            else:
                e = sustain + (1 - sustain) * math.exp(-(t - attack) / tau)
            rt = n / SR - release_at
            if rt > 0:
                e *= 0.5 + 0.5 * math.cos(math.pi * min(1.0, rt / release))
            f = freq
            if glide is not None:
                f = freq + (glide[1] - freq) * math.exp(-t / glide[0])
            if vibrato:
                f *= 1 + vibrato * math.sin(2 * math.pi * 4.6 * t)
            phase += f * size / SR
            i = int(phase)
            frac = phase - i
            s = (wav[i % size] * (1 - frac) + wav[(i + 1) % size] * frac) * e * amp
            self.left[n] += s * gl
            self.right[n] += s * gr

    def air(self, start, peak_at, end, amp):
        """A hush of filtered noise rising to `peak_at`, gone by `end`."""
        lp = [0.0] * 4
        for n in range(int(start * SR), min(self.n, int(end * SR))):
            t = n / SR
            e = ((t - start) / (peak_at - start)) ** 2 if t < peak_at else max(0.0, 1 - (t - peak_at) / (end - peak_at))
            lp[0] += 0.05 * (random.uniform(-1, 1) - lp[0])
            lp[1] += 0.05 * (lp[0] - lp[1])
            lp[2] += 0.05 * (random.uniform(-1, 1) - lp[2])
            lp[3] += 0.05 * (lp[2] - lp[3])
            self.left[n] += lp[1] * e * amp
            self.right[n] += lp[3] * e * amp

    def boom(self, at, amp=0.85):
        self.voice(36.0, at, 0.012, 0.9, 0.0, at + 3.0, 0.8, amp, 0.0, SINE, glide=(0.45, 56.0))
        self.voice(62.0, at, 0.008, 0.22, 0.0, at + 1.0, 0.3, amp * 0.35, 0.0, SINE, glide=(0.12, 95.0))

    def write(self, name, wet=0.42, fade=0.5):
        wet_l = reverb(self.left, 0)
        wet_r = reverb(self.right, 23)
        mix = [[d * 0.8 + w * wet for d, w in zip(self.left, wet_l)],
               [d * 0.8 + w * wet for d, w in zip(self.right, wet_r)]]
        for ch in mix:
            for n in range(self.n):
                ch[n] = math.tanh(ch[n] * 1.2)
                tail = (self.n - n) / (fade * SR)
                if tail < 1:
                    ch[n] *= tail
        peak = max(max(abs(v) for v in ch) for ch in mix)
        gain = 0.89 / peak
        OUT.mkdir(parents=True, exist_ok=True)
        wav_path = OUT / (name + ".wav")
        with wave.open(str(wav_path), "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SR)
            frames = bytearray()
            for a, b in zip(mix[0], mix[1]):
                frames += struct.pack("<hh", int(a * gain * 32767), int(b * gain * 32767))
            w.writeframes(bytes(frames))
        ogg = OUT / (name + ".ogg")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav_path), "-c:a", "libvorbis", "-q:a", "6",
                        str(ogg)], check=True)
        wav_path.unlink()
        print("Wrote %s (%.1f s)" % (ogg, self.n / SR))


def reverb(x, spread):
    """Freeverb's combs and all-passes: a large, soft hall."""
    out = [0.0] * len(x)
    feedback, damp = 0.86, 0.25
    for d in (1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116):
        d += spread
        buf = [0.0] * d
        store = 0.0
        i = 0
        for n in range(len(x)):
            y = buf[i]
            store = y * (1 - damp) + store * damp
            buf[i] = x[n] * 0.015 + store * feedback
            out[n] += y
            i = (i + 1) % d
    for d in (556, 441, 341, 225):
        d += spread
        buf = [0.0] * d
        i = 0
        for n in range(len(out)):
            b = buf[i]
            y = -out[n] + b
            buf[i] = out[n] + b * 0.5
            out[n] = y
            i = (i + 1) % d
    return out


def lights_down():
    t = Track(2.2)
    for f, amp in ((87.31, 0.16), (130.81, 0.12), (174.61, 0.09)):
        for d, pan in ((-7, -0.5), (0, 0.0), (7, 0.5)):
            t.voice(cents(f, d), 0.0, 1.3, 2.5, 0.8, 1.35, 0.75, amp, pan, SOFT, vibrato=0.0015)
    t.air(0.0, 1.2, 2.0, 0.5)
    t.write("cinema_in", fade=0.4)


def logo():
    t = Track(6.2)
    # The ring drawing itself: a soft shimmer rising, gone as the letters begin.
    for d, pan in ((-9, -0.4), (9, 0.4)):
        t.voice(cents(880.0, d), 0.0, 0.7, 0.5, 0.9, 0.8, 0.25, 0.05, pan, SINE, glide=(0.35, 330.0))
    t.air(0.0, 0.75, 1.05, 0.35)
    # The play sign popping in.
    t.voice(140.0, 0.55, 0.004, 0.09, 0.0, 0.8, 0.1, 0.35, 0.0, SINE, glide=(0.05, 230.0))
    # One note per letter, left to right: F4 A4 C5 F5 A5 C6.
    for i, f in enumerate((349.23, 440.00, 523.25, 698.46, 880.00, 1046.50)):
        at = 0.95 + 0.085 * i
        t.voice(f, at, 0.004, 0.32, 0.0, at + 1.4, 0.4, 0.12, -0.5 + 0.2 * i, PLUCK)
    # The gradient blooming: boom, chord, bells, with the pad underneath.
    bloom = 1.55
    t.boom(bloom)
    for f, amp in ((87.31, 0.12), (130.81, 0.09)):
        for d, pan in ((-7, -0.5), (7, 0.5)):
            t.voice(cents(f, d), bloom - 0.15, 0.6, 2.0, 0.7, 4.6, 1.4, amp, pan, SOFT)
    for f, amp, pan in ((174.61, 0.075, -0.2), (261.63, 0.07, 0.25), (349.23, 0.06, -0.35),
                        (440.00, 0.055, 0.4), (783.99, 0.035, 0.0)):
        for d in (-6, 0, 6):
            t.voice(cents(f, d), bloom + 0.03, 0.3, 1.6, 0.55, 4.6, 1.4, amp, pan + d / 30.0, WARM, vibrato=0.002)
    for i, (f, pan) in enumerate(((1046.50, -0.6), (1396.91, 0.0), (1760.00, 0.6))):
        at = bloom + 0.35 + 0.2 * i
        t.voice(f, at, 0.006, 1.3, 0.0, at + 3.5, 0.5, 0.05, pan, SINE)
        t.voice(f * 2.76, at, 0.004, 0.35, 0.0, at + 1.5, 0.3, 0.012, pan, SINE)
    t.write("cinema_logo", fade=0.6)


def lights_up():
    t = Track(3.0)
    t.air(0.0, 0.8, 1.4, 0.45)
    for d, pan in ((-8, -0.4), (8, 0.4)):
        t.voice(cents(660.0, d), 0.0, 0.7, 0.4, 0.8, 0.8, 0.3, 0.045, pan, SINE, glide=(0.4, 220.0))
    # Bells falling: C6 A5 F5 C5.
    for i, f in enumerate((1046.50, 880.00, 698.46, 523.25)):
        at = 0.7 + 0.1 * i
        t.voice(f, at, 0.004, 0.5, 0.0, at + 1.6, 0.4, 0.09, 0.45 - 0.3 * i, PLUCK)
    # A warm chord settling: F3 A3 C4 F4.
    for f, amp, pan in ((174.61, 0.08, -0.3), (220.00, 0.07, 0.3), (261.63, 0.065, -0.1), (349.23, 0.05, 0.2)):
        for d in (-6, 6):
            t.voice(cents(f, d), 1.1, 0.25, 0.8, 0.35, 2.2, 0.7, amp, pan, WARM)
    t.write("cinema_out", fade=0.5)


if __name__ == "__main__":
    random.seed(7)
    lights_down()
    logo()
    lights_up()

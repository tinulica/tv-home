#!/usr/bin/env python3
"""Makes the sound of TV Home's "hello" when the TV comes on (launcher/assets/sounds/hello.ogg).

In step with HelloIntro (3.8 s):
- 0.0-1.0 s: a hush of air as the light comes in from the left, a sub bass swelling under it;
- 0.85 s: "hell" in focus: a soft glass tone;
- 1.05 s: the "o" sliding out from behind the "l": a quick rising shimmer;
- 1.28 s: the "o" in place: a deep bass drop, a warm D major chord with the ninth, glass bells;
- 1.75-2.5 s: the gleam passing over the word; then everything rings out as the picture fades.

Run: python tools/hello_sound.py   (uses the synth in cinema_sound.py)
"""

import random

from cinema_sound import PLUCK, SINE, SOFT, WARM, Track, cents


def hello():
    t = Track(4.6)
    # The light coming in, and the bass swelling under it.
    t.air(0.0, 0.9, 1.4, 0.28)
    t.voice(41.2, 0.0, 0.9, 1.4, 0.5, 1.3, 0.25, 0.42, 0.0, SINE, glide=(0.5, 30.0))   # E1, rising from below
    # "hell" in focus.
    t.voice(587.33, 0.85, 0.01, 0.6, 0.0, 2.0, 0.4, 0.07, -0.3, PLUCK)
    t.voice(587.33 * 2.76, 0.85, 0.004, 0.2, 0.0, 1.2, 0.2, 0.01, -0.3, SINE)
    # The "o" sliding out.
    for d, pan in ((-6, 0.2), (6, 0.5)):
        t.voice(cents(1760.0, d), 1.05, 0.12, 0.2, 0.3, 1.25, 0.12, 0.03, pan, SINE, glide=(0.1, 880.0))
    t.air(1.02, 1.22, 1.32, 0.12)
    # The "o" in place.
    hit = 1.28
    t.boom(hit, amp=0.95)
    t.voice(73.42, hit, 0.01, 1.3, 0.35, hit + 2.2, 0.7, 0.3, 0.0, SOFT)                # D2, the body of the bass
    for f, amp in ((146.83, 0.1), (220.00, 0.08)):
        for d, pan in ((-7, -0.5), (7, 0.5)):
            t.voice(cents(f, d), hit - 0.08, 0.22, 1.8, 0.6, hit + 2.3, 0.8, amp, pan, SOFT)
    for f, amp, pan in ((293.66, 0.06, -0.25), (369.99, 0.055, 0.3), (440.00, 0.05, -0.4), (659.25, 0.04, 0.4)):
        for d in (-6, 0, 6):
            t.voice(cents(f, d), hit + 0.02, 0.18, 1.6, 0.5, hit + 2.3, 0.8, amp, pan + d / 30.0, WARM, vibrato=0.002)
    for i, (f, pan) in enumerate(((1174.66, -0.5), (1760.00, 0.0), (2349.32, 0.5))):
        at = hit + 0.1 + 0.16 * i
        t.voice(f, at, 0.005, 1.0, 0.0, at + 2.0, 0.4, 0.035, pan, SINE)
        t.voice(f * 2.76, at, 0.003, 0.25, 0.0, at + 0.8, 0.2, 0.009, pan, SINE)
    # The gleam passing over the word.
    for d, pan in ((-8, -0.5), (8, 0.5)):
        t.voice(cents(2349.32, d), 1.75, 0.35, 0.5, 0.4, 2.4, 0.4, 0.02, pan, SINE, glide=(0.3, 1174.66))
    t.write("hello", wet=0.4, fade=0.7)


if __name__ == "__main__":
    random.seed(7)
    hello()

#!/usr/bin/env python3
"""Makes TV Home's notice sounds (launcher/assets/sounds/notice.ogg, alert.ogg), our own.

- notice: a soft glass "clink", like a glass touched with a spoon: two bright partials and an
          inharmonic one, a quick tap after it, a little room.
- alert:  two bell notes (G6 then C7), clearer but still quiet.

Run: python tools/notice_sound.py   (uses the synth in cinema_sound.py)
"""

import random

from cinema_sound import SINE, Track


def notice():
    t = Track(1.0)
    for at, gain in ((0.0, 1.0), (0.065, 0.45)):
        t.voice(2637.0, at, 0.002, 0.22, 0.0, at + 0.8, 0.15, 0.32 * gain, -0.15, SINE)
        t.voice(3951.0, at, 0.002, 0.15, 0.0, at + 0.6, 0.15, 0.16 * gain, 0.15, SINE)
        t.voice(5274.0 * 1.013, at, 0.001, 0.08, 0.0, at + 0.4, 0.1, 0.07 * gain, 0.0, SINE)
    t.write("notice", wet=0.25, fade=0.25)


def alert():
    t = Track(1.5)
    for at, f, pan in ((0.0, 1567.98, -0.2), (0.14, 2093.0, 0.2)):
        t.voice(f, at, 0.003, 0.45, 0.0, at + 1.1, 0.25, 0.3, pan, SINE)
        t.voice(f * 2.0, at, 0.002, 0.25, 0.0, at + 0.8, 0.2, 0.1, pan, SINE)
        t.voice(f * 2.76, at, 0.002, 0.12, 0.0, at + 0.5, 0.15, 0.05, pan, SINE)
    t.write("alert", wet=0.3, fade=0.3)


if __name__ == "__main__":
    random.seed(11)
    notice()
    alert()

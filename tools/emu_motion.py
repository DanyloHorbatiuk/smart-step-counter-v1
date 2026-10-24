#!/usr/bin/env python3
"""Feeds synthetic motion into the Android emulator's accelerometer (Lab 6).

Connects to the emulator console (telnet protocol, localhost:5554 by default),
authenticates with ~/.emulator_console_auth_token and sends
"sensor set acceleration x:y:z" about 25 times per second.

The profiles match ml/generate_dataset.py: gravity on Z plus
  still  - sensor noise 0.05-0.3 m/s²
  walk   - 1.4-2.2 Hz, 1.5-4 m/s² + harmonics
  run    - 2.5-3.3 Hz, 5-12 m/s² + harmonics
  shake  - chaotic 8-12 Hz peaks, 6-15 m/s²
  demo   - still -> walk -> run -> shake, 8 s each, in a loop

Usage:
  python3 tools/emu_motion.py --mode demo
  python3 tools/emu_motion.py --mode walk --seconds 20
Only the standard library is needed.
"""

import argparse
import math
import random
import socket
import sys
import time
from pathlib import Path

GRAVITY = 9.80665
RATE_HZ = 25
DEMO_ORDER = ("still", "walk", "run", "shake")
DEMO_SECONDS = 8.0


class Console:
    """Minimal emulator console client: line commands, every reply ends with OK or KO."""

    def __init__(self, host: str, port: int):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.file = self.sock.makefile("rwb", buffering=0)
        self._read_reply()  # banner
        token_file = Path.home() / ".emulator_console_auth_token"
        if token_file.exists():
            self.command(f"auth {token_file.read_text().strip()}")

    def _read_reply(self) -> str:
        lines = []
        while True:
            line = self.file.readline()
            if not line:
                raise ConnectionError("emulator console closed the connection")
            text = line.decode(errors="replace").strip()
            if text == "OK":
                return "\n".join(lines)
            if text.startswith("KO"):
                raise RuntimeError(text)
            lines.append(text)

    def command(self, cmd: str) -> str:
        self.file.write((cmd + "\n").encode())
        return self._read_reply()

    def close(self):
        try:
            self.file.write(b"quit\n")
        except OSError:
            pass
        self.sock.close()


class Profile:
    """Dynamic acceleration s(t) of one mode; parameters are drawn once per mode."""

    def __init__(self, mode: str, rng: random.Random):
        self.mode = mode
        self.rng = rng
        self.phase = rng.uniform(0, 2 * math.pi)
        if mode == "walk":
            self.freq, self.amp = rng.uniform(1.4, 2.2), rng.uniform(1.5, 4.0)
        elif mode == "run":
            self.freq, self.amp = rng.uniform(2.5, 3.3), rng.uniform(5.0, 12.0)
        elif mode == "shake":
            self.amp = rng.uniform(6.0, 15.0)
            self.tones = [(rng.uniform(8.0, 12.0), rng.uniform(0, 2 * math.pi)) for _ in range(3)]
        else:
            self.sigma = rng.uniform(0.05, 0.3)
        self.h2 = rng.uniform(0.1, 0.4)

    def value(self, t: float) -> float:
        g = self.rng.gauss
        if self.mode in ("walk", "run"):
            p = self.phase + 2 * math.pi * self.freq * t
            noise = 0.3 if self.mode == "walk" else 0.6
            return self.amp * (math.sin(p) + self.h2 * math.sin(2 * p)) + g(0, noise)
        if self.mode == "shake":
            s = sum(math.sin(ph + 2 * math.pi * f * t) for f, ph in self.tones) / len(self.tones)
            s *= self.amp * self.rng.uniform(0.7, 1.4)
            if self.rng.random() < 0.15:  # sharp hits
                s += self.rng.choice((-1, 1)) * self.amp * self.rng.uniform(0.5, 1.0)
            return s + g(0, 0.5)
        return g(0, self.sigma)


def mode_at(mode: str, elapsed: float) -> str:
    if mode != "demo":
        return mode
    return DEMO_ORDER[int(elapsed // DEMO_SECONDS) % len(DEMO_ORDER)]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mode", choices=("still", "walk", "run", "shake", "demo"), default="demo")
    parser.add_argument("--seconds", type=float, default=None,
                        help="duration; default 32 s for demo (one cycle), 15 s otherwise")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=5554, help="emulator console port (adb devices: emulator-PORT)")
    parser.add_argument("--seed", type=int, default=None)
    args = parser.parse_args()
    seconds = args.seconds or (DEMO_SECONDS * len(DEMO_ORDER) if args.mode == "demo" else 15.0)

    try:
        console = Console(args.host, args.port)
    except OSError as e:
        print(f"Cannot connect to the emulator console {args.host}:{args.port}: {e}", file=sys.stderr)
        print("Start an emulator first (adb devices should list emulator-5554).", file=sys.stderr)
        return 1

    rng = random.Random(args.seed)
    profile, current = None, None
    start = time.monotonic()
    sent = 0
    try:
        while (elapsed := time.monotonic() - start) < seconds:
            mode = mode_at(args.mode, elapsed)
            if mode != current:
                current, profile = mode, Profile(mode, rng)
                print(f"[{elapsed:5.1f} s] mode = {mode}", flush=True)
            s = profile.value(elapsed)
            x, y = rng.gauss(0, 0.05), rng.gauss(0, 0.05)
            z = GRAVITY + s
            console.command(f"sensor set acceleration {x:.3f}:{y:.3f}:{z:.3f}")
            sent += 1
            # Absolute schedule: keeps ~25 Hz on average even if one command was slow.
            time.sleep(max(0.0, start + sent / RATE_HZ - time.monotonic()))
    except KeyboardInterrupt:
        pass
    finally:
        # Leave the phone lying still.
        try:
            console.command(f"sensor set acceleration 0:0:{GRAVITY}")
        except (OSError, RuntimeError):
            pass
        console.close()
    print(f"sent {sent} samples in {time.monotonic() - start:.1f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())

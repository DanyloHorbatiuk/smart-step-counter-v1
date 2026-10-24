"""Synthetic accelerometer dataset for the on-device activity classifier (Lab 6).

Every example is one 1 s window (50 samples at 50 Hz) of *dynamic* acceleration,
exactly what StepDetector.kt exposes as StepSnapshot.dynamic:

    |a|  ->  fast low-pass (tau 0.04 s)  ->  minus slow low-pass "gravity" (tau 0.8 s)

The raw |a| is simulated for 3 s and passed through a Python port of those filters;
only the last second is kept, so the gravity filter has settled the same way it has
on the phone. Train and inference therefore see the same signal.

Usage:  ml/.venv/bin/python ml/generate_dataset.py
Output: ml/data/dataset.npz  (x_train, y_train, x_val, y_val, x_test, y_test)
"""

import json
from pathlib import Path

import numpy as np

ML_DIR = Path(__file__).resolve().parent
CONFIG = json.loads((ML_DIR / "model_config.json").read_text())

FS = CONFIG["sample_rate_hz"]
WINDOW = CONFIG["window"]
LABELS = CONFIG["labels"]

GRAVITY = 9.80665
SMOOTH_TAU_S = 0.04  # Thresholds.SMOOTH_TAU_S
GRAVITY_TAU_S = 0.8  # Thresholds.GRAVITY_TAU_S

SIM_SECONDS = 3.0
PER_CLASS = 4000
SEED = 42

# Share of windows resampled with sample-and-hold at 25 Hz: tools/emu_motion.py feeds the
# emulator at ~25 Hz while the app listens at ~50 Hz, so each value arrives twice.
HOLD_25HZ_SHARE = 0.3


def step_detector_dynamic(magnitude: np.ndarray, dt: np.ndarray) -> np.ndarray:
    """Port of StepDetector.process(): returns smoothed - gravity for every sample."""
    out = np.empty_like(magnitude)
    smoothed = gravity = magnitude[0]
    out[0] = 0.0
    for i in range(1, len(magnitude)):
        d = min(max(dt[i], 0.0), 0.5)
        smoothed += d / (SMOOTH_TAU_S + d) * (magnitude[i] - smoothed)
        gravity += d / (GRAVITY_TAU_S + d) * (magnitude[i] - gravity)
        out[i] = smoothed - gravity
    return out


def jittered_time(rng, n: int):
    """SENSOR_DELAY_GAME is not exactly 50 Hz: +-10 % jitter between samples."""
    dt = (1.0 / FS) * rng.uniform(0.9, 1.1, n)
    dt[0] = 0.0
    return np.cumsum(dt), dt


def drifting_phase(rng, t: np.ndarray, freq: float) -> np.ndarray:
    """Phase of a periodic gait with slowly wandering cadence (people are not metronomes)."""
    f = freq * (1.0 + np.cumsum(rng.normal(0, 0.002, len(t))))
    dt = np.diff(t, prepend=t[0])
    return rng.uniform(0, 2 * np.pi) + 2 * np.pi * np.cumsum(f * dt)


def still(rng, t):
    sigma = rng.uniform(0.05, 0.3)
    drift = rng.uniform(-0.05, 0.05) * t
    return rng.normal(0, sigma, len(t)) + drift


def gait(rng, t, f_range, a_range, noise_range):
    phase = drifting_phase(rng, t, rng.uniform(*f_range))
    amp = rng.uniform(*a_range) * (1 + 0.1 * np.sin(2 * np.pi * rng.uniform(0.1, 0.5) * t))
    h2, h3 = rng.uniform(0.1, 0.4), rng.uniform(0.0, 0.15)
    s = amp * (np.sin(phase) + h2 * np.sin(2 * phase + rng.uniform(0, 2 * np.pi))
               + h3 * np.sin(3 * phase + rng.uniform(0, 2 * np.pi)))
    return s + rng.normal(0, rng.uniform(*noise_range), len(t)) + rng.uniform(-0.1, 0.1) * t


def walking(rng, t):
    return gait(rng, t, (1.4, 2.2), (1.5, 4.0), (0.1, 0.5))


def running(rng, t):
    return gait(rng, t, (2.5, 3.3), (5.0, 12.0), (0.3, 1.0))


def shake(rng, t):
    """Chaotic 8-12 Hz shaking: a few incoherent tones with random bursts and spikes."""
    amp = rng.uniform(6.0, 15.0)
    s = np.zeros_like(t)
    for _ in range(rng.integers(2, 5)):
        f = rng.uniform(8.0, 12.0)
        s += rng.uniform(0.3, 1.0) * np.sin(drifting_phase(rng, t, f))
    s *= amp / max(np.max(np.abs(s)), 1e-6)
    s *= rng.uniform(0.5, 1.0, len(t)) ** 0.5  # amplitude bursts
    spikes = rng.random(len(t)) < 0.08
    s[spikes] += rng.choice([-1, 1], spikes.sum()) * rng.uniform(0.5, 1.0, spikes.sum()) * amp
    return s + rng.normal(0, 0.5, len(t))


GENERATORS = {"STILL": still, "WALKING": walking, "RUNNING": running, "SHAKE": shake}


def make_window(rng, label: str) -> np.ndarray:
    n = int(SIM_SECONDS * FS)
    t, dt = jittered_time(rng, n)
    signal = GENERATORS[label](rng, t)
    if label != "STILL" and rng.random() < 0.2:
        # The activity started shortly before the window: still covers >= 70 % of it.
        onset = n - WINDOW + int(rng.uniform(0, 0.3) * WINDOW)
        signal[:onset] = rng.normal(0, rng.uniform(0.05, 0.3), onset)
    magnitude = np.abs(GRAVITY + signal)  # |a| is a norm, never negative
    if rng.random() < HOLD_25HZ_SHARE:
        magnitude[1::2] = magnitude[0::2][: len(magnitude[1::2])]
    return step_detector_dynamic(magnitude, dt)[-WINDOW:].astype(np.float32)


def main():
    rng = np.random.default_rng(SEED)
    xs, ys = [], []
    for idx, label in enumerate(LABELS):
        for _ in range(PER_CLASS):
            xs.append(make_window(rng, label))
            ys.append(idx)
    x = np.stack(xs)
    y = np.array(ys, dtype=np.int64)

    # Stratified 70/15/15 split with the same seed.
    train_idx, val_idx, test_idx = [], [], []
    for c in range(len(LABELS)):
        idx = rng.permutation(np.flatnonzero(y == c))
        n_train, n_val = int(0.7 * len(idx)), int(0.15 * len(idx))
        train_idx += list(idx[:n_train])
        val_idx += list(idx[n_train:n_train + n_val])
        test_idx += list(idx[n_train + n_val:])
    splits = {name: rng.permutation(ids) for name, ids in
              (("train", train_idx), ("val", val_idx), ("test", test_idx))}

    out = ML_DIR / "data" / "dataset.npz"
    out.parent.mkdir(exist_ok=True)
    np.savez_compressed(out, **{f"{k}_{s}": arr[ids] for s, ids in splits.items()
                                for k, arr in (("x", x), ("y", y))})
    sizes = ", ".join(f"{s}={len(ids)}" for s, ids in splits.items())
    print(f"saved {out.relative_to(ML_DIR.parent)}: {sizes}, window={WINDOW}, classes={LABELS}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate Aura's deterministic, original Ogg tone pack.

The output uses only synthesized waveforms. Run from the repository root:

    python tools/generate_aura_originals.py

FFmpeg must be available on PATH. The script is deterministic, so regenerated
resources have stable bytes for the same FFmpeg version.
"""

from __future__ import annotations

import math
import shutil
import struct
import subprocess
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path


SAMPLE_RATE = 22_050
OUTPUT_DIR = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "raw"


@dataclass(frozen=True)
class Tone:
    slug: str
    notes: tuple[tuple[float, float], ...]
    beat_seconds: float
    repeats: int
    voice: str
    gap_beats: float = 0.0
    echo_seconds: float = 0.16
    echo_mix: float = 0.18


def note(name: str) -> float:
    if name == "R":
        return 0.0
    semitones = {
        "C": 0,
        "C#": 1,
        "D": 2,
        "D#": 3,
        "E": 4,
        "F": 5,
        "F#": 6,
        "G": 7,
        "G#": 8,
        "A": 9,
        "A#": 10,
        "B": 11,
    }
    pitch = name[:-1]
    octave = int(name[-1])
    midi = 12 * (octave + 1) + semitones[pitch]
    return 440.0 * (2.0 ** ((midi - 69) / 12.0))


def sequence(text: str) -> tuple[tuple[float, float], ...]:
    result: list[tuple[float, float]] = []
    for token in text.split():
        pitch, beats = token.split(":", maxsplit=1)
        result.append((note(pitch), float(beats)))
    return tuple(result)


TONES = (
    Tone("aura_ringtone_crystal_chime", sequence("C5:1 E5:1 G5:1 C6:2 G5:1 E5:1 D5:1 G5:2 R:1"), 0.34, 3, "chime", 0.5, 0.24, 0.24),
    Tone("aura_ringtone_bright_orbit", sequence("A4:1 C#5:1 E5:1 B4:1 F#5:2 E5:1 C#5:1 B4:2 R:1"), 0.32, 3, "bright", 0.5, 0.13, 0.16),
    Tone("aura_ringtone_soft_marimba", sequence("F4:1 A4:1 C5:1 A4:1 G4:1 B4:1 D5:1 B4:1 F4:2 R:1"), 0.36, 3, "marimba", 0.5, 0.11, 0.12),
    Tone("aura_ringtone_digital_pulse", sequence("D4:0.5 A4:0.5 D5:1 R:0.5 F4:0.5 C5:0.5 F5:1 R:0.5 A4:0.5 E5:1.5 R:1"), 0.30, 4, "pulse", 0.5, 0.10, 0.14),
    Tone("aura_ringtone_garden_echo", sequence("G4:1 B4:1 D5:1 G5:1 D5:1 B4:1 A4:1 C5:1 E5:2 R:1"), 0.35, 3, "air", 0.5, 0.28, 0.22),
    Tone("aura_ringtone_glass_bells", sequence("E5:1 B5:1 G#5:1 E6:2 B5:1 F#5:1 C#6:1 B5:2 R:1"), 0.34, 3, "glass", 0.5, 0.22, 0.28),
    Tone("aura_ringtone_warm_arpeggio", sequence("C4:0.5 G4:0.5 C5:0.5 E5:0.5 G5:1 E5:0.5 C5:0.5 A3:0.5 E4:0.5 A4:0.5 C5:0.5 E5:1 R:1"), 0.29, 4, "warm", 0.5, 0.15, 0.16),
    Tone("aura_ringtone_music_box", sequence("D5:1 F#5:1 A5:1 D6:1 A5:1 F#5:1 E5:1 G5:1 B5:2 R:1"), 0.33, 3, "music_box", 0.5, 0.20, 0.22),
    Tone("aura_ringtone_mellow_air", sequence("A4:2 C5:1 E5:2 D5:1 B4:2 G4:1 A4:2 R:1"), 0.38, 3, "air", 0.5, 0.30, 0.20),
    Tone("aura_ringtone_xylophone_cascade", sequence("C5:0.5 D5:0.5 E5:0.5 G5:0.5 C6:1 G5:0.5 E5:0.5 D5:0.5 E5:0.5 G5:1 C6:1 R:1"), 0.28, 4, "xylophone", 0.5, 0.09, 0.12),
    Tone("aura_notification_soft_pop", sequence("C5:0.5 G5:0.7"), 0.55, 1, "soft_pop", 0.0, 0.08, 0.08),
    Tone("aura_notification_gentle_ding", sequence("E5:1"), 1.25, 1, "chime", 0.0, 0.18, 0.20),
    Tone("aura_notification_water_drop", sequence("C6:0.8"), 0.85, 1, "drop", 0.0, 0.10, 0.12),
    Tone("aura_notification_bubble_click", sequence("G5:0.6 C6:0.4"), 0.62, 1, "bubble", 0.0, 0.06, 0.08),
    Tone("aura_notification_bright_ping", sequence("A5:1"), 1.35, 1, "bright", 0.0, 0.15, 0.18),
    Tone("aura_notification_wooden_knock", sequence("C4:0.45 R:0.12 C4:0.45"), 0.62, 1, "wood", 0.0, 0.04, 0.04),
    Tone("aura_notification_chime_alert", sequence("C5:0.6 E5:0.6 G5:1"), 0.70, 1, "chime", 0.0, 0.13, 0.16),
    Tone("aura_notification_subtle_beep", sequence("E5:0.8"), 0.72, 1, "sine", 0.0, 0.04, 0.04),
    Tone("aura_notification_glass_tap", sequence("D6:0.7"), 0.88, 1, "glass", 0.0, 0.10, 0.14),
    Tone("aura_notification_echo_blip", sequence("G5:0.5 B5:0.5"), 0.78, 1, "pulse", 0.0, 0.20, 0.24),
    Tone("aura_alarm_sunrise_bells", sequence("C4:1 E4:1 G4:1 C5:2 E5:1 G5:2 R:1"), 0.42, 4, "chime", 0.5, 0.26, 0.24),
    Tone("aura_alarm_morning_chorus", sequence("G4:1 C5:0.5 E5:0.5 G5:1 E5:0.5 C5:0.5 A4:1 D5:0.5 F#5:0.5 A5:1 R:1"), 0.36, 4, "air", 0.5, 0.22, 0.18),
    Tone("aura_alarm_radar_pulse", sequence("C4:0.5 R:0.5 C5:0.5 R:0.5 G4:0.5 R:0.5 G5:0.75 R:0.75"), 0.40, 8, "pulse", 0.0, 0.30, 0.30),
    Tone("aura_alarm_ascending_chimes", sequence("C4:0.75 D4:0.75 E4:0.75 G4:0.75 A4:0.75 C5:0.75 E5:1.5 R:0.5"), 0.40, 5, "glass", 0.25, 0.18, 0.20),
    Tone("aura_alarm_classic_bells", sequence("E4:1 E4:1 R:0.5 E4:1 G4:1 C5:2 R:1"), 0.48, 6, "bell", 0.0, 0.20, 0.20),
)


def oscillator(voice: str, frequency: float, elapsed: float, duration: float) -> float:
    phase = math.tau * frequency * elapsed
    progress = elapsed / max(duration, 0.001)
    if voice == "chime":
        return math.sin(phase) + 0.33 * math.sin(phase * 2.01) + 0.16 * math.sin(phase * 3.98)
    if voice == "bright":
        return math.sin(phase) + 0.25 * math.sin(phase * 2.0) + 0.12 * math.sin(phase * 4.0)
    if voice == "marimba":
        return math.sin(phase) + 0.38 * math.sin(phase * 3.0) * math.exp(-7.0 * progress)
    if voice == "pulse":
        return math.sin(phase) + 0.20 * math.sin(phase * 2.0) + 0.08 * math.sin(phase * 5.0)
    if voice == "air":
        vibrato = 1.0 + 0.004 * math.sin(math.tau * 5.0 * elapsed)
        return math.sin(phase * vibrato) + 0.14 * math.sin(phase * 2.0)
    if voice == "glass":
        return math.sin(phase) + 0.28 * math.sin(phase * 2.72) + 0.13 * math.sin(phase * 4.15)
    if voice == "warm":
        return math.sin(phase) + 0.30 * math.sin(phase * 0.5) + 0.12 * math.sin(phase * 2.0)
    if voice == "music_box":
        return math.sin(phase) + 0.42 * math.sin(phase * 2.0) + 0.20 * math.sin(phase * 3.0)
    if voice == "xylophone":
        return math.sin(phase) + 0.30 * math.sin(phase * 3.0) + 0.08 * math.sin(phase * 5.0)
    if voice == "soft_pop":
        falling = frequency * (1.0 - 0.35 * progress)
        return math.sin(math.tau * falling * elapsed) * (1.0 - progress)
    if voice == "drop":
        falling = frequency * (1.0 - 0.55 * progress)
        return math.sin(math.tau * falling * elapsed) + 0.10 * math.sin(phase * 2.0)
    if voice == "bubble":
        rising = frequency * (1.0 + 0.45 * progress)
        return math.sin(math.tau * rising * elapsed) * (1.0 - 0.35 * progress)
    if voice == "wood":
        noise = math.sin(phase * 1.73) * math.sin(phase * 3.11)
        return 0.65 * math.sin(phase) + 0.35 * noise
    if voice == "bell":
        return math.sin(phase) + 0.45 * math.sin(phase * 2.4) + 0.22 * math.sin(phase * 3.7)
    return math.sin(phase)


def envelope(voice: str, elapsed: float, duration: float) -> float:
    attack = min(0.025, duration * 0.12)
    release = min(0.24, duration * 0.42)
    attack_gain = min(1.0, elapsed / max(attack, 0.001))
    release_gain = min(1.0, (duration - elapsed) / max(release, 0.001))
    decay = 1.0
    if voice in {"chime", "glass", "music_box", "xylophone", "bell", "marimba", "wood", "drop", "bubble", "soft_pop"}:
        decay = math.exp(-2.8 * elapsed / max(duration, 0.001))
    return max(0.0, attack_gain * release_gain * decay)


def render(tone: Tone) -> list[float]:
    samples: list[float] = []
    for _ in range(tone.repeats):
        for frequency, beats in tone.notes:
            duration = beats * tone.beat_seconds
            count = max(1, int(duration * SAMPLE_RATE))
            if frequency == 0.0:
                samples.extend([0.0] * count)
                continue
            for index in range(count):
                elapsed = index / SAMPLE_RATE
                samples.append(oscillator(tone.voice, frequency, elapsed, duration) * envelope(tone.voice, elapsed, duration))
        samples.extend([0.0] * int(tone.gap_beats * tone.beat_seconds * SAMPLE_RATE))

    delay = int(tone.echo_seconds * SAMPLE_RATE)
    if delay > 0 and tone.echo_mix > 0:
        dry = samples[:]
        for index in range(delay, len(samples)):
            samples[index] += dry[index - delay] * tone.echo_mix

    fade_samples = min(len(samples) // 4, int(0.12 * SAMPLE_RATE))
    for index in range(fade_samples):
        samples[-1 - index] *= index / max(1, fade_samples)
    peak = max((abs(value) for value in samples), default=1.0)
    scale = 0.72 / max(peak, 0.001)
    return [max(-1.0, min(1.0, value * scale)) for value in samples]


def write_wav(path: Path, samples: list[float]) -> None:
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for value in samples:
            frames.extend(struct.pack("<h", int(value * 32_767)))
        output.writeframes(frames)


def encode_ogg(wav_path: Path, output_path: Path) -> None:
    subprocess.run(
        [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(wav_path),
            "-map_metadata",
            "-1",
            "-fflags",
            "+bitexact",
            "-c:a",
            "libvorbis",
            "-flags:a",
            "+bitexact",
            "-q:a",
            "3",
            str(output_path),
        ],
        check=True,
    )


def main() -> None:
    if shutil.which("ffmpeg") is None:
        raise SystemExit("FFmpeg is required on PATH")
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    expected = {f"{tone.slug}.ogg" for tone in TONES}
    for old in OUTPUT_DIR.glob("aura_*.ogg"):
        if old.name not in expected:
            old.unlink()

    with tempfile.TemporaryDirectory(prefix="aura_originals_") as temp_dir:
        temp_root = Path(temp_dir)
        for tone in TONES:
            wav_path = temp_root / f"{tone.slug}.wav"
            output_path = OUTPUT_DIR / f"{tone.slug}.ogg"
            write_wav(wav_path, render(tone))
            encode_ogg(wav_path, output_path)
            print(f"{output_path.relative_to(OUTPUT_DIR.parent.parent.parent.parent.parent)}")


if __name__ == "__main__":
    main()

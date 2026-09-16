from pathlib import Path
import math
import struct
import wave

root = Path(__file__).resolve().parents[1]
raw_dir = root / "app/src/main/res/raw"
raw_dir.mkdir(parents=True, exist_ok=True)

SAMPLE_RATE = 22050


def write_wav(name: str, samples):
    path = raw_dir / name
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for sample in samples:
            sample = max(-1.0, min(1.0, sample))
            frames.extend(struct.pack("<h", int(sample * 32767)))
        w.writeframes(frames)
    print(f"generated {path.relative_to(root)}")


def pulse_tone():
    duration = 2.4
    out = []
    for i in range(int(SAMPLE_RATE * duration)):
        t = i / SAMPLE_RATE
        phase = t % 0.6
        if phase < 0.16 or 0.24 <= phase < 0.40:
            local = phase if phase < 0.16 else phase - 0.24
            env = min(1.0, local / 0.008, (0.16 - local) / 0.025)
            out.append(
                0.55 * env * math.sin(2 * math.pi * 880 * t)
                + 0.12 * env * math.sin(2 * math.pi * 1760 * t)
            )
        else:
            out.append(0.0)
    return out


def siren_tone():
    duration = 3.0
    out = []
    phase_acc = 0.0
    for i in range(int(SAMPLE_RATE * duration)):
        t = i / SAMPLE_RATE
        cycle = t % 1.0
        triangle = cycle * 2.0 if cycle < 0.5 else 2.0 * (1.0 - cycle)
        frequency = 620.0 + 560.0 * triangle
        phase_acc += 2.0 * math.pi * frequency / SAMPLE_RATE
        env = min(1.0, t / 0.03, (duration - t) / 0.05)
        out.append(env * (0.52 * math.sin(phase_acc) + 0.08 * math.sin(2.0 * phase_acc)))
    return out


def radar_tone():
    duration = 2.8
    out = []
    for i in range(int(SAMPLE_RATE * duration)):
        t = i / SAMPLE_RATE
        phase = t % 0.7
        if phase < 0.30:
            env = math.exp(-phase * 10.0)
            frequency = 1250.0 - 350.0 * min(1.0, phase / 0.30)
            out.append(0.65 * env * math.sin(2.0 * math.pi * frequency * t))
        else:
            out.append(0.0)
    return out


def urgent_tone():
    duration = 2.4
    out = []
    for i in range(int(SAMPLE_RATE * duration)):
        t = i / SAMPLE_RATE
        phase = t % 0.3
        if phase < 0.21:
            frequency = 980.0 if int(t / 0.3) % 2 == 0 else 1320.0
            env = min(1.0, phase / 0.006, (0.21 - phase) / 0.02)
            out.append(0.57 * env * math.sin(2.0 * math.pi * frequency * t))
        else:
            out.append(0.0)
    return out


write_wav("alarm_pulse.wav", pulse_tone())
write_wav("alarm_siren.wav", siren_tone())
write_wav("alarm_radar.wav", radar_tone())
write_wav("alarm_urgent.wav", urgent_tone())

"""Audio format helpers.

One rule runs through the whole voice server: inside it, audio is always
16 kHz, 16-bit, signed, little-endian, mono PCM. Every transport converts to
that on the way in and back out again on the way out, so nothing further in
has to ask what shape the bytes are.

Nothing here does any I/O, which is why it is the one module that can be
tested without a microphone, a network or a cloud account.
"""

import io
import wave

import numpy as np

SAMPLE_RATE = 16000
SAMPLE_WIDTH = 2  # bytes per sample, i.e. 16-bit
CHANNELS = 1

# How many bytes one millisecond of our internal format takes up.
BYTES_PER_MS = SAMPLE_RATE * SAMPLE_WIDTH // 1000


def pcm16_to_float32(data: bytes) -> np.ndarray:
    """Bytes to samples in the -1.0 .. 1.0 range."""
    return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0


def float32_to_pcm16(samples: np.ndarray) -> bytes:
    """Samples back to bytes, clipped so loud audio distorts instead of wrapping."""
    clipped = np.clip(samples, -1.0, 1.0)
    return (clipped * 32767.0).astype("<i2").tobytes()


def resample_pcm16(data: bytes, from_rate: int, to_rate: int) -> bytes:
    """Change the sample rate of PCM16 audio.

    Linear interpolation, which is not the most faithful method there is, but
    speech recognisers are unbothered by it and it costs nothing — the good
    resamplers all want a compiled dependency we would rather not add.
    """
    if from_rate == to_rate or not data:
        return data
    samples = pcm16_to_float32(data)
    target_length = max(1, int(round(len(samples) * to_rate / from_rate)))
    source_positions = np.linspace(0, len(samples) - 1, num=target_length)
    return float32_to_pcm16(np.interp(source_positions, np.arange(len(samples)), samples))


def wav_wrap(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> bytes:
    """Put a WAV header on raw PCM. Some recognisers will only take a file."""
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(CHANNELS)
        out.setsampwidth(SAMPLE_WIDTH)
        out.setframerate(sample_rate)
        out.writeframes(pcm)
    return buffer.getvalue()


def wav_unwrap(wav_bytes: bytes) -> tuple[bytes, int]:
    """Take a WAV apart into mono PCM16 and its sample rate."""
    with wave.open(io.BytesIO(wav_bytes), "rb") as source:
        channels = source.getnchannels()
        width = source.getsampwidth()
        rate = source.getframerate()
        frames = source.readframes(source.getnframes())

    if width != SAMPLE_WIDTH:
        raise ValueError(f"expected 16-bit audio, got {width * 8}-bit")
    if channels > 1:
        frames = _mix_to_mono(frames, channels)
    return frames, rate


def to_internal(data: bytes, from_rate: int, channels: int = 1) -> bytes:
    """Whatever came in, hand back 16 kHz mono PCM16."""
    if channels > 1:
        data = _mix_to_mono(data, channels)
    return resample_pcm16(data, from_rate, SAMPLE_RATE)


def duration_ms(pcm: bytes, sample_rate: int = SAMPLE_RATE) -> float:
    return len(pcm) / SAMPLE_WIDTH / sample_rate * 1000.0


# ------------------------------------------------------------------ mu-law --
#
# Telephone networks carry 8-bit mu-law rather than 16-bit PCM, so the Twilio
# transport in Phase 7 converts through here. The standard library used to do
# this in the audioop module, which was removed in Python 3.13 — these are the
# G.711 tables written out directly so the code works on any version.

_MU = 255
_MU_BIAS = 0x84
_MU_CLIP = 32635


def ulaw_encode(pcm: bytes) -> bytes:
    samples = np.frombuffer(pcm, dtype="<i2").astype(np.int32)
    sign = np.where(samples < 0, 0x80, 0)
    magnitude = np.minimum(np.abs(samples), _MU_CLIP) + _MU_BIAS

    # The exponent is which power-of-two band the magnitude falls in. frexp
    # gives that straight away: it returns e where magnitude = m * 2**e with
    # m in [0.5, 1), so the highest set bit sits at e - 1.
    highest_bit = np.frexp(magnitude.astype(np.float64))[1] - 1
    exponent = np.clip(highest_bit - 7, 0, 7).astype(np.int32)
    mantissa = (magnitude >> (exponent + 3)) & 0x0F

    encoded = ~(sign | (exponent << 4) | mantissa) & 0xFF
    return encoded.astype(np.uint8).tobytes()


def ulaw_decode(data: bytes) -> bytes:
    encoded = (~np.frombuffer(data, dtype=np.uint8).astype(np.int32)) & 0xFF
    sign = encoded & 0x80
    exponent = (encoded >> 4) & 0x07
    mantissa = encoded & 0x0F

    magnitude = (((mantissa << 3) + _MU_BIAS) << exponent) - _MU_BIAS
    samples = np.where(sign != 0, -magnitude, magnitude)
    return np.clip(samples, -32768, 32767).astype("<i2").tobytes()


def _mix_to_mono(data: bytes, channels: int) -> bytes:
    samples = np.frombuffer(data, dtype="<i2")
    usable = len(samples) - (len(samples) % channels)
    folded = samples[:usable].reshape(-1, channels).mean(axis=1)
    return np.clip(folded, -32768, 32767).astype("<i2").tobytes()

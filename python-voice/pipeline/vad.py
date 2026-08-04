"""Deciding when the caller has finished a sentence.

Speech arrives as a stream with no punctuation in it, so something has to say
"they have stopped talking, answer them now". That is this module. It cuts the
stream into 30 ms frames, asks whether each one contains speech, and closes an
utterance once enough silence has gone by.

The judgement itself comes from webrtcvad, which is small, fast and good at
telling speech from room noise. If it is not installed the class falls back to
a plain loudness threshold — worse, but it keeps a demo alive rather than
refusing to start.
"""

import logging

import numpy as np

from audio import SAMPLE_RATE, SAMPLE_WIDTH

log = logging.getLogger(__name__)

FRAME_MS = 30
SILENCE_MS = 600
# Speech shorter than this is a cough, a door, or a click — not a sentence.
MIN_UTTERANCE_MS = 250
# How loud a frame must be for the fallback detector to call it speech.
FALLBACK_RMS_THRESHOLD = 500


class Endpointer:
    """Feed it audio, get back whole utterances.

    push() returns a list of events, each one of:
        ("speech_start", None)     the caller began talking
        ("utterance", pcm_bytes)   a complete utterance, silence-trimmed
    """

    def __init__(self, sample_rate: int = SAMPLE_RATE, aggressiveness: int = 2,
                 silence_ms: int = SILENCE_MS):
        self.sample_rate = sample_rate
        self.silence_frames_needed = max(1, silence_ms // FRAME_MS)
        self.frame_bytes = int(sample_rate * FRAME_MS / 1000) * SAMPLE_WIDTH

        self._detector = _build_detector(aggressiveness, sample_rate)
        self._pending = b""          # audio not yet a whole frame
        self._voiced = []            # frames of the utterance being collected
        self._speech_frames = 0      # how many of those actually held speech
        self._silence_run = 0        # consecutive silent frames since speech
        self._in_speech = False

    def push(self, pcm: bytes) -> list[tuple[str, bytes | None]]:
        events = []
        self._pending += pcm
        while len(self._pending) >= self.frame_bytes:
            frame = self._pending[:self.frame_bytes]
            self._pending = self._pending[self.frame_bytes:]
            events.extend(self._consume_frame(frame))
        return events

    def flush(self) -> bytes | None:
        """Close whatever is in progress. Used when the call ends mid-sentence."""
        utterance = self._finish_utterance()
        self._pending = b""
        return utterance

    def reset(self) -> None:
        """Forget everything. Called after the agent speaks, so its own audio
        cannot be mistaken for the start of the caller's next sentence."""
        self._pending = b""
        self._voiced = []
        self._speech_frames = 0
        self._silence_run = 0
        self._in_speech = False

    # ---------------------------------------------------------------------- #

    def _consume_frame(self, frame: bytes) -> list[tuple[str, bytes | None]]:
        speaking = self._detector(frame)

        if speaking:
            events = [] if self._in_speech else [("speech_start", None)]
            self._in_speech = True
            self._silence_run = 0
            self._voiced.append(frame)
            self._speech_frames += 1
            return events

        if not self._in_speech:
            return []

        # Silence during speech is kept, because trailing silence is part of
        # how a recogniser knows a word ended.
        self._voiced.append(frame)
        self._silence_run += 1
        if self._silence_run < self.silence_frames_needed:
            return []

        utterance = self._finish_utterance()
        return [("utterance", utterance)] if utterance else []

    def _finish_utterance(self) -> bytes | None:
        if not self._voiced:
            self._in_speech = False
            return None
        utterance = b"".join(self._voiced)
        # Measured over the frames that held speech, not the whole recording:
        # most of an utterance is the silence that ended it, and counting that
        # would let a cough through as a sentence.
        speech_ms = self._speech_frames * FRAME_MS

        self._voiced = []
        self._speech_frames = 0
        self._silence_run = 0
        self._in_speech = False

        return utterance if speech_ms >= MIN_UTTERANCE_MS else None


def _build_detector(aggressiveness: int, sample_rate: int):
    """Returns a function that says whether one frame contains speech."""
    try:
        import webrtcvad
    except ImportError:
        log.warning("webrtcvad is not installed — falling back to a loudness threshold")
        return _loudness_detector

    if sample_rate not in (8000, 16000, 32000, 48000):
        log.warning("webrtcvad cannot handle %d Hz — falling back to loudness", sample_rate)
        return _loudness_detector

    vad = webrtcvad.Vad(aggressiveness)

    def detect(frame: bytes) -> bool:
        try:
            return vad.is_speech(frame, sample_rate)
        except Exception:
            return _loudness_detector(frame)

    return detect


def _loudness_detector(frame: bytes) -> bool:
    samples = np.frombuffer(frame, dtype="<i2").astype(np.float64)
    if samples.size == 0:
        return False
    return float(np.sqrt(np.mean(samples ** 2))) > FALLBACK_RMS_THRESHOLD

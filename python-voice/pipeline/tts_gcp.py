"""The agent's voice, through Google Cloud.

Asked for LINEAR16 at 16 kHz, which is exactly the format the rest of the
server uses, so the audio needs no conversion between here and the caller's
speakers.
"""

import logging
import os

from audio import SAMPLE_RATE, wav_unwrap
from config import VOICE_LOCALE
from pipeline.providers import TtsProvider

log = logging.getLogger(__name__)

# The panel stores speaking rate in words per minute, because that is what the
# offline voice understands. Google wants a multiplier instead, where 1.0 is
# its normal pace — which is about this many words per minute.
NORMAL_WORDS_PER_MINUTE = 170.0
MIN_SPEAKING_RATE = 0.25
MAX_SPEAKING_RATE = 4.0


def google_voice_name(wanted: str, language: str) -> str:
    """The chosen voice, if Google is the one that has it.

    The Settings menu lists every voice this installation could use, and that
    includes the machine's own. Sending one of those names to Google is not a
    slightly-wrong voice, it is an error Google refuses the whole request over —
    so the caller hears nothing rather than the wrong accent. An empty name is a
    valid request meaning "any voice for this language", which is exactly what
    an operator choosing "whichever the provider picks" asked for.
    """
    if not wanted:
        return ""
    if wanted.lower().startswith(language.lower() + "-"):
        return wanted

    log.info("'%s' is not a Google voice for %s, so Google is picking one instead",
             wanted, language)
    return ""


class GoogleTts(TtsProvider):

    name = "gcp"

    def __init__(self, config):
        from google.cloud import texttospeech

        os.environ.setdefault("GOOGLE_APPLICATION_CREDENTIALS", config.credentials_path)

        self._tts = texttospeech
        self._client = texttospeech.TextToSpeechClient()
        self._config = config

    def synthesize(self, text: str, language: str) -> bytes:
        if not text:
            return b""

        voice = self._tts.VoiceSelectionParams(
            language_code=VOICE_LOCALE.get(language, "en-US"),
            name=self._voice_name(language),
        )
        audio_config = self._tts.AudioConfig(
            audio_encoding=self._tts.AudioEncoding.LINEAR16,
            sample_rate_hertz=SAMPLE_RATE,
            speaking_rate=self._speaking_rate(),
            volume_gain_db=self._volume_gain_db(),
        )

        response = self._client.synthesize_speech(
            input=self._tts.SynthesisInput(text=text),
            voice=voice,
            audio_config=audio_config,
        )
        # Google returns LINEAR16 wrapped in a WAV container; the call only
        # wants the samples.
        pcm, _rate = wav_unwrap(response.audio_content)
        return pcm

    def _voice_name(self, language: str) -> str:
        return google_voice_name(self._config.voice_name(language), language)

    def _speaking_rate(self) -> float:
        rate = self._config.tts_rate / NORMAL_WORDS_PER_MINUTE
        return max(MIN_SPEAKING_RATE, min(MAX_SPEAKING_RATE, rate))

    def _volume_gain_db(self) -> float:
        """Google takes volume in decibels; the panel stores it as 0.0 to 1.0.

        Full volume is 0 dB — the reference level, not the maximum — and
        quieter settings go negative. Anything at or below silence is clamped
        to Google's floor rather than sent as minus infinity.
        """
        volume = max(0.0, min(1.0, self._config.tts_volume))
        if volume <= 0.01:
            return -96.0
        import math
        return max(-96.0, 20.0 * math.log10(volume))

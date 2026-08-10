"""The agent's voice with no account, kept from version 1.

pyttsx3 drives whatever speech engine the operating system has — SAPI5 on
Windows, espeak-ng in the Docker image. It sounds robotic next to a cloud
voice, and it needs no key, no network and no quota, which is what makes every
demo runnable.

Version 1 spoke straight out of the machine's speakers. Here the audio has to
travel down a websocket to whoever is on the call, so it is rendered to a file
and read back as samples instead.
"""

import logging
import os
import tempfile
import threading

from audio import SAMPLE_RATE, resample_pcm16, wav_unwrap
from pipeline import tts_windows, voices
from pipeline.providers import TtsProvider

log = logging.getLogger(__name__)

# pyttsx3 engines are not safe to share between threads, and re-using one is a
# known source of "it only speaks the first time". One engine per sentence,
# built under a lock, is slower and always works.
_ENGINE_LOCK = threading.Lock()


class OfflineTts(TtsProvider):

    name = "fallback"

    def __init__(self, config):
        import pyttsx3

        self._pyttsx3 = pyttsx3
        self._config = config
        # Languages this machine has no voice for, so the warning is said once
        # per call rather than once per sentence.
        self._warned = set()
        # Build one engine now purely to fail early: if no speech engine is
        # installed, the provider chooser should hear about it before a call
        # starts rather than halfway through one.
        self._pyttsx3.init().stop()

    def synthesize(self, text: str, language: str) -> bytes:
        if not text:
            return b""
        with _ENGINE_LOCK:
            voice_id = self._voice_for(language)
            # A voice Windows installed after the machine was built is in a
            # registry SAPI does not read, and pyttsx3 refuses an id it did not
            # enumerate. Those go through their own renderer; everything else
            # takes the ordinary path.
            if tts_windows.is_onecore(voice_id):
                spoken = tts_windows.render(text, voice_id, int(self._config.tts_rate),
                                            float(self._config.tts_volume))
                if spoken:
                    return spoken
                log.warning("Falling back to the default voice for %r", text[:40])
                voice_id = None
            return self._render(text, voice_id)

    def _voice_for(self, language: str) -> str | None:
        """Which installed voice should read this, if any suits.

        Handing Bengali text to an English voice does not produce accented
        Bangla — it produces the letters read as English, which is unusable.
        So the language decides the voice, an operator's own choice in Settings
        overrides that, and when the machine has no voice for the language at
        all the engine's default is used and the limitation is logged once.
        """
        installed = voices.available(self._config)
        chosen = voices.best_for(installed, language, self._config.voice_name(language))
        if chosen is None:
            if language not in self._warned:
                self._warned.add(language)
                log.warning("No offline voice on this machine speaks '%s' — it will be read "
                            "by the default voice. Add Google credentials for a real one.",
                            language)
            return None
        return chosen["id"]

    def _render(self, text: str, voice_id: str | None) -> bytes:
        path = None
        try:
            handle, path = tempfile.mkstemp(suffix=".wav")
            os.close(handle)

            engine = self._pyttsx3.init()
            engine.setProperty("rate", int(self._config.tts_rate))
            engine.setProperty("volume", float(self._config.tts_volume))
            if voice_id:
                engine.setProperty("voice", voice_id)
            engine.save_to_file(text, path)
            engine.runAndWait()
            engine.stop()

            with open(path, "rb") as rendered:
                data = rendered.read()
            if not data:
                log.warning("The offline voice produced no audio for %r", text[:40])
                return b""

            pcm, rate = wav_unwrap(data)
            return resample_pcm16(pcm, rate, SAMPLE_RATE)
        except Exception as e:
            log.warning("The offline voice failed: %s", e)
            return b""
        finally:
            if path and os.path.exists(path):
                os.unlink(path)

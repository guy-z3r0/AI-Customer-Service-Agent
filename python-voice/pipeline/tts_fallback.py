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
        # Build one engine now purely to fail early: if no speech engine is
        # installed, the provider chooser should hear about it before a call
        # starts rather than halfway through one.
        self._pyttsx3.init().stop()

    def synthesize(self, text: str, language: str) -> bytes:
        # language is ignored on purpose: the offline engines ship English
        # voices only. Bangla replies come out English-accented, which is a
        # documented limitation of running without Google credentials.
        if not text:
            return b""
        with _ENGINE_LOCK:
            return self._render(text)

    def _render(self, text: str) -> bytes:
        path = None
        try:
            handle, path = tempfile.mkstemp(suffix=".wav")
            os.close(handle)

            engine = self._pyttsx3.init()
            engine.setProperty("rate", int(self._config.tts_rate))
            engine.setProperty("volume", float(self._config.tts_volume))
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

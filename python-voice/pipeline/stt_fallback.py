"""Speech recognition with no account, kept from version 1.

SpeechRecognition's free Google Web endpoint takes one finished recording and
returns one answer. That means no interim results: the panel sees nothing until
the caller stops talking. It is slower and less accurate than the Cloud
recogniser, and it is what makes a demo possible on a machine with no
credentials at all, which is worth the trade.
"""

import logging
import threading
import time

from audio import SAMPLE_RATE
from config import SPEECH_LOCALE
from pipeline.providers import SttStream

log = logging.getLogger(__name__)


class FallbackSttStream(SttStream):

    name = "fallback"

    def __init__(self, config, language: str, on_partial, on_final):
        import asyncio

        import speech_recognition as sr

        self._sr = sr
        self._recognizer = sr.Recognizer()
        self._language = language
        self._on_final = on_final
        self._loop = asyncio.get_running_loop()

        # on_partial is part of the interface but this provider has nothing to
        # say until the recording is complete.
        self._buffer = bytearray()
        self._closed = False
        self._final_sent = False

    def push(self, pcm: bytes) -> None:
        if not self._closed:
            self._buffer.extend(pcm)

    def finish(self) -> None:
        audio = bytes(self._buffer)
        self._buffer.clear()
        threading.Thread(target=self._recognise, args=(audio,),
                         name="fallback-stt", daemon=True).start()

    def close(self) -> None:
        self._closed = True
        self._buffer.clear()

    # --------------------------------------------------------- worker thread --

    def _recognise(self, pcm: bytes) -> None:
        text = ""
        if pcm:
            try:
                audio = self._sr.AudioData(pcm, SAMPLE_RATE, 2)
                text = self._recognizer.recognize_google(
                    audio, language=SPEECH_LOCALE.get(self._language, "en-US"))
            except self._sr.UnknownValueError:
                log.info("Free recogniser could not make out the audio")
            except self._sr.RequestError as e:
                log.warning("Free recogniser is unreachable: %s", e)
            except Exception as e:
                log.warning("Free recogniser failed: %s", e)
        self._emit_final(text.strip())

    def _emit_final(self, text: str) -> None:
        if self._final_sent or self._loop.is_closed():
            return
        self._final_sent = True
        self._loop.call_soon_threadsafe(self._on_final, text, time.time())

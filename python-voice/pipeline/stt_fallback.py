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
from config import ALTERNATE_SPEECH_LOCALES, SPEECH_LOCALE
from pipeline.providers import SttStream

log = logging.getLogger(__name__)


def locales_for(language: str) -> list[str]:
    """The call's own language first, then the other one.

    The Cloud recogniser is handed both at once and picks; this endpoint takes
    exactly one, so the second language costs a second attempt on an utterance
    that has already failed. It is worth it: without it a caller on a Bangla
    call cannot be understood saying anything in English, including the sentence
    asking to switch to English, which leaves them with no way out.
    """
    primary = SPEECH_LOCALE.get(language, "en-US")
    return [primary] + [locale for locale in ALTERNATE_SPEECH_LOCALES.get(language, [])
                        if locale != primary]


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
        """Tries the call's language, then the other one.

        This endpoint takes exactly one language and has no equivalent of the
        Cloud recogniser's alternatives, so a caller on a Bangla call who says
        a sentence in English is simply not understood — including the sentence
        asking to switch to English, which leaves them stuck. A second attempt
        costs one more request on an utterance that already failed, and it is
        what makes changing language possible without a Google account.
        """
        text = ""
        if pcm:
            audio = self._sr.AudioData(pcm, SAMPLE_RATE, 2)
            for locale in locales_for(self._language):
                text = self._try(audio, locale)
                if text:
                    break
        self._emit_final(text.strip())

    def _try(self, audio, locale: str) -> str:
        try:
            return self._recognizer.recognize_google(audio, language=locale)
        except self._sr.UnknownValueError:
            log.info("Free recogniser made nothing of the audio in %s", locale)
        except self._sr.RequestError as e:
            log.warning("Free recogniser is unreachable: %s", e)
        except Exception as e:
            log.warning("Free recogniser failed: %s", e)
        return ""

    def _emit_final(self, text: str) -> None:
        if self._final_sent or self._loop.is_closed():
            return
        self._final_sent = True
        self._loop.call_soon_threadsafe(self._on_final, text, time.time())

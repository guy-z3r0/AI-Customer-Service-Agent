"""Speech recognition through Google Cloud.

Google's streaming recogniser wants to pull audio from a generator on its own
thread, which does not fit an asyncio websocket handler. So a stream lives on a
worker thread: audio goes in through a queue, results come back out by handing
them to the event loop with call_soon_threadsafe.

One Google stream covers one utterance. Starting a fresh stream per sentence
keeps the code simple and stays far inside Google's five-minute stream limit.
"""

import logging
import os
import queue
import threading
import time

from audio import SAMPLE_RATE
from config import ALTERNATE_SPEECH_LOCALES, SPEECH_LOCALE
from pipeline.providers import SttStream

log = logging.getLogger(__name__)

# Google's short-form model: tuned for utterances of a few seconds, which is
# what a phone call is made of.
MODEL = "latest_short"
STREAM_JOIN_TIMEOUT_S = 5
_END_OF_AUDIO = object()

# Asking Google to listen for a second language is what makes Banglish
# transcribe at all, but it is not accepted with every model in every region.
# The first refusal turns it off for the rest of the process rather than
# letting every utterance fail the same way; the caller repeats that one
# sentence and everything after it works.
_ALTERNATES_ALLOWED = True


class GoogleSttStream(SttStream):

    name = "gcp"

    def __init__(self, config, language: str, on_partial, on_final):
        import asyncio

        from google.cloud import speech

        os.environ.setdefault("GOOGLE_APPLICATION_CREDENTIALS", config.credentials_path)

        self._speech = speech
        self._client = speech.SpeechClient()
        self._language = language
        self._on_partial = on_partial
        self._on_final = on_final
        self._loop = asyncio.get_running_loop()

        self._audio = queue.Queue()
        self._finished = threading.Event()
        self._final_sent = False
        self._best_guess = ""

        self._worker = threading.Thread(target=self._run, name="gcp-stt", daemon=True)
        self._worker.start()

    def push(self, pcm: bytes) -> None:
        if not self._finished.is_set():
            self._audio.put(pcm)

    def finish(self) -> None:
        self._audio.put(_END_OF_AUDIO)

    def close(self) -> None:
        self._finished.set()
        self._audio.put(_END_OF_AUDIO)
        self._worker.join(timeout=STREAM_JOIN_TIMEOUT_S)

    # --------------------------------------------------------- worker thread --

    def _run(self) -> None:
        global _ALTERNATES_ALLOWED
        listening_for_both = _ALTERNATES_ALLOWED
        try:
            for response in self._client.streaming_recognize(
                    self._stream_config(listening_for_both), self._requests()):
                self._handle(response)
        except Exception as e:
            if listening_for_both and _refused_the_config(e):
                _ALTERNATES_ALLOWED = False
                log.warning("Google will not take a second language on %s here (%s) — "
                            "listening for one language from now on", MODEL, e)
            else:
                log.warning("Google speech stream ended early: %s", e)
        finally:
            # Whatever happened, the session is waiting for a final result. Send
            # the best guess we have, even if that is an empty string.
            self._emit_final(self._best_guess)

    def _stream_config(self, listening_for_both: bool):
        recognition = self._speech.RecognitionConfig(
            encoding=self._speech.RecognitionConfig.AudioEncoding.LINEAR16,
            sample_rate_hertz=SAMPLE_RATE,
            language_code=SPEECH_LOCALE.get(self._language, "en-US"),
            alternative_language_codes=(
                ALTERNATE_SPEECH_LOCALES.get(self._language, []) if listening_for_both else []),
            model=MODEL,
            enable_automatic_punctuation=True,
        )
        return self._speech.StreamingRecognitionConfig(config=recognition, interim_results=True)

    def _requests(self):
        while True:
            chunk = self._audio.get()
            if chunk is _END_OF_AUDIO:
                return
            yield self._speech.StreamingRecognizeRequest(audio_content=chunk)

    def _handle(self, response) -> None:
        for result in response.results:
            if not result.alternatives:
                continue
            text = result.alternatives[0].transcript.strip()
            if not text:
                continue
            self._best_guess = text
            if result.is_final:
                self._emit_final(text)
            else:
                self._call_back(self._on_partial, text)

    def _emit_final(self, text: str) -> None:
        if self._final_sent:
            return
        self._final_sent = True
        self._call_back(self._on_final, text, time.time())

    def _call_back(self, fn, *args) -> None:
        """Hop from this worker thread back onto the event loop."""
        if self._loop.is_closed():
            return
        self._loop.call_soon_threadsafe(fn, *args)


def _refused_the_config(error) -> bool:
    """True when Google rejected the request itself rather than the audio.

    Checked by name so this file does not have to import google.api_core just
    to recognise one exception type.
    """
    return type(error).__name__ in ("InvalidArgument", "BadRequest")

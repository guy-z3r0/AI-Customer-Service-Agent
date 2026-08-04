"""One call, from the first packet of audio to the last.

The session is where the pieces meet: audio comes in from a transport, the
endpointer says when a sentence has finished, the recogniser turns it into
text, and that text goes up to the Java brain. The brain answers a sentence at
a time, and each one is spoken the moment it arrives rather than when the whole
reply is finished — which is most of the difference between a reply that lands
inside two seconds and one that does not.

Three rules matter more than the rest:

  Half duplex. While the agent is speaking, incoming audio is thrown away. The
  caller's microphone can hear the agent's voice through their speakers, and
  without this the agent would answer itself.

  The agent finishes its sentence. A caller talking over the agent does not cut
  it off mid-word; the queue is only abandoned when the call ends.

  Sentences are spoken in the order they were written. They arrive faster than
  they can be said, so they wait in a queue rather than overlapping.
"""

import asyncio
import logging
import time

from audio import BYTES_PER_MS
from config import fetch, fetch_strings
from pipeline import providers
from pipeline.vad import Endpointer
import java_link

log = logging.getLogger(__name__)

# How much audio to send the caller at a time. Small enough that they hear the
# reply start almost immediately, big enough not to flood the socket.
CHUNK_MS = 100
CHUNK_BYTES = CHUNK_MS * BYTES_PER_MS

# Audio kept from just before speech was detected, so the recogniser is not
# handed a sentence with its first syllable missing.
PRE_ROLL_MS = 300
PRE_ROLL_BYTES = PRE_ROLL_MS * BYTES_PER_MS

# The only English in this file. Every word the agent says comes from
# utils/Lang.java over /api/lang — except this one, which is what it says when
# Java is the thing that cannot be reached.
FALLBACK_LINES = {
    "voice.link_lost": "Sorry, I have lost my connection. I have to end the call here.",
}


class VoiceSession:
    """Owns one call. The transport creates it, feeds it, and closes it."""

    def __init__(self, call_id: str, send_json, send_audio, telephony: str = "browser"):
        self.call_id = call_id
        self.telephony = telephony
        self._send_json = send_json
        self._send_audio = send_audio

        self.language = "en"
        self.config = None
        self.strings = {}
        self.stt_name = "none"
        self.tts_name = "none"

        self._endpointer = None
        self._tts = None
        self._stt = None
        self._link = None
        self._pre_roll = bytearray()
        self._streaming = False
        self._agent_speaking = False
        self._turn_seq = 0
        self._closed = False

        self._say_queue = asyncio.Queue()
        self._speaker = None
        self._reported = set()
        self._pending_hangup = None

    # ------------------------------------------------------------ lifecycle --

    async def start(self, language: str | None = None) -> None:
        """Load settings, pick a voice, and open the line to the brain."""
        self.config = await asyncio.to_thread(fetch)
        self.language = (language or self.config.default_language or "en").lower()
        self.strings = await asyncio.to_thread(fetch_strings, self.language)

        self._endpointer = Endpointer()
        self._tts = await asyncio.to_thread(providers.build_tts, self.config, self.language)
        self.tts_name = self._tts.name
        self._speaker = asyncio.create_task(self._speak_loop())

        self._link = java_link.TurnLink(self.call_id, self._on_brain_message, self._on_brain_lost)
        connected = await self._link.open({
            "type": "call_start",
            "telephony": self.telephony,
            "languageHint": self.language,
        })

        log.info("[%s] call ready — language=%s tts=%s brain=%s",
                 self.call_id, self.language, self.tts_name, connected)
        await self._send_json({"type": "ready", "language": self.language,
                               "tts": self.tts_name, "brain": connected})
        if not connected:
            await self._apologise_and_end("brain_unreachable")

    async def close(self, reason: str = "hangup") -> None:
        if self._closed:
            return
        self._closed = True

        self._release_stt()
        self._say_queue.put_nowait(None)  # wakes the speaker if it is waiting
        speaker = self._speaker
        if speaker is not None and speaker is not asyncio.current_task():
            speaker.cancel()  # and stops it mid-sentence if it is not

        if self._link is not None:
            await self._link.close(reason)
        log.info("[%s] call ended (%s)", self.call_id, reason)

    # ---------------------------------------------------------- audio in --

    async def on_audio(self, pcm: bytes) -> None:
        """Every chunk of caller audio arrives here, already 16 kHz PCM16."""
        if self._closed or not pcm:
            return
        if self._agent_speaking:
            # Half-duplex gate: this is very likely our own voice coming back.
            return

        try:
            events = self._endpointer.push(pcm)
        except Exception as e:
            log.warning("[%s] endpointer failed: %s", self.call_id, e)
            return

        self._feed_recogniser(pcm, events)

        for name, payload in events:
            if name == "utterance":
                self._end_utterance()

    def _feed_recogniser(self, pcm: bytes, events) -> None:
        """Send audio to the recogniser, opening a stream when speech starts."""
        if any(name == "speech_start" for name, _ in events):
            self._open_stt()

        if self._streaming:
            self._stt.push(pcm)
            return

        # Not talking yet: keep only the tail, as the run-up to the first word.
        self._pre_roll.extend(pcm)
        if len(self._pre_roll) > PRE_ROLL_BYTES:
            del self._pre_roll[:-PRE_ROLL_BYTES]

    def _open_stt(self) -> None:
        if self._streaming:
            return
        self._stt = providers.build_stt(self.config, self.language,
                                        self._on_partial, self._on_final)
        self.stt_name = self._stt.name
        self._streaming = True
        if self._pre_roll:
            self._stt.push(bytes(self._pre_roll))
            self._pre_roll.clear()

    def _end_utterance(self) -> None:
        if not self._streaming:
            return
        self._streaming = False
        self._stt.finish()

    def _release_stt(self) -> None:
        if self._stt is not None:
            try:
                self._stt.close()
            except Exception as e:
                log.debug("[%s] closing the recogniser raised %s", self.call_id, e)
            self._stt = None
        self._streaming = False
        self._pre_roll.clear()

    # ------------------------------------------------- recogniser callbacks --

    def _on_partial(self, text: str) -> None:
        """A guess at what is being said. Shown live, never stored."""
        if self._closed or not text:
            return
        asyncio.create_task(self._tell_brain({"type": "transcript_partial", "text": text}))

    def _on_final(self, text: str, t_stt_final: float) -> None:
        """The caller finished a sentence. The brain decides what follows."""
        self._release_stt()
        if self._closed:
            return
        if not text:
            # Still worth reporting: two of these in a row is the brain's cue to
            # ask the caller to say it again, which is what a recogniser
            # listening in one language does to a sentence in two.
            log.info("[%s] nothing recognised in that utterance", self.call_id)
            self._endpointer.reset()
            asyncio.create_task(self._tell_brain({
                "type": "transcript_final",
                "text": "",
                "language": self.language,
                "tSttFinal": _millis(t_stt_final),
            }))
            return

        self._turn_seq += 1
        log.info("[%s] turn %d — caller said %r", self.call_id, self._turn_seq, text[:80])
        asyncio.create_task(self._tell_brain({
            "type": "transcript_final",
            "text": text,
            "language": self.language,
            "tSttFinal": _millis(t_stt_final),
        }))

    # ----------------------------------------------------- the brain speaks --

    async def _on_brain_message(self, message: dict) -> None:
        kind = message.get("type")
        if kind == "say":
            await self._enqueue(int(message.get("seq") or 0), message.get("text"),
                                bool(message.get("last")), message.get("language"))
        elif kind == "greeting":
            # The brain says last=false when it is about to follow the greeting
            # with the language question. Anything else ends the agent's turn.
            await self._enqueue(0, message.get("text"), bool(message.get("last", True)),
                                message.get("language"))
        elif kind == "hangup":
            self._pending_hangup = message.get("reason") or "agent_hangup"
            farewell = message.get("farewellText")
            # Nothing left to say means nothing left to wait for either, and
            # waiting would only let the link try to rebuild itself first.
            if farewell:
                await self._enqueue(0, farewell, True, message.get("language"))
            else:
                await self.close(self._pending_hangup)
        elif kind == "set_language":
            await self._switch_language(message.get("language"))
        else:
            log.debug("[%s] ignoring a '%s' message from the brain", self.call_id, kind)

    async def _on_brain_lost(self) -> None:
        log.warning("[%s] the brain could not be reached again", self.call_id)
        await self._apologise_and_end("brain_unreachable")

    async def _apologise_and_end(self, reason: str) -> None:
        """Say why the call is ending rather than leaving the caller in silence."""
        self._pending_hangup = reason
        await self._enqueue(0, self._line("voice.link_lost"), True)

    async def _switch_language(self, language: str | None) -> None:
        wanted = (language or "en").lower()
        if wanted == self.language:
            return

        self.language = wanted
        self.strings = await asyncio.to_thread(fetch_strings, wanted)
        self._tts = await asyncio.to_thread(providers.build_tts, self.config, wanted)
        self.tts_name = self._tts.name
        self._release_stt()  # the next utterance opens a stream in the new language
        await self._send_json({"type": "ready", "language": self.language, "tts": self.tts_name})

    # ------------------------------------------------------------- speaking --

    async def _enqueue(self, seq: int, text: str | None, last: bool,
                       language: str | None = None) -> None:
        """
        :param language: which voice to say it in, when it is not the call's own.
            The greeting asks which language the caller wants, and asking that in
            one of them defeats the point, so each half arrives tagged.
        """
        if self._closed:
            return
        if not self._agent_speaking:
            self._agent_speaking = True
            await self._send_json({"type": "agent_state", "speaking": True})
        await self._say_queue.put({"seq": seq, "text": text or "", "last": last,
                                   "language": (language or self.language).lower()})

    async def _speak_loop(self) -> None:
        """Speaks queued sentences one after another for the life of the call."""
        while not self._closed:
            item = await self._say_queue.get()
            if item is None:
                return

            try:
                await self._speak_one(item)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("[%s] could not speak the reply: %s", self.call_id, e)
                await self._send_json({"type": "error", "code": "tts_failed", "msg": str(e)})

            if item["last"] and self._say_queue.empty():
                await self._end_of_turn()

    async def _speak_one(self, item: dict) -> None:
        text = item["text"].strip()
        if not text:
            return

        pcm = await asyncio.to_thread(self._tts.synthesize, text, item["language"])
        if not pcm:
            raise RuntimeError("the voice produced no audio")

        first_byte_at = 0.0
        for offset in range(0, len(pcm), CHUNK_BYTES):
            await self._send_audio(pcm[offset:offset + CHUNK_BYTES])
            if not first_byte_at:
                first_byte_at = time.time()
        await self._report_first_audio(item["seq"], first_byte_at)

    async def _report_first_audio(self, seq: int, when: float) -> None:
        """Closes the latency window the caller's last word opened, once a turn."""
        if seq <= 0 or not when or seq in self._reported:
            return
        self._reported.add(seq)
        await self._tell_brain({"type": "spoken", "seq": seq, "tTtsFirst": _millis(when)})

    async def _end_of_turn(self) -> None:
        self._agent_speaking = False
        await self._send_json({"type": "agent_state", "speaking": False})
        if self._endpointer is not None:
            self._endpointer.reset()
        if self._pending_hangup:
            await self.close(self._pending_hangup)

    # ------------------------------------------------------------ internals --

    async def _tell_brain(self, payload: dict) -> None:
        if self._link is not None:
            await self._link.send(payload)

    def _line(self, key: str) -> str:
        return self.strings.get(key) or FALLBACK_LINES.get(key, "")


def _millis(epoch_seconds: float) -> int:
    return int(epoch_seconds * 1000)

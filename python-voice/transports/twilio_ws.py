"""Calls arriving over a real telephone, through Twilio Media Streams.

Twilio opens one websocket per call and sends JSON for everything, including
the audio, which travels base64-encoded inside it:

    Twilio -> here   {"event": "connected"}
                     {"event": "start", "streamSid": …, "start": {"customParameters": {…}}}
                     {"event": "media", "media": {"payload": "<base64 mu-law>"}}
                     {"event": "stop"}
    here  -> Twilio  {"event": "media", "streamSid": …, "media": {"payload": …}}
                     {"event": "clear", "streamSid": …}

Two things differ from a browser call and nothing else does. The audio is
8 kHz mu-law rather than 16 kHz PCM, so it is converted at this edge and the
rest of the server never learns a telephone was involved. And the call id
arrives in the stream's custom parameters rather than in the path, because
Twilio chooses the URL from TwiML and the path is fixed.
"""

import base64
import binascii
import json
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from audio import SAMPLE_RATE, resample_pcm16, ulaw_decode, ulaw_encode
from session import VoiceSession
from transports import guard

log = logging.getLogger(__name__)

router = APIRouter()

# What a telephone line carries: 8 kHz, 8-bit mu-law, mono.
TELEPHONE_RATE = 8000

# Twilio wants roughly a fifth of a second of audio per message. Much smaller
# and the message overhead outweighs the audio; much larger and the caller
# hears the reply start late.
OUTBOUND_CHUNK_MS = 200
OUTBOUND_CHUNK_BYTES = TELEPHONE_RATE * OUTBOUND_CHUNK_MS // 1000

SESSIONS: dict[str, VoiceSession] = {}


@router.websocket("/ws/twilio")
async def twilio_call(websocket: WebSocket) -> None:
    await websocket.accept()
    stream = _Stream(websocket)
    reason = "hangup"

    try:
        await _run(websocket, stream)
    except WebSocketDisconnect:
        reason = "caller_disconnected"
        log.info("[%s] the telephone call dropped", stream.call_id or "twilio")
    except Exception as e:
        reason = "error"
        log.exception("[%s] telephone call failed: %s", stream.call_id or "twilio", e)
    finally:
        await stream.close(reason)


async def _run(websocket: WebSocket, stream: "_Stream") -> None:
    """Read the socket until Twilio stops sending or the call is over."""
    while True:
        message = await websocket.receive()
        if message["type"] == "websocket.disconnect":
            raise WebSocketDisconnect(message.get("code", 1000))

        text = message.get("text")
        if text is None:
            continue  # Media Streams is JSON only; binary would be a surprise

        event = _parse(text)
        if event is None:
            continue

        kind = event.get("event")
        if kind == "start":
            await stream.begin(event)
        elif kind == "media":
            await stream.on_media(event)
        elif kind == "stop":
            log.info("[%s] Twilio says the call is over", stream.call_id)
            return
        elif kind not in ("connected", "mark"):
            log.debug("Ignoring a '%s' event from Twilio", kind)


class _Stream:
    """One telephone call: the Twilio side of it, and the session behind it."""

    def __init__(self, websocket: WebSocket):
        self._websocket = websocket
        self._stream_sid = None
        self.call_id = None
        self._session = None

    async def begin(self, event: dict) -> None:
        """The start event names the call and opens the session behind it."""
        if self._session is not None:
            return

        start = event.get("start") or {}
        self._stream_sid = event.get("streamSid") or start.get("streamSid")
        parameters = start.get("customParameters") or {}
        self.call_id = parameters.get("callId")

        if not guard.call_id_is_sane(self.call_id):
            # Without a real id there is no call record to attach to, and an id
            # that is not a uuid is a string somebody chose — it goes into the
            # URL this server then dials on the backend.
            await guard.refuse(self._websocket, "no usable callId on the stream",
                               self.call_id or "")
            self.call_id = None
            return
        if self.call_id in SESSIONS:
            await guard.refuse(self._websocket, "that call already has a media stream",
                               self.call_id)
            self.call_id = None
            return

        self._session = VoiceSession(self.call_id,
                                     send_json=self._ignore_control,
                                     send_audio=self._send_audio,
                                     telephony="twilio")
        SESSIONS[self.call_id] = self._session
        log.info("[%s] telephone call connected (stream %s)", self.call_id, self._stream_sid)
        await self._session.start()

    async def on_media(self, event: dict) -> None:
        """One packet of the caller's voice, as base64 mu-law."""
        if self._session is None:
            return

        payload = (event.get("media") or {}).get("payload")
        if not payload:
            return

        try:
            ulaw = base64.b64decode(payload)
        except (binascii.Error, ValueError):
            log.debug("[%s] a media packet was not valid base64", self.call_id)
            return

        await self._session.on_audio(
            resample_pcm16(ulaw_decode(ulaw), TELEPHONE_RATE, SAMPLE_RATE))

    async def close(self, reason: str) -> None:
        if self._session is None:
            return
        if SESSIONS.get(self.call_id) is self._session:
            SESSIONS.pop(self.call_id, None)
        session, self._session = self._session, None
        await session.close(reason)

    # ------------------------------------------------------------ internals --

    async def _send_audio(self, pcm: bytes) -> None:
        """The agent's voice, back down the telephone line.

        The reply is cut into telephone-sized pieces here rather than passed on
        whole: Twilio plays what it is given in order, and a single large
        message would arrive as one lump after a pause.
        """
        if not self._stream_sid:
            return

        ulaw = ulaw_encode(resample_pcm16(pcm, SAMPLE_RATE, TELEPHONE_RATE))
        for offset in range(0, len(ulaw), OUTBOUND_CHUNK_BYTES):
            piece = ulaw[offset:offset + OUTBOUND_CHUNK_BYTES]
            await self._send({
                "event": "media",
                "streamSid": self._stream_sid,
                "media": {"payload": base64.b64encode(piece).decode("ascii")},
            })

    async def _ignore_control(self, payload: dict) -> None:
        """A telephone has no screen. The panel hears about the call from Java."""
        log.debug("[%s] not sending '%s' to a telephone", self.call_id, payload.get("type"))

    async def _send(self, payload: dict) -> None:
        try:
            await self._websocket.send_text(json.dumps(payload))
        except Exception as e:
            # The socket closing mid-reply is ordinary; the caller hung up.
            log.debug("[%s] could not send audio to Twilio: %s", self.call_id, e)


def _parse(text: str) -> dict | None:
    try:
        event = json.loads(text)
        return event if isinstance(event, dict) else None
    except json.JSONDecodeError:
        log.warning("Ignoring a Twilio frame that was not JSON: %r", text[:80])
        return None

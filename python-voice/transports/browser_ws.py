"""Calls placed straight from a browser tab.

The page opens one websocket and uses it for everything. Binary frames are
audio, 16 kHz mono PCM16 in both directions. Text frames are JSON control
messages:

    page  -> here   {"type": "start",  "language": "en"}
                    {"type": "end",    "reason": "hangup"}
    here  -> page   {"type": "ready",  "language": "en", "tts": "gcp"}
                    {"type": "agent_state", "speaking": true}
                    {"type": "error",  "code": "...", "msg": "..."}

No account, no phone number, nothing to configure — which is why this is the
default way to place a call and Twilio is the option.
"""

import json
import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from session import VoiceSession

log = logging.getLogger(__name__)

router = APIRouter()

# Live calls, so /health can say how many there are and shutdown can end them.
SESSIONS: dict[str, VoiceSession] = {}


@router.websocket("/ws/browser/{call_id}")
async def browser_call(websocket: WebSocket, call_id: str) -> None:
    await websocket.accept()
    session = VoiceSession(call_id,
                           send_json=_json_sender(websocket),
                           send_audio=_audio_sender(websocket))
    SESSIONS[call_id] = session
    reason = "hangup"

    try:
        await _run(websocket, session)
    except WebSocketDisconnect:
        reason = "caller_disconnected"
        log.info("[%s] the caller's browser went away", call_id)
    except Exception as e:
        reason = "error"
        log.exception("[%s] call failed: %s", call_id, e)
    finally:
        SESSIONS.pop(call_id, None)
        await session.close(reason)


async def _run(websocket: WebSocket, session: VoiceSession) -> None:
    """Read the socket until it closes or the page says the call is over."""
    started = False

    while True:
        message = await websocket.receive()

        if message["type"] == "websocket.disconnect":
            raise WebSocketDisconnect(message.get("code", 1000))

        if message.get("bytes") is not None:
            if started:
                await session.on_audio(message["bytes"])
            continue

        text = message.get("text")
        if text is None:
            continue

        control = _parse(text)
        if control is None:
            continue

        if control.get("type") == "start" and not started:
            await session.start(control.get("language"))
            started = True
        elif control.get("type") == "end":
            await session.close(control.get("reason", "hangup"))
            return


def _parse(text: str) -> dict | None:
    try:
        message = json.loads(text)
        return message if isinstance(message, dict) else None
    except json.JSONDecodeError:
        log.warning("Ignoring a control frame that was not JSON: %r", text[:80])
        return None


def _json_sender(websocket: WebSocket):
    async def send(payload: dict) -> None:
        try:
            await websocket.send_text(json.dumps(payload))
        except Exception as e:
            # The socket closing mid-reply is ordinary; the caller hung up.
            log.debug("Could not send a control frame: %s", e)

    return send


def _audio_sender(websocket: WebSocket):
    async def send(pcm: bytes) -> None:
        try:
            await websocket.send_bytes(pcm)
        except Exception as e:
            log.debug("Could not send audio: %s", e)

    return send

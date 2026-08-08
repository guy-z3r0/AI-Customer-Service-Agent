"""The line from one call to the Java brain.

Java holds the conversation: it knows the business, the history and the model.
This module is how a call reaches it — one websocket per call, at
/ws/turn/{callId}, carrying words in both directions and no audio at all.

    up    call_start, transcript_partial, transcript_final, spoken, call_end
    down  greeting, say, set_language, hangup

A link that breaks is dialled again, three times, because a caller is still on
the line and a blip is not a reason to hang up on them. When the third attempt
fails the session is told, and it apologises and ends the call rather than
leaving someone listening to silence.
"""

import asyncio
import json
import logging
import re

from config import JAVA_BASE_URL, java_auth_header

log = logging.getLogger(__name__)

TURN_WS_URL = re.sub(r"^http", "ws", JAVA_BASE_URL) + "/ws/turn/"

CONNECT_TIMEOUT_S = 5
ATTEMPTS = 3
BACKOFF_S = (0.5, 1.5)


class TurnLink:
    """One call's connection to the brain."""

    def __init__(self, call_id: str, on_message, on_lost):
        """
        :param on_message: async callable taking one decoded message dict
        :param on_lost:    async callable, run when the link cannot be recovered
        """
        self.call_id = call_id
        self._url = TURN_WS_URL + call_id
        self._on_message = on_message
        self._on_lost = on_lost

        self._socket = None
        self._reader = None
        self._start = None
        self._closed = False

    async def open(self, start: dict) -> bool:
        """Connect and announce the call. False if Java cannot be reached."""
        self._start = start
        if not await self._dial():
            return False

        await self.send(start)
        self._reader = asyncio.create_task(self._read_forever())
        return True

    async def send(self, payload: dict) -> None:
        socket = self._socket
        if socket is None or self._closed:
            return
        try:
            await socket.send(json.dumps(payload))
        except Exception as e:
            # Losing a message is the reader's problem to notice and fix.
            log.debug("[%s] could not send %s: %s", self.call_id, payload.get("type"), e)

    async def close(self, reason: str = "hangup") -> None:
        if self._closed:
            return
        await self.send({"type": "call_end", "reason": reason})
        self._closed = True

        reader = self._reader
        if reader is not None and reader is not asyncio.current_task():
            reader.cancel()
        if self._socket is not None:
            try:
                await self._socket.close()
            except Exception as e:
                log.debug("[%s] closing the link raised %s", self.call_id, e)
            self._socket = None

    # ------------------------------------------------------------ internals --

    async def _read_forever(self) -> None:
        """Deliver everything Java says, and rebuild the link when it drops."""
        while not self._closed:
            try:
                async for raw in self._socket:
                    await self._deliver(raw)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("[%s] the link to the brain broke: %s", self.call_id, e)

            if self._closed:
                return
            log.info("[%s] the brain's link closed — dialling again", self.call_id)
            if not await self._dial():
                await self._on_lost()
                return
            # Java treats a second call_start on a live call as a reconnection.
            await self.send(self._start)

    async def _deliver(self, raw) -> None:
        try:
            message = json.loads(raw)
        except (TypeError, ValueError):
            log.warning("[%s] the brain sent something that was not JSON", self.call_id)
            return
        if isinstance(message, dict):
            await self._on_message(message)

    async def _dial(self) -> bool:
        connect = _client()
        if connect is None:
            return False

        # The brain requires the operator login, and a websocket handshake is an
        # HTTP request like any other — so the credentials ride on it as a
        # header. Without them Java answers 401 and the call never connects.
        headers = java_auth_header()

        for attempt in range(1, ATTEMPTS + 1):
            try:
                self._socket = await connect(self._url, open_timeout=CONNECT_TIMEOUT_S,
                                             additional_headers=headers)
                log.info("[%s] connected to the brain", self.call_id)
                return True
            except Exception as e:
                log.warning("[%s] could not reach the brain (%d of %d): %s",
                            self.call_id, attempt, ATTEMPTS, e)
                if attempt < ATTEMPTS:
                    await asyncio.sleep(BACKOFF_S[attempt - 1])
        return False


def _client():
    """The websocket client, imported late so a missing library is survivable."""
    try:
        from websockets.asyncio.client import connect
        return connect
    except ImportError as e:
        log.error("The 'websockets' package is not installed, so calls cannot "
                  "reach the brain: %s", e)
        return None

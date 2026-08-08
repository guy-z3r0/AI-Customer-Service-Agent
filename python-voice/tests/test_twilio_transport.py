"""Tests for the telephone transport.

A Twilio call differs from a browser call in exactly two ways: the audio is
8 kHz mu-law wrapped in base64 JSON, and the call id arrives in the stream's
custom parameters rather than in the URL. Everything here tests that edge and
nothing past it — the session behind it is a stand-in, because the turn-taking
it does is already covered in test_voice_pipeline.py.

Run with:  cd python-voice && python -m pytest
"""

import asyncio
import base64
import json
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import audio  # noqa: E402
# ------------------------------------------------------- the telephone line --
#
# A Twilio call differs from a browser call in exactly two ways: the audio is
# 8 kHz mu-law wrapped in base64 JSON, and the call id arrives in the stream's
# custom parameters. Everything below tests that edge and nothing past it.

class FakeTwilioSocket:
    """Twilio's end of the websocket, writing down what it was sent."""

    def __init__(self):
        self.sent = []
        self.closed_with = None

    async def send_text(self, text: str) -> None:
        self.sent.append(json.loads(text))

    async def close(self, code: int = 1000) -> None:
        self.closed_with = code

    def media_payloads(self) -> list[bytes]:
        return [base64.b64decode(m["media"]["payload"])
                for m in self.sent if m.get("event") == "media"]


class FakeTwilioSession:
    """Stands in for a whole call, so only the transport is under test."""

    def __init__(self, call_id, send_json, send_audio, telephony="browser"):
        self.call_id = call_id
        self.telephony = telephony
        self.send_audio = send_audio
        self.heard = bytearray()
        self.started = False
        self.closed_with = None

    async def start(self, language=None):
        self.started = True

    async def on_audio(self, pcm: bytes) -> None:
        self.heard.extend(pcm)

    async def close(self, reason: str = "hangup") -> None:
        self.closed_with = reason


@pytest.fixture
def twilio(monkeypatch):
    from transports import twilio_ws

    monkeypatch.setattr(twilio_ws, "VoiceSession", FakeTwilioSession)
    twilio_ws.SESSIONS.clear()
    yield twilio_ws
    twilio_ws.SESSIONS.clear()


CALL_ID = "6f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b"


def start_event(call_id=CALL_ID):
    return {
        "event": "start",
        "streamSid": "MZ0000",
        "start": {"customParameters": {"callId": call_id} if call_id else {}},
    }


def test_the_call_id_comes_from_the_streams_custom_parameters(twilio):
    socket = FakeTwilioSocket()
    stream = twilio._Stream(socket)

    asyncio.run(stream.begin(start_event()))

    assert stream.call_id == CALL_ID
    assert stream._session.started
    assert stream._session.telephony == "twilio", "the brain must know it is a phone"
    assert CALL_ID in twilio.SESSIONS


def test_a_stream_with_no_call_id_is_refused_rather_than_guessed(twilio):
    socket = FakeTwilioSocket()
    stream = twilio._Stream(socket)

    asyncio.run(stream.begin(start_event(call_id=None)))

    assert stream._session is None
    assert socket.closed_with == 1008
    assert twilio.SESSIONS == {}


def test_a_call_id_that_is_not_a_uuid_is_refused(twilio):
    # The id is interpolated into the URL this server dials on the backend, so
    # a chosen string is a chosen destination. SECURITY-AUDIT SEC-009.
    socket = FakeTwilioSocket()
    stream = twilio._Stream(socket)

    asyncio.run(stream.begin(start_event("../../somewhere-else")))

    assert stream._session is None
    assert socket.closed_with == 1008
    assert twilio.SESSIONS == {}


def test_a_second_stream_cannot_take_over_a_live_call(twilio):
    first = twilio._Stream(FakeTwilioSocket())
    second_socket = FakeTwilioSocket()
    second = twilio._Stream(second_socket)

    async def scenario():
        await first.begin(start_event())
        await second.begin(start_event())

    asyncio.run(scenario())

    assert second._session is None
    assert second_socket.closed_with == 1008
    assert twilio.SESSIONS[CALL_ID] is first._session, "the live call is untouched"


def test_the_callers_voice_arrives_as_the_internal_format(twilio):
    socket = FakeTwilioSocket()
    stream = twilio._Stream(socket)
    # 160 mu-law bytes is 20 ms of telephone audio, one Twilio media packet.
    tone = (np.sin(np.linspace(0, 8 * np.pi, 160)) * 12000).astype("<i2").tobytes()
    packet = base64.b64encode(audio.ulaw_encode(
        audio.resample_pcm16(tone, audio.SAMPLE_RATE, 8000))).decode()

    async def scenario():
        await stream.begin(start_event())
        await stream.on_media({"event": "media", "media": {"payload": packet}})

    asyncio.run(scenario())

    heard = bytes(stream._session.heard)
    assert audio.duration_ms(heard) == pytest.approx(audio.duration_ms(tone), abs=1.0), \
        "20 ms down the telephone is still 20 ms once it is back at 16 kHz"


def test_rubbish_in_a_media_packet_does_not_end_the_call(twilio):
    stream = twilio._Stream(FakeTwilioSocket())

    async def scenario():
        await stream.begin(start_event())
        await stream.on_media({"event": "media", "media": {"payload": "not base64!!"}})
        await stream.on_media({"event": "media", "media": {}})

    asyncio.run(scenario())

    assert stream._session.heard == bytearray(), "nothing was fed on"
    assert stream._session.closed_with is None, "and the call is still up"


def test_the_reply_goes_back_as_telephone_sized_pieces(twilio):
    socket = FakeTwilioSocket()
    stream = twilio._Stream(socket)
    half_a_second = b"\x00\x00" * (audio.SAMPLE_RATE // 2)

    async def scenario():
        await stream.begin(start_event())
        await stream._send_audio(half_a_second)

    asyncio.run(scenario())

    pieces = socket.media_payloads()
    assert len(pieces) == 3, "500 ms at 200 ms a piece is three messages, not one lump"
    assert sum(len(p) for p in pieces) == 4000, "half a second of 8 kHz mu-law"
    assert all(m["streamSid"] == "MZ0000" for m in socket.sent)

"""Tests for the parts of the voice server that do not need a cloud account.

The recogniser, the voice and the Java brain are all replaced with stand-ins
here, so what is actually under test is the turn-taking: does silence end an
utterance, does the caller's sentence reach the brain, are the brain's
sentences spoken in order, is the microphone ignored while the agent is
speaking, and do the timestamps come out in an order that makes the latency
figure mean something.

Run with:  cd python-voice && python -m pytest
"""

import asyncio
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import audio  # noqa: E402
import session as session_module  # noqa: E402
from pipeline.vad import FRAME_MS, Endpointer  # noqa: E402

FRAME_BYTES = int(audio.SAMPLE_RATE * FRAME_MS / 1000) * audio.SAMPLE_WIDTH

REPLY = ["Hello there.", "How can I help?"]


def speech_frames(count: int) -> bytes:
    """Audio loud enough that both webrtcvad and the fallback call it speech."""
    samples = int(audio.SAMPLE_RATE * FRAME_MS / 1000) * count
    tone = np.sin(np.linspace(0, 2 * np.pi * 20 * count, samples)) * 12000
    return tone.astype("<i2").tobytes()


def silence_frames(count: int) -> bytes:
    return b"\x00\x00" * (int(audio.SAMPLE_RATE * FRAME_MS / 1000) * count)


# ------------------------------------------------------------------- audio --

def test_resampling_48k_to_16k_thirds_the_sample_count():
    one_second_at_48k = b"\x00\x00" * 48000
    resampled = audio.resample_pcm16(one_second_at_48k, 48000, 16000)
    assert len(resampled) // 2 == 16000


def test_wav_round_trip_keeps_the_samples():
    pcm = np.array([0, 500, -500, 32000], dtype="<i2").tobytes()
    back, rate = audio.wav_unwrap(audio.wav_wrap(pcm))
    assert back == pcm
    assert rate == audio.SAMPLE_RATE


def test_mu_law_survives_a_round_trip():
    tone = (np.sin(np.linspace(0, 40 * np.pi, 2000)) * 18000).astype("<i2").tobytes()
    decoded = audio.ulaw_decode(audio.ulaw_encode(tone))
    original = np.frombuffer(tone, "<i2").astype(float)
    restored = np.frombuffer(decoded, "<i2").astype(float)
    # 8-bit companding is lossy by design; a few percent is expected.
    assert np.abs(original - restored).max() / 32768 < 0.03


# --------------------------------------------------------------------- vad --

def test_silence_after_speech_closes_an_utterance():
    endpointer = Endpointer()
    events = []
    events += endpointer.push(speech_frames(10))
    events += endpointer.push(silence_frames(25))

    names = [name for name, _ in events]
    assert names == ["speech_start", "utterance"]
    utterance = events[1][1]
    assert audio.duration_ms(utterance) > 300


def test_a_click_is_not_an_utterance():
    endpointer = Endpointer()
    events = endpointer.push(speech_frames(2)) + endpointer.push(silence_frames(25))
    assert [name for name, _ in events] == ["speech_start"]


def test_speech_alone_does_not_close_an_utterance():
    endpointer = Endpointer()
    events = endpointer.push(speech_frames(40))
    assert [name for name, _ in events] == ["speech_start"]


# ---------------------------------------------------------------- stand-ins --

class FakeStt:
    """Answers with a fixed sentence as soon as the utterance ends."""

    name = "fake"

    def __init__(self, config, language, on_partial, on_final):
        self.on_partial = on_partial
        self.on_final = on_final
        self.pushed = 0

    def push(self, pcm):
        self.pushed += len(pcm)

    def finish(self):
        import time
        self.on_partial("hello th")
        self.on_final("hello there", time.time())

    def close(self):
        pass


class FakeTts:
    name = "fake"

    def __init__(self, config):
        self.spoken = []
        self.languages = []

    def synthesize(self, text, language):
        self.spoken.append(text)
        self.languages.append(language)
        # A quarter second of audio, so it arrives as several chunks.
        return b"\x01\x00" * (audio.SAMPLE_RATE // 4)


class FakeBrain:
    """Stands in for Java: answers every finished sentence with two of its own."""

    reachable = True
    # Whether opening the call also delivers the greeting and the bilingual
    # language question, the way the real brain does.
    greets = False

    def __init__(self, call_id, on_message, on_lost):
        self.call_id = call_id
        self._on_message = on_message
        self._on_lost = on_lost
        self.sent = []

    async def open(self, start):
        self.sent.append(start)
        if FakeBrain.greets:
            await self._greet()
        return FakeBrain.reachable

    async def _greet(self):
        await self._on_message({"type": "greeting", "text": "Hello, Example Shop.",
                                "language": "en", "last": False})
        await self._on_message({"type": "say", "seq": 0, "text": "English or Bangla?",
                                "language": "en", "last": False})
        await self._on_message({"type": "say", "seq": 0, "text": "à¦‡à¦‚à¦°à§‡à¦œà¦¿ à¦¨à¦¾ à¦¬à¦¾à¦‚à¦²à¦¾?",
                                "language": "bn", "last": True})

    async def deliver(self, message):
        """Something the brain decided on its own, with no caller behind it."""
        await self._on_message(message)

    async def send(self, payload):
        self.sent.append(payload)
        if payload.get("type") != "transcript_final":
            return
        await self._on_message({"type": "say", "seq": 1, "text": REPLY[0], "last": False})
        await self._on_message({"type": "say", "seq": 1, "text": REPLY[1], "last": True})

    async def close(self, reason="hangup"):
        self.sent.append({"type": "call_end", "reason": reason})

    def of_type(self, kind):
        return [message for message in self.sent if message.get("type") == kind]


class Recorder:
    def __init__(self):
        self.json = []
        self.audio = []

    async def send_json(self, payload):
        self.json.append(payload)

    async def send_audio(self, pcm):
        self.audio.append(pcm)


class _StubConfig:
    default_language = "en"
    stt_provider = "fallback"
    tts_provider = "fallback"


class Wiring:
    """Handles on the stand-ins a test needs to look inside."""

    def __init__(self):
        self.links = []
        self.voices = []

    @property
    def brain(self):
        return self.links[0]

    @property
    def spoken(self):
        """Every line the voice was asked to say, in order."""
        return [line for voice in self.voices for line in voice.spoken]

    @property
    def spoken_in(self):
        """The same lines paired with the language each was said in."""
        return [pair for voice in self.voices
                for pair in zip(voice.spoken, voice.languages)]


@pytest.fixture
def wired(monkeypatch):
    """A session with no network anywhere: no Java, no cloud, no microphone."""
    FakeBrain.reachable = True
    FakeBrain.greets = False
    wiring = Wiring()

    def build_link(call_id, on_message, on_lost):
        link = FakeBrain(call_id, on_message, on_lost)
        wiring.links.append(link)
        return link

    def build_tts(config, language):
        voice = FakeTts(config)
        wiring.voices.append(voice)
        return voice

    monkeypatch.setattr(session_module.providers, "build_stt",
                        lambda config, language, on_partial, on_final:
                        FakeStt(config, language, on_partial, on_final))
    monkeypatch.setattr(session_module.providers, "build_tts", build_tts)
    monkeypatch.setattr(session_module, "fetch", lambda: _StubConfig())
    monkeypatch.setattr(session_module, "fetch_strings",
                        lambda language: {"voice.link_lost": "The line has dropped."})
    monkeypatch.setattr(session_module.java_link, "TurnLink", build_link)
    return wiring


# ----------------------------------------------------------------- session --

def one_turn(recorder):
    """Start a call, say one sentence, and let the whole turn play out."""

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")
        await call.on_audio(speech_frames(10))
        await call.on_audio(silence_frames(25))
        await _settle()
        await call.close()
        return call

    return asyncio.run(scenario())


def test_the_callers_sentence_reaches_the_brain(wired):
    call = one_turn(Recorder())

    finals = wired.brain.of_type("transcript_final")
    assert len(finals) == 1
    assert finals[0]["text"] == "hello there"
    assert finals[0]["language"] == "en"
    assert call._turn_seq == 1


def test_the_brains_sentences_are_spoken_in_order(wired):
    one_turn(Recorder())
    assert wired.spoken == REPLY


def test_the_reply_is_streamed_in_chunks_not_one_lump(wired):
    recorder = Recorder()
    one_turn(recorder)

    assert len(recorder.audio) > 1, "the caller should hear the reply start before it finishes"
    assert all(len(chunk) <= session_module.CHUNK_BYTES for chunk in recorder.audio)


def test_the_page_is_told_when_the_agent_starts_and_stops_speaking(wired):
    recorder = Recorder()
    one_turn(recorder)

    states = [m["speaking"] for m in recorder.json if m.get("type") == "agent_state"]
    assert states == [True, False]


def test_the_microphone_is_ignored_while_the_agent_speaks(wired):
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")
        call._agent_speaking = True
        await call.on_audio(speech_frames(10))
        await call.on_audio(silence_frames(25))
        await _settle()
        await call.close()
        return call

    call = asyncio.run(scenario())
    assert call._turn_seq == 0, "audio heard while the agent talks is its own voice"


def test_the_latency_window_runs_from_recogniser_to_first_audio(wired):
    one_turn(Recorder())

    started = wired.brain.of_type("transcript_final")[0]["tSttFinal"]
    reports = wired.brain.of_type("spoken")
    assert len(reports) == 1, "one turn produces one reading, not one per sentence"
    assert reports[0]["seq"] == 1
    assert reports[0]["tTtsFirst"] >= started


def test_each_half_of_the_greeting_is_said_in_its_own_language(wired):
    """The one moment a call says the same thing twice, one language each."""
    FakeBrain.greets = True
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")
        await _settle()
        await call.close()

    asyncio.run(scenario())

    assert wired.spoken_in == [
        ("Hello, Example Shop.", "en"),
        ("English or Bangla?", "en"),
        ("à¦‡à¦‚à¦°à§‡à¦œà¦¿ à¦¨à¦¾ à¦¬à¦¾à¦‚à¦²à¦¾?", "bn"),
    ]
    # One agent turn covers the whole greeting: the microphone opens once, at
    # the end of it, not between the two halves of the question.
    states = [m["speaking"] for m in recorder.json if m.get("type") == "agent_state"]
    assert states == [True, False]


def test_an_utterance_nobody_could_make_out_is_still_reported(wired):
    """Two of these in a row is what makes the brain ask the caller to repeat."""
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")
        call._on_final("", 1234.5)
        await _settle()
        await call.close()
        return call

    call = asyncio.run(scenario())

    finals = wired.brain.of_type("transcript_final")
    assert len(finals) == 1
    assert finals[0]["text"] == ""
    assert call._turn_seq == 0, "an unheard utterance is not a turn"


def test_switching_language_changes_the_voice_for_what_follows(wired):
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")
        await wired.brain.deliver({"type": "set_language", "language": "bn"})
        await wired.brain.deliver({"type": "say", "seq": 1, "text": "à¦ à¦¿à¦• à¦†à¦›à§‡à¥¤", "last": True})
        await _settle()
        await call.close()
        return call

    call = asyncio.run(scenario())

    assert call.language == "bn"
    assert wired.spoken_in[-1] == ("à¦ à¦¿à¦• à¦†à¦›à§‡à¥¤", "bn"), "the new language is the default now"
    assert any(m.get("type") == "ready" and m.get("language") == "bn" for m in recorder.json)


def test_a_farewell_is_spoken_before_the_call_is_hung_up(wired):
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")
        await wired.brain.deliver({"type": "hangup", "reason": "wrong_number",
                                   "language": "en", "farewellText": "Goodbye."})
        await _settle()
        return call

    call = asyncio.run(scenario())

    assert wired.spoken == ["Goodbye."]
    assert call._closed
    assert wired.brain.of_type("call_end")[0]["reason"] == "wrong_number"


def test_a_brain_that_cannot_be_reached_apologises_and_ends_the_call(wired):
    FakeBrain.reachable = False
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")
        await _settle()
        return call

    call = asyncio.run(scenario())

    assert wired.spoken == ["The line has dropped."]
    assert call._closed
    assert wired.brain.of_type("call_end")[0]["reason"] == "brain_unreachable"


# ------------------------------------------------------- turn-taking bugs --

def test_a_sentence_arriving_late_still_ends_the_turn(wired):
    """BUG-006. The end of a turn used to be decided by asking two questions of
    each sentence as it finished: was this the one marked last, and is the queue
    empty now? A sentence still waiting behind the last one answers the second
    "no" — and that sentence is not itself marked last, so it answers the first
    "no" too. Neither ends the turn and nothing ever will. The microphone stays
    shut, the brain's silence clock never starts, and the call sits there.

    Counting what has been handed over against what has been spoken cannot fall
    down that gap.
    """
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")

        # The brain finishes its turn, then adds an afterthought before the
        # first sentence has finished being spoken.
        await call._enqueue(1, "That is all sorted.", True)
        await call._enqueue(1, "Oh, and one more thing.", False)

        await _settle()
        await call.close()
        return call

    asyncio.run(scenario())

    states = [m["speaking"] for m in recorder.json if m.get("type") == "agent_state"]
    assert states == [True, False], "the agent has to be heard to stop, not just to start"
    assert wired.brain.of_type("agent_done"), "the caller's silence clock never started"
    assert wired.spoken == ["That is all sorted.", "Oh, and one more thing."]


def test_a_transcript_is_held_onto_until_it_has_been_sent(wired):
    """BUG-003. The recogniser's callbacks are ordinary functions on the event
    loop, so the only way to send their result onward is a task — and the loop
    keeps only a weak reference to one. A task nobody else holds can be
    collected before it runs, silently losing a line of the caller's speech.
    """
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json, recorder.send_audio)
        await call.start("en")

        call._on_partial("half a sen")
        assert call._pending, "something other than the loop has to be holding it"

        await _settle()
        assert not call._pending, "and let go of it once it is done"
        await call.close()

    asyncio.run(scenario())

    partials = wired.brain.of_type("transcript_partial")
    assert [p["text"] for p in partials] == ["half a sen"]


async def _settle():
    """Let the turn tasks, which the recogniser callback starts, actually run.

    Long enough to cover the wait at the end of a turn as well. The session
    holds the turn open until its audio would have finished playing, and the
    stand-in voice returns a quarter of a second per sentence — so a
    three-sentence greeting is three quarters of a second of waiting before
    the microphone is handed back.
    """
    for _ in range(200):
        await asyncio.sleep(0.01)


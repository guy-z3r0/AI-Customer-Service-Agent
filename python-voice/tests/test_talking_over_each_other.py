"""Who has the floor, and when the other one may take it.

Every test here comes from something a caller actually experienced. The agent
cut its own farewell off halfway. It reopened the microphone into the tail of
its own voice, heard that as an utterance, made no words of it, and asked the
caller to repeat something the caller never said. And it asked whether anybody
was still there while the caller was in the middle of answering — which on the
free recogniser is invisible, because that one says nothing at all until a
sentence is finished.

Run with:  cd python-voice && python -m pytest
"""

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import session as session_module  # noqa: E402
# The stand-ins and the `wired` fixture are the same ones the pipeline tests
# use: no Java, no cloud, no microphone anywhere.
from test_voice_pipeline import (  # noqa: E402
    Recorder, _settle, silence_frames, speech_frames, wired,  # noqa: F401
)


# ------------------------------------------------- the caller holds the floor --

def test_the_brain_is_told_the_moment_the_caller_starts_talking(wired):
    """The only sign of a caller mid-sentence, when the recogniser gives none."""
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json,
                                           recorder.send_audio)
        await call.start("en")
        await call.on_audio(speech_frames(10))
        await _settle()
        await call.close()

    asyncio.run(scenario())

    assert wired.brain.of_type("caller_speaking"), \
        "a caller talking for twenty seconds must not look like an empty room"


def test_the_brain_is_told_when_the_sentence_ends(wired):
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json,
                                           recorder.send_audio)
        await call.start("en")
        await call.on_audio(speech_frames(10))
        await call.on_audio(silence_frames(25))
        await _settle()
        await call.close()

    asyncio.run(scenario())

    said = [message["type"] for message in wired.brain.sent
            if message["type"] in ("caller_speaking", "caller_stopped")]
    assert said == ["caller_speaking", "caller_stopped"], "in that order, once each"


# -------------------------------------------------- the agent holds the floor --

def test_the_turn_is_not_over_until_the_audio_has_really_been_heard(wired):
    """The estimate is a lower bound: audio crosses a socket and then waits in a
    buffer. Ending the turn on the estimate alone is what cut off a farewell."""
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json,
                                           recorder.send_audio, reports_playback=True)
        await call.start("en")
        await call._enqueue(1, "Thank you for calling. Goodbye.", True)

        # The page has not said it finished, so nothing may move yet.
        for _ in range(60):
            await asyncio.sleep(0.01)
        heard_early = wired.brain.of_type("agent_done")

        call.on_playback_finished()
        await _settle()
        await call.close()
        return heard_early

    heard_early = asyncio.run(scenario())

    assert not heard_early, "the turn ended before the caller had heard it"
    assert wired.brain.of_type("agent_done"), "and it does end once they have"


def test_a_transport_that_cannot_report_still_ends_its_turns(wired):
    """A telephone has the estimate and the tail guard, and nothing else."""
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json,
                                           recorder.send_audio, telephony="twilio")
        await call.start("en")
        await call._enqueue(1, "Thank you for calling. Goodbye.", True)
        await _settle()
        await call.close()

    asyncio.run(scenario())

    assert wired.brain.of_type("agent_done")


def test_the_microphone_stays_shut_past_the_end_of_the_agents_own_audio(wired):
    """The tail guard. Without it the agent hears its own last word come back,
    makes nothing of it, and asks the caller to say it again."""
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json,
                                           recorder.send_audio)
        await call.start("en")
        await call._enqueue(1, "One moment please.", True)

        # Just past when the samples would have finished playing, and inside the
        # guard: the line is still the agent's.
        await asyncio.sleep(0.25 + 0.05)
        still_speaking = call._agent_speaking

        await _settle()
        await call.close()
        return still_speaking, call

    still_speaking, call = asyncio.run(scenario())

    assert still_speaking, "the agent's own tail is not the caller talking"
    assert not call._agent_speaking, "and the floor does come back afterwards"


def test_a_report_from_between_two_sentences_does_not_end_the_turn(wired):
    """The page reports every time its queue drains, and that happens between
    sentences whenever one is synthesised slower than the last one plays. That
    report belongs to the sentence before it, not to the reply."""
    recorder = Recorder()

    async def scenario():
        call = session_module.VoiceSession("test-call", recorder.send_json,
                                           recorder.send_audio, reports_playback=True)
        await call.start("en")
        await call._enqueue(1, "One moment.", False)
        call.on_playback_finished()          # the gap between the two sentences
        await call._enqueue(1, "Here it is.", True)

        await _settle()
        ended_on_the_stale_report = wired.brain.of_type("agent_done")

        call.on_playback_finished()          # the real one, for the whole reply
        await _settle()
        await call.close()
        return ended_on_the_stale_report

    ended_early = asyncio.run(scenario())

    assert wired.spoken == ["One moment.", "Here it is."]
    assert not ended_early, "a report from mid-reply is not the end of the reply"
    assert wired.brain.of_type("agent_done")

"""Speaking with a Windows voice that SAPI will not list.

Windows keeps its speech voices in two places. The ones that shipped with the
machine are registered under Speech\\Voices; everything installed afterwards
through Settings -> Time & language -> Speech goes under Speech_OneCore\\Voices.
The offline engine this app otherwise uses reads only the first of those, and
refuses any voice id it did not enumerate — so installing a voice in Windows,
which is the advice every guide gives, changes nothing at all.

The way out is not to copy registry keys about, which is what most answers
suggest and is a change to somebody's machine that this app has no business
making. It is to ask SAPI for the other category by name, which it will do
quite happily, and hand it the token from there.

Windows only. Every entry point returns empty rather than raising, because the
caller's job is to fall back to the ordinary engine and carry on with the call.
"""

import logging
import math
import os
import tempfile

from audio import SAMPLE_RATE, resample_pcm16, wav_unwrap

log = logging.getLogger(__name__)

# The registry category holding the voices SAPI does not enumerate by default.
ONECORE_CATEGORY = r"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech_OneCore\Voices"

# SpeechStreamFileMode.SSFMCreateForWrite, and the audio format we want out:
# 16 kHz, 16-bit, mono, which is what the rest of the server speaks.
_CREATE_FOR_WRITE = 3
_FORMAT_16KHZ_16BIT_MONO = 18

# SAPI's speaking rate is -10 to 10, where 0 is its normal pace of roughly this
# many words per minute. The panel stores words per minute, like the offline
# engine wants, so the two have to be converted.
_NORMAL_WORDS_PER_MINUTE = 200.0


def is_onecore(voice_id: str | None) -> bool:
    """True for a voice id that came out of the OneCore half of the registry."""
    return bool(voice_id) and "Speech_OneCore" in voice_id


def render(text: str, voice_id: str, rate_words_per_minute: int, volume: float) -> bytes:
    """Speaks one sentence to 16 kHz PCM16, or returns nothing if it cannot.

    The caller holds the engine lock, so nothing here takes one of its own.
    """
    path = None
    try:
        import comtypes.client  # installed with the offline engine, on Windows

        handle, path = tempfile.mkstemp(suffix=".wav")
        os.close(handle)

        speaker = comtypes.client.CreateObject("SAPI.SpVoice")
        speaker.Voice = _token(comtypes.client, voice_id)
        speaker.Rate = _sapi_rate(rate_words_per_minute)
        speaker.Volume = max(0, min(100, round(volume * 100)))
        _speak_to_file(comtypes.client, speaker, text, path)

        with open(path, "rb") as rendered:
            data = rendered.read()
        if not data:
            return b""

        pcm, rate = wav_unwrap(data)
        return resample_pcm16(pcm, rate, SAMPLE_RATE)
    except Exception as e:
        log.warning("The Windows voice %s could not speak: %s", voice_id, e)
        return b""
    finally:
        if path and os.path.exists(path):
            os.unlink(path)


def _token(client, voice_id: str):
    """The voice, found in the category SAPI does not search by default."""
    category = client.CreateObject("SAPI.SpObjectTokenCategory")
    category.SetId(ONECORE_CATEGORY, False)
    for token in category.EnumerateTokens():
        if token.Id == voice_id:
            return token
    raise LookupError(f"Windows has no voice registered as {voice_id}")


def _speak_to_file(client, speaker, text: str, path: str) -> None:
    """Renders to a file rather than the machine's own speakers.

    The caller is at the other end of a websocket, so audio played out of this
    computer's speakers would reach nobody.
    """
    stream = client.CreateObject("SAPI.SpFileStream")
    try:
        stream.Format.Type = _FORMAT_16KHZ_16BIT_MONO
    except Exception:
        # A voice that will not produce this format still produces something,
        # and wav_unwrap reads whatever rate it chose.
        log.debug("A Windows voice would not take the 16 kHz format; using its own")
    stream.Open(path, _CREATE_FOR_WRITE, False)
    try:
        speaker.AudioOutputStream = stream
        speaker.Speak(text)
    finally:
        stream.Close()


def _sapi_rate(words_per_minute: int) -> int:
    if words_per_minute <= 0:
        return 0
    return max(-10, min(10, round(math.log2(words_per_minute / _NORMAL_WORDS_PER_MINUTE) * 10)))

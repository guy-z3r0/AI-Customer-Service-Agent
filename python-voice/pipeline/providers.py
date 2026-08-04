"""Choosing who does the speech work, and what they have to look like.

There are two providers for each job. Google Cloud is better and needs an
account; the free pair needs nothing and is what keeps a demo runnable on a
laptop with no credentials. The `auto` setting picks Google when a real key
file is present and the free pair otherwise, so nobody has to remember to
switch it.

If Google is asked for by name but cannot be built, the free provider is used
anyway and the reason is logged. A call that degrades is better than a call
that never starts.
"""

import logging

log = logging.getLogger(__name__)


class SttStream:
    """One utterance being recognised.

    Audio is pushed in as it arrives; finish() says the caller has stopped
    talking. Results come back through the two callbacks rather than a return
    value, because the good providers deliver interim guesses long before the
    final answer.
    """

    name = "none"

    def push(self, pcm: bytes) -> None:
        raise NotImplementedError

    def finish(self) -> None:
        """The utterance is over. The final result follows through on_final."""
        raise NotImplementedError

    def close(self) -> None:
        """Give back whatever the provider is holding. Safe to call twice."""


class TtsProvider:
    """Turns a sentence into 16 kHz mono PCM16, ready to send down the call."""

    name = "none"

    def synthesize(self, text: str, language: str) -> bytes:
        raise NotImplementedError


def build_stt(config, language: str, on_partial, on_final) -> SttStream:
    """Pick a speech recogniser and open a stream on it."""
    wanted = config.stt_provider
    if _should_use_google(wanted, config):
        try:
            from pipeline.stt_gcp import GoogleSttStream
            return GoogleSttStream(config, language, on_partial, on_final)
        except Exception as e:
            log.warning("Google speech-to-text unavailable (%s) — using the free recogniser", e)

    from pipeline.stt_fallback import FallbackSttStream
    return FallbackSttStream(config, language, on_partial, on_final)


def build_tts(config, language: str) -> TtsProvider:
    """Pick a voice."""
    wanted = config.tts_provider
    if _should_use_google(wanted, config):
        try:
            from pipeline.tts_gcp import GoogleTts
            return GoogleTts(config)
        except Exception as e:
            log.warning("Google text-to-speech unavailable (%s) — using the offline voice", e)

    from pipeline.tts_fallback import OfflineTts
    return OfflineTts(config)


def _should_use_google(wanted: str, config) -> bool:
    if wanted == "fallback":
        return False
    if wanted == "gcp":
        if not config.google_credentials_available():
            log.warning("Google was selected but no credentials file was found at %s",
                        config.credentials_path)
            return False
        return True
    # "auto", or anything unrecognised: use Google only if it is actually set up.
    return config.google_credentials_available()

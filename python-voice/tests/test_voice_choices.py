"""Which voice speaks, which language is listened for, and why Google is off.

These are the decisions behind "Bangla does not work". Each one of them can
fail silently — a voice name Google refuses, a recogniser listening for the
wrong language, a key file one character away from where the setting points —
and every one of them produces the same symptom: an English voice reading
Bengali letters.

Run with:  cd python-voice && python -m pytest
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import VoiceConfig  # noqa: E402
from pipeline import voices  # noqa: E402
from pipeline.stt_fallback import locales_for  # noqa: E402
from pipeline.tts_gcp import google_voice_name  # noqa: E402


# ------------------------------------------------------------ which voice --

def test_whichever_the_provider_picks_is_an_answer_not_a_gap():
    """Choosing the empty option in Settings used to be replaced by the
    hard-coded default, so the option quietly did not do what it said."""
    config = VoiceConfig({"tts_voice_bn": "", "tts_voice_en": ""})
    assert config.voice_name("bn") == ""
    assert config.voice_name("en") == ""


def test_a_named_voice_is_used_as_written():
    config = VoiceConfig({"tts_voice_bn": "bn-IN-Standard-A"})
    assert config.voice_name("bn") == "bn-IN-Standard-A"


def test_google_is_only_sent_a_voice_google_has():
    assert google_voice_name("bn-IN-Standard-A", "bn") == "bn-IN-Standard-A"
    assert google_voice_name("en-US-Neural2-C", "en") == "en-US-Neural2-C"


def test_a_machines_own_voice_is_not_sent_to_google():
    """The Settings menu lists both, and Google refuses the whole request over
    a name it does not know — so the caller would hear nothing at all."""
    sapi = r"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech\Voices\Tokens\TTS_MS_EN-US_ZIRA_11.0"
    assert google_voice_name(sapi, "en") == ""
    assert google_voice_name("en-US-Neural2-C", "bn") == "", "nor an English voice for Bangla"
    assert google_voice_name("", "bn") == ""


# --------------------------------------------------------- which language --

def test_a_bangla_call_listens_for_english_too():
    assert locales_for("bn") == ["bn-BD", "en-US"]


def test_an_english_call_listens_for_bangla_too():
    assert locales_for("en") == ["en-US", "bn-BD"]


def test_an_unknown_language_still_listens_for_something():
    assert locales_for("fr") == ["en-US"]


# ------------------------------------------------------- which voices exist --

def test_a_windows_bangla_voice_is_recognised_as_bangla():
    assert voices._language_of_text("Microsoft Bashkar - Bangla (India)") == "bn"
    assert voices._language_of_text("MSTTS_V110_bnIN_Bashkar") == "bn"
    assert voices._language_of_text("MSTTS_V110_enUS_ZiraM") == "en"
    assert voices._language_of_text("Microsoft Haruka - Japanese") == "other"


def test_listing_the_windows_voices_never_raises():
    """It reads a registry that only exists on Windows, and the container has
    no registry at all. A missing one is an empty list, not a failed startup."""
    assert isinstance(voices._onecore_voices(), list)


# ------------------------------------------------------ why google is off --

def test_a_key_file_one_name_away_is_named_rather_than_ignored(tmp_path):
    """Windows hides known extensions, so a key saved from a browser lands as
    gcp-credentials.json.json. Everything downstream degrades politely and
    silently, and the operator's only evidence is that Bangla sounds wrong."""
    (tmp_path / "gcp-credentials.json.json").write_text("{}")
    config = VoiceConfig({"gcp_credentials_path": str(tmp_path / "gcp-credentials.json")})

    reason = voices.why_google_is_off(config)

    assert reason["credentials"] == "missing"
    assert reason["nearMisses"] == ["gcp-credentials.json.json"]


def test_a_key_that_is_really_there_is_not_complained_about(tmp_path):
    path = tmp_path / "gcp-credentials.json"
    path.write_text('{"type": "service_account"}')
    config = VoiceConfig({"gcp_credentials_path": str(path)})

    assert voices.why_google_is_off(config) == {"credentials": "present", "nearMisses": []}


def test_an_empty_folder_has_nothing_to_suggest(tmp_path):
    config = VoiceConfig({"gcp_credentials_path": str(tmp_path / "gcp-credentials.json")})
    assert voices.why_google_is_off(config)["nearMisses"] == []


def test_the_voice_list_is_rebuilt_once_a_key_file_appears(tmp_path):
    """It used to be keyed on the provider alone and kept for the life of the
    process, so dropping a key into place changed nothing until a restart —
    and nobody knew to restart, because the panel listed what it listed before."""
    path = tmp_path / "gcp-credentials.json"
    config = VoiceConfig({"gcp_credentials_path": str(path)})
    before = voices._cache_key(config)

    path.write_text('{"type": "service_account"}')

    assert voices._cache_key(config) != before

"""Which voices this installation can actually speak with.

The Settings page used to ask an operator to type a voice name into a blank
field, which only works if you already know what is installed. This module
answers the question instead: it asks the speech engines what they have, tags
each voice with the language it speaks, and hands back a list the panel can
show as a menu.

It is also what makes Bangla work. The offline engine has no idea what language
it is being handed — give it Bengali text with an English voice selected and it
reads the letters as English, which is the noise a caller hears. Choosing a
voice by language is the whole fix, and when the machine has no Bangla voice at
all this is what lets the panel say so plainly instead of sounding broken.
"""

import logging
import threading

log = logging.getLogger(__name__)

# Enumerating voices means starting a speech engine, which is slow enough to be
# worth doing once. The panel asks every time Settings is opened.
_CACHE = {}
_CACHE_LOCK = threading.Lock()

# What a voice's name or id looks like when it speaks one of our two languages.
# SAPI on Windows reports no usable language field, so the name and the registry
# id are all there is to go on.
_MARKERS = {
    "bn": ("BN-", "BN_", "BENGALI", "BANGLA"),
    "en": ("EN-", "EN_", "ENGLISH"),
}


def available(config, refresh: bool = False) -> list[dict]:
    """Every voice that could be used on the next call.

    Each entry is {id, name, language, provider}. `language` is "en", "bn" or
    "other"; `id` is what goes into the tts_voice_* setting.
    """
    key = "gcp" if config.google_credentials_available() else "fallback"
    with _CACHE_LOCK:
        if refresh:
            _CACHE.clear()
        if key in _CACHE:
            return _CACHE[key]

        found = _google_voices(config) if key == "gcp" else []
        # The offline voices are always listed. They are what speaks if Google
        # is switched off or unreachable, so hiding them would hide the truth.
        found += _offline_voices()
        _CACHE[key] = found
        return found


def best_for(voices: list[dict], language: str, wanted: str | None) -> dict | None:
    """The voice to use for one language.

    A name the operator chose wins, if it is really installed. Otherwise the
    first voice that speaks the language. Otherwise nothing, and the caller
    hears whatever the engine defaults to — wrong, but not silent.
    """
    if wanted:
        for voice in voices:
            if wanted in (voice["id"], voice["name"]):
                return voice
    for voice in voices:
        if voice["language"] == language:
            return voice
    return None


# ------------------------------------------------------------------ offline --

def _offline_voices() -> list[dict]:
    try:
        import pyttsx3
    except Exception as e:
        log.warning("No offline speech engine to list voices from: %s", e)
        return []

    engine = None
    try:
        engine = pyttsx3.init()
        return [_describe(voice) for voice in engine.getProperty("voices")]
    except Exception as e:
        log.warning("Could not list the offline voices: %s", e)
        return []
    finally:
        if engine is not None:
            try:
                engine.stop()
            except Exception:
                log.debug("The offline engine did not stop cleanly after listing")


def _describe(voice) -> dict:
    return {
        "id": voice.id,
        "name": getattr(voice, "name", voice.id),
        "language": _language_of(voice),
        "provider": "fallback",
    }


def _language_of(voice) -> str:
    """Works out which of our two languages a voice speaks, or neither."""
    # espeak fills this in properly; SAPI usually leaves it empty.
    for tag in getattr(voice, "languages", []) or []:
        text = tag.decode("utf-8", "ignore") if isinstance(tag, bytes) else str(tag)
        for language, markers in _MARKERS.items():
            if text.upper().startswith(language.upper()) or _mentions(text, markers):
                return language

    haystack = f"{getattr(voice, 'name', '')} {voice.id}"
    for language, markers in _MARKERS.items():
        if _mentions(haystack, markers):
            return language
    return "other"


def _mentions(text: str, markers: tuple) -> bool:
    upper = text.upper()
    return any(marker in upper for marker in markers)


# ------------------------------------------------------------------- google --

def _google_voices(config) -> list[dict]:
    """The cloud voices, which are the ones worth having for Bangla."""
    try:
        import os

        from google.cloud import texttospeech

        os.environ.setdefault("GOOGLE_APPLICATION_CREDENTIALS", config.credentials_path)
        client = texttospeech.TextToSpeechClient()
        listed = client.list_voices()
    except Exception as e:
        log.warning("Could not list the Google voices: %s", e)
        return []

    found = []
    for voice in listed.voices:
        for code in voice.language_codes:
            language = code.split("-")[0].lower()
            if language not in ("en", "bn"):
                continue
            found.append({
                "id": voice.name,
                "name": f"{voice.name} ({code})",
                "language": language,
                "provider": "gcp",
            })
    return found

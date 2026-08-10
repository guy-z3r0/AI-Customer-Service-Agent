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
import os
import threading
import time

log = logging.getLogger(__name__)

# Enumerating voices means starting a speech engine, which is slow enough to be
# worth doing once. The panel asks every time Settings is opened.
_CACHE = {}
_CACHE_LOCK = threading.Lock()

# How long an answer is kept.
#
# It used to be kept for the life of the process, and the key was only which
# provider was in use — so dropping a Google key file into place, or installing
# a voice in Windows, changed nothing until somebody restarted the voice server.
# Nobody knew to, because the panel went on listing what it had listed before.
_CACHE_TTL_S = 60

# Where Windows registers the voices it installs through Settings, which is not
# where SAPI looks. See _onecore_voices.
ONECORE_TOKENS = r"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech_OneCore\Voices\Tokens"
_ONECORE_REGISTRY_PATH = r"SOFTWARE\Microsoft\Speech_OneCore\Voices\Tokens"

# What a voice's name or id looks like when it speaks one of our two languages.
# SAPI on Windows reports no usable language field, so the name and the registry
# id are all there is to go on.
_MARKERS = {
    "bn": ("BN-", "BN_", "BNBD", "BNIN", "BENGALI", "BANGLA"),
    "en": ("EN-", "EN_", "ENUS", "ENGB", "ENGLISH"),
}


def available(config, refresh: bool = False) -> list[dict]:
    """Every voice that could be used on the next call.

    Each entry is {id, name, language, provider}. `language` is "en", "bn" or
    "other"; `id` is what goes into the tts_voice_* setting.
    """
    key = _cache_key(config)
    with _CACHE_LOCK:
        if refresh:
            _CACHE.clear()
        cached = _CACHE.get(key)
        if cached is not None and time.monotonic() - cached[0] < _CACHE_TTL_S:
            return cached[1]

        found = _google_voices(config) if key[0] == "gcp" else []
        # The offline voices are always listed. They are what speaks if Google
        # is switched off or unreachable, so hiding them would hide the truth.
        found += _offline_voices()
        found += _onecore_voices()
        _CACHE[key] = (time.monotonic(), found)
        return found


def _cache_key(config) -> tuple:
    """What the answer depends on: the provider, and the key file behind it.

    The file is part of the key so that putting one in place — or fixing its
    name — invalidates the list by itself rather than needing a restart.
    """
    path = config.credentials_path
    try:
        stat = os.stat(path)
        credentials = (path, stat.st_size, int(stat.st_mtime))
    except OSError:
        credentials = (path, None, None)
    return ("gcp" if config.google_credentials_available() else "fallback", credentials)


def why_google_is_off(config) -> dict:
    """What to tell an operator when Google speech is not being used.

    A key file under a slightly wrong name is the commonest reason Bangla
    sounds wrong on a machine that has everything else set up, and every layer
    of this app is built to degrade around it quietly. This is what makes it
    visible: the folder is listed, and anything in it that looks like the
    intended file by another name is named.
    """
    if config.google_credentials_available():
        return {"credentials": "present", "nearMisses": []}
    return {"credentials": "missing", "nearMisses": _near_misses(config.credentials_path)}


def _near_misses(wanted_path: str) -> list[str]:
    """Files sitting beside the expected one whose name is nearly it."""
    folder = os.path.dirname(wanted_path) or "."
    wanted = os.path.basename(wanted_path)
    stem = os.path.splitext(wanted)[0]
    try:
        entries = os.listdir(folder)
    except OSError:
        return []
    return sorted(name for name in entries
                  if name != wanted and (name.startswith(stem) or name.startswith(wanted)))


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

    return _language_of_text(f"{getattr(voice, 'name', '')} {voice.id}")


def _language_of_text(haystack: str) -> str:
    for language, markers in _MARKERS.items():
        if _mentions(haystack, markers):
            return language
    return "other"


def _mentions(text: str, markers: tuple) -> bool:
    upper = text.upper()
    return any(marker in upper for marker in markers)


# ----------------------------------------------------------------- onecore --

def _onecore_voices() -> list[dict]:
    """The voices Windows installs but SAPI will not show you.

    Windows keeps two voice registries. Everything added through Settings ->
    Time & language -> Speech goes into Speech_OneCore, and SAPI — which is
    what the offline engine drives — reads only the older Speech one. So an
    operator installs a voice, Windows says it is there, and the panel goes on
    saying the machine cannot speak that language.

    Reading the registry is all that happens here: the keys are enumerated, not
    written. Copying them into the SAPI half is the workaround usually
    suggested, and this app will not do that to somebody's machine.
    """
    try:
        import winreg
    except ImportError:
        return []  # not Windows; the container has espeak instead

    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, _ONECORE_REGISTRY_PATH) as tokens:
            names = [winreg.EnumKey(tokens, index)
                     for index in range(winreg.QueryInfoKey(tokens)[0])]
            return [_describe_onecore(winreg, tokens, name) for name in names]
    except OSError as e:
        log.debug("No Windows OneCore voices to list: %s", e)
        return []


def _describe_onecore(winreg, tokens, key_name: str) -> dict:
    try:
        with winreg.OpenKey(tokens, key_name) as token:
            description = str(winreg.QueryValueEx(token, "")[0])
    except OSError:
        description = key_name

    return {
        "id": ONECORE_TOKENS + "\\" + key_name,
        "name": f"{description} (Windows)",
        "language": _language_of_text(f"{description} {key_name}"),
        "provider": "onecore",
    }


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

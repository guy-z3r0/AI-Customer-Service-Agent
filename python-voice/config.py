"""Where the voice server gets its settings.

Nothing that changes behaviour is hard-coded here. At the start of every call
the settings are fetched from the Java backend's /api/config, so editing a
value in the panel affects the next call with no restart anywhere. If Java
cannot be reached the defaults below keep the call working.

The one thing this file does own is how to find Java, which comes from the
environment because it differs between Docker and a laptop.
"""

import base64
import logging
import os

import requests

log = logging.getLogger(__name__)

JAVA_BASE_URL = os.environ.get("JAVA_BASE_URL", "http://localhost:8080").rstrip("/")
CONFIG_URL = JAVA_BASE_URL + "/api/config"
LANG_URL = JAVA_BASE_URL + "/api/lang"
CALL_URL = JAVA_BASE_URL + "/api/call"

REQUEST_TIMEOUT_S = 3

# The backend requires the operator login on everything except the Twilio
# webhook, so the voice server has to sign in like anything else. Same
# credentials as the panel; run-local.ps1 and docker compose pass both.
PANEL_USER = os.environ.get("PANEL_USER", "operator")
PANEL_PASSWORD = os.environ.get("PANEL_PASSWORD", "")


def java_auth() -> tuple[str, str] | None:
    """Credentials for calling Java, or None when none were provided."""
    return (PANEL_USER, PANEL_PASSWORD) if PANEL_PASSWORD else None


def java_auth_header() -> dict:
    """The same credentials as a header, for the websocket handshake."""
    if not PANEL_PASSWORD:
        return {}
    encoded = base64.b64encode(
        f"{PANEL_USER}:{PANEL_PASSWORD}".encode("utf-8")).decode("ascii")
    return {"Authorization": "Basic " + encoded}

DEFAULTS = {
    "stt_provider": "auto",
    "tts_provider": "auto",
    "tts_voice_en": "en-US-Neural2-C",
    "tts_voice_bn": "bn-IN-Standard-A",
    "tts_rate": 170,
    "tts_volume": 1.0,
    "default_language": "en",
    "gcp_credentials_path": "./secrets/gcp-credentials.json",
}

# Google's language tags, which are not the two-letter codes the rest of the
# app uses.
SPEECH_LOCALE = {"en": "en-US", "bn": "bn-BD"}
VOICE_LOCALE = {"en": "en-US", "bn": "bn-IN"}

# What else to listen for while a call is in one language. Callers here mix
# Bangla and English inside a single sentence, and a recogniser told to expect
# only one of them turns the other half into noise.
ALTERNATE_SPEECH_LOCALES = {"en": ["bn-BD"], "bn": ["en-US"]}


class VoiceConfig:
    """One call's worth of settings, already resolved to plain Python values."""

    def __init__(self, values: dict):
        self._values = values

    @property
    def stt_provider(self) -> str:
        return str(self._values.get("stt_provider", "auto")).lower()

    @property
    def tts_provider(self) -> str:
        return str(self._values.get("tts_provider", "auto")).lower()

    @property
    def default_language(self) -> str:
        return str(self._values.get("default_language", "en")).lower()

    @property
    def tts_rate(self) -> int:
        return _as_int(self._values.get("tts_rate"), 170)

    @property
    def tts_volume(self) -> float:
        return _as_float(self._values.get("tts_volume"), 1.0)

    def voice_name(self, language: str) -> str:
        """The voice the operator chose, or "" for whichever the provider picks.

        An empty setting used to be replaced with the default below, which meant
        the "whichever the provider picks" option in Settings quietly did not do
        that. The defaults still apply when Java cannot be reached at all —
        they are the base this dictionary is built on — but a value the operator
        deliberately cleared is now respected.
        """
        key = "tts_voice_bn" if language == "bn" else "tts_voice_en"
        return str(self._values.get(key) or "")

    @property
    def credentials_path(self) -> str:
        return str(self._values.get("gcp_credentials_path") or DEFAULTS["gcp_credentials_path"])

    def google_credentials_available(self) -> bool:
        """True only when a real key file is sitting where it should be.

        A placeholder is not a credential, and neither is a path to a file that
        does not exist — both mean "use the free providers instead".
        """
        path = self.credentials_path
        if not path or path.startswith("PLACEHOLDER_"):
            return False
        return os.path.isfile(path) and os.path.getsize(path) > 0


def fetch(session=requests) -> VoiceConfig:
    """Ask Java for the current settings; fall back to the defaults if it is down."""
    values = dict(DEFAULTS)
    try:
        response = session.get(CONFIG_URL, timeout=REQUEST_TIMEOUT_S, auth=java_auth())
        response.raise_for_status()
        for entry in response.json():
            # Secrets come back masked, which is fine — the voice server needs
            # none of them. It only reads provider names, voices and volumes.
            values[entry["key"]] = entry["value"]
        log.info("Loaded settings from Java")
    except (requests.RequestException, ValueError, KeyError, TypeError) as e:
        log.warning("Could not load settings from Java (%s) — using defaults", e)
    return VoiceConfig(values)


def fetch_strings(language: str = "en", session=requests) -> dict:
    """The spoken and displayed wording, which lives in Java's Lang.java."""
    try:
        response = session.get(LANG_URL, params={"lang": language},
                               timeout=REQUEST_TIMEOUT_S, auth=java_auth())
        response.raise_for_status()
        return response.json()
    except (requests.RequestException, ValueError) as e:
        log.warning("Could not load wording from Java (%s)", e)
        return {}


def _as_int(value, fallback: int) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return fallback


def _as_float(value, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback

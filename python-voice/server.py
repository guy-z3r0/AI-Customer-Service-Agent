"""The voice server.

Run it with:  uvicorn server:app --port 8090

It does one job: carry a call's audio. Speech goes in, text comes out, and the
reply the Java brain sends back goes out as speech. It holds no business
knowledge, no conversation history and no language model — all three live in
the Java backend, which each call reaches over its own websocket.
"""

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI

from config import JAVA_BASE_URL, fetch
from pipeline import voices as voice_catalogue
from transports import browser_ws, twilio_ws

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
)
log = logging.getLogger("voice")


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Everything before the first request, and after the last one.

    The two @app.on_event decorators this replaces have been deprecated since
    FastAPI 0.93 and are scheduled for removal; one context manager is also a
    plainer way of saying that the shutdown half is the startup half unwinding.
    """
    log.info("Voice server ready. Java backend at %s", JAVA_BASE_URL)
    yield
    # End live calls tidily instead of leaving them half-open in Java.
    for session in list(_live_sessions().values()):
        await session.close("server_shutdown")


app = FastAPI(title="AI Customer Service Agent — voice server", lifespan=lifespan)
app.include_router(browser_ws.router)
# The telephone route is always mounted. It costs nothing when nobody calls it,
# and a route that only exists once a credential is set is a route that is
# never tested until the day it matters.
app.include_router(twilio_ws.router)


@app.get("/health")
async def health() -> dict:
    """What the panel's status bar and the Java health check both read.

    This answers even when nothing is configured — reporting which providers
    would be used is the whole point of it.
    """
    settings = fetch()
    google_ready = settings.google_credentials_available()
    return {
        "status": "up",
        "activeCalls": len(_live_sessions()),
        "stt": _resolve(settings.stt_provider, google_ready),
        "tts": _resolve(settings.tts_provider, google_ready),
        "googleCredentials": "present" if google_ready else "missing",
    }


@app.get("/voices")
async def voices() -> dict:
    """Which voices the next call could actually use.

    The panel shows these as a menu on the Settings page. Only this server can
    answer it: the voices are whatever the operating system has installed, plus
    whatever Google offers once its credentials are in place.
    """
    settings = fetch()
    # Cached for a minute, and the cache key includes the Google key file — so
    # putting one in place, or correcting its name, shows up here by itself
    # rather than after a restart nobody knew to do.
    found = voice_catalogue.available(settings)
    return {
        "voices": found,
        # Said plainly, because a machine with no Bangla voice is the normal
        # state of a fresh Windows install and it is the reason Bangla sounds
        # wrong. The panel turns this into a sentence an operator can act on.
        "speaks": sorted({voice["language"] for voice in found} & {"en", "bn"}),
        "provider": "gcp" if settings.google_credentials_available() else "fallback",
        # And when it is off, why — which is nearly always a key file that is
        # not where the setting says it is.
        **voice_catalogue.why_google_is_off(settings),
    }


def _live_sessions() -> dict:
    """Every call in progress, whichever way it arrived."""
    return {**browser_ws.SESSIONS, **twilio_ws.SESSIONS}


def _resolve(wanted: str, google_ready: bool) -> str:
    """Which provider a call started right now would actually get."""
    if wanted == "fallback":
        return "fallback"
    if wanted == "gcp":
        return "gcp" if google_ready else "fallback"
    return "gcp" if google_ready else "fallback"

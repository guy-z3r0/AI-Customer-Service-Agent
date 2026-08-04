"""The voice server.

Run it with:  uvicorn server:app --port 8090

It does one job: carry a call's audio. Speech goes in, text comes out, and the
reply the Java brain sends back goes out as speech. It holds no business
knowledge, no conversation history and no language model — all three live in
the Java backend, which each call reaches over its own websocket.
"""

import logging
import os

from fastapi import FastAPI

from config import JAVA_BASE_URL, fetch
from transports import browser_ws

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)-5s %(name)s: %(message)s",
)
log = logging.getLogger("voice")

app = FastAPI(title="AI Customer Service Agent — voice server")
app.include_router(browser_ws.router)


@app.on_event("startup")
async def announce() -> None:
    log.info("Voice server ready. Java backend at %s", JAVA_BASE_URL)


@app.on_event("shutdown")
async def hang_up_everything() -> None:
    """End live calls tidily instead of leaving them half-open in Java."""
    for session in list(browser_ws.SESSIONS.values()):
        await session.close("server_shutdown")


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
        "activeCalls": len(browser_ws.SESSIONS),
        "stt": _resolve(settings.stt_provider, google_ready),
        "tts": _resolve(settings.tts_provider, google_ready),
        "googleCredentials": "present" if google_ready else "missing",
    }


def _resolve(wanted: str, google_ready: bool) -> str:
    """Which provider a call started right now would actually get."""
    if wanted == "fallback":
        return "fallback"
    if wanted == "gcp":
        return "gcp" if google_ready else "fallback"
    return "gcp" if google_ready else "fallback"

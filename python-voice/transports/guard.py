"""Who is allowed to open a call socket.

A websocket is not covered by the browser's same-origin rule: any page the
operator happens to have open can connect to ws://localhost:8090 and drive a
call — listen to it, or speak into it — unless something checks. Nothing did.

Two checks, both cheap:

  The call id has to be a UUID. It used to be any string at all, and it is
  interpolated into the URL the voice server then dials on the Java backend, so
  a value with a slash or a query character in it aimed that request somewhere
  else.

  The Origin has to be one we know. A program with no browser behind it — the
  Java backend, or Twilio's media stream — sends no Origin at all, and that is
  allowed; what is refused is a *different* site's page, which is the case the
  same-origin rule would have covered if it applied here.
"""

import logging
import os
import re

log = logging.getLogger(__name__)

# Close code 1008 is "policy violation", which is what this is.
POLICY_VIOLATION = 1008

_UUID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.IGNORECASE)

# The panel's own address. Overridable because the port is configurable and a
# demo may be served from somewhere else.
ALLOWED_ORIGINS = {
    origin.strip()
    for origin in os.environ.get(
        "ALLOWED_ORIGINS",
        "http://localhost:8080,http://127.0.0.1:8080").split(",")
    if origin.strip()
}


def call_id_is_sane(call_id: str) -> bool:
    return bool(call_id) and bool(_UUID.match(call_id))


def origin_is_allowed(origin: str | None) -> bool:
    """No Origin means no browser, which is a program and not a foreign page."""
    if origin is None or origin == "":
        return True
    return origin in ALLOWED_ORIGINS


async def refuse(websocket, why: str, call_id: str = "") -> None:
    log.warning("Refused a call socket (%s)%s", why, f" for {call_id}" if call_id else "")
    await websocket.close(code=POLICY_VIOLATION)

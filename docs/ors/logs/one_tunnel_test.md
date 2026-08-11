# One-tunnel test script — a Twilio call over a single public address

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 7's test script passed. Steps 1–3 need nothing at
all. Steps 4–8 need a Twilio account and ngrok, and one tunnel is now all you
need — which is the whole point of this pass, because a free ngrok account only
ever grants one hostname.

**Steps 4 and 6 decide this pass**: one tunnel carrying both the webhook and the
audio, and a real call whose voice-server log looks exactly as it always did.

---

## A. It builds, and nothing else moved

**1.** Both test suites.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, `Tests run: 208, Failures: 0, Errors: 0`. Five of
those came with this pass: four in `TwilioRelayTest` and one in
`ApiRequiresLoginTest`.

```bash
cd python-voice && python -m pytest
```

*Expected:* `51 passed` — unchanged, because no Python file was touched.

**2.** Start the stack.

```bash
docker compose up --build
```

*Expected:* all three containers healthy, and no new migration — this pass adds
no database change.

**3.** Place an ordinary **browser** call and let it run a turn or two.

*Expected:* exactly what it did before. The relay only sits on the telephone
path; a browser call still goes straight to the voice server on 8090.

---

## B. One tunnel

**4.** Start a single tunnel against the panel, not the voice server.

```bash
ngrok http 8080
```

Put the host it prints — the `abc123.ngrok-free.app` part, without `https://` —
in **Settings → Public media URL**, and set the TwiML App's Voice Request URL to
`https://<that same host>/api/twilio/voice` with method **POST**.

*Expected:* one tunnel running, and the same host written in both places. If
those two ever disagree, one of them is stale.

**5.** Reload the Live Call page.

*Expected:* the **Twilio call** button is enabled and its tooltip is gone, as
before — nothing about the seven settings changed.

---

## C. A real call

**6.** Place a Twilio call to your verified number and speak a question.

*Expected:* the agent answers and you hold a normal conversation. In the
backend's log, as the audio arrives:

```
TwilioMediaSocket : Relaying a Twilio media stream to ws://python-voice:8090/ws/twilio
```

And in the **voice server's** log, the same lines a telephone call has always
produced — `telephone call connected (stream MZ…)`, then the ordinary turn
logging. It cannot tell it was relayed, and that is what is being checked here.

*If the call connects and then goes silent:* look for that `Relaying …` line. No
line means Twilio never reached the backend, which is a stale host in the TwiML
App. A line followed by `could not reach the voice server` means the backend is
up and python-voice is not.

**7.** Hang up from the telephone.

*Expected:* the call ends in the panel as usual, and the backend logs both ends
of the pair closing:

```
TwilioMediaSocket : The Twilio end of a media stream closed (1000)
TwilioMediaSocket : The voice server's end of a media stream closed (1000)
```

Two lines, not one. One would mean a socket was left half-open with Twilio still
pushing audio into it.

**8.** Stop the `python-voice` container, then place another Twilio call.

```bash
docker compose stop python-voice
```

*Expected:* the call connects and then ends promptly rather than hanging. The
backend logs a single warning naming the address it could not reach. Start the
container again afterwards:

```bash
docker compose start python-voice
```

---

## What this pass did not fix

A real Twilio call had never been placed when this was written, and no part of
this script can be signed off from the code alone — the relay was exercised
against a stand-in on 8090, which proves the pipe and proves nothing about
Twilio. Steps 6 and 7 are still the first time this project meets a telephone.

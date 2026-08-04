# Phase 7 log — Twilio mode, a second business, polish

**Closed:** 2026-08-05
**Status:** built and run against a live stack, **with a real language model
key for the first time**. That closed four things carried as unverified since
Phase 3, and turned up a real bug in Phase 5's caller matching that only a live
model could have exposed. Twilio itself is built and placeholder-guarded but has
never carried a call — there are no Twilio credentials in this environment.

This is the last phase in `phases.md`.

---

## What ships

A call can now arrive over a real telephone line. Twilio's Media Streams
websocket lands on the same voice server as a browser tab, the audio is
converted at that edge, and everything past it — the brain, the screening, the
knowledge base, the transcript, the summary — is the same code taking the same
call. Without credentials the button is disabled with a tooltip and nothing
else changes.

There is a second business, **Demo Courier**, a parcel service in a different
trade from Template Business, with its own hours, prices, policies, persona and
customers. Switching the active business in the top bar changes what the agent
says and who it recognises, with no code involved. That was the claim; it is now
demonstrable in about four seconds.

And the tuning pass found the thing that mattered most: the model the app
shipped with was answering in **3823 ms** where the whole turn is meant to take
2000 ms.

---

## Files added

**Java** — `api/TwilioController.java` (access token and TwiML),
`db/migration/V4__seed_demo_two.sql`, `V5__current_default_model.sql`,
`V6__faster_default_model.sql`.

**Python** — `transports/twilio_ws.py`, the Media Streams protocol and the
mu-law edge.

**Panel** — `js/twilio_mode.js`, which is the whole of the difference between a
telephone call and a browser call as far as the page is concerned.

**Tests** — `ClientMatchingTest` (4) for the caller-matching rule and
`tests/test_twilio_transport.py` (5) for the transport. **59 Java tests, 22
Python.**

## Files changed

`CallDtos.StartRequest` gained `callerNumber`, and `CallLogService.start` now
recognises a caller by the number they are ringing from as well as by a code —
which is the only clue a telephone gives. `ClientService` gained
`sameNumber`, and lost the bug described below. `SseChat` now retries a
transient refusal. `server.py` mounts the telephone route and counts calls from
both transports. `live_call.js` dials Twilio and hangs it up. `Lang` and
`LangPages` gained the Twilio wording. `README.md` was rewritten from scratch —
it still described version 1, with JSON files and console commands.
`docs/SETUP.md` gained the Twilio and ngrok walkthrough.

---

## Decisions worth knowing

**No Twilio SDK on either side.** An access token is a JWT with particular
claims signed with the API key secret; minting one is about twenty lines of
`javax.crypto`. Adding a dependency — and its transitive tree — to one endpoint
would have been the larger change and the harder one to read. The Media Streams
protocol is likewise four JSON event types, handled directly.

**The SDK pin was verified in a browser, and the obvious URL was wrong.**
`sdk.twilio.com/js/voice/releases/2.18.3/twilio.min.js` — the path most guides
give — does not load. `cdn.jsdelivr.net/npm/@twilio/voice-sdk@2.18.3` does, and
exposes `Twilio.Device` with `isSupported` true and the three methods this app
calls. Checked by loading it into the running panel rather than by trusting a
version number, because a 404 here would only ever surface during a demo.

**The telephone route is always mounted.** It costs nothing when nobody calls
it, and a route that only exists once a credential is set is a route nobody
tests until the day it matters.

**A failure has to be TwiML, not an HTTP error.** By the time Twilio asks what
to do, a caller is already on the line. An unconfigured system answers with a
spoken apology and a hangup; returning a 500 would play Twilio's own error
message to a stranger.

**`byPhone` was matching the wrong customer, and masking is what exposed it.**
The model called `lookup_client` with the phone number as it had received it —
`[MASKED_PHONE]`, because Phase 6 masks caller text on the way to the model.
That string has no digits, the old rule compared with `endsWith`, and every
string ends with the empty one, so **the first customer on the books matched**.
A live call greeted a stranger as "Example Sender One" and switched to
EXISTING_CUSTOMER, which would have answered them from somebody else's record.
Both sides now need six digits before they can match. Four unit tests, one of
them the masked case verbatim.

**A 503 and a 404 are not the same answer.** `SseChat` refused to retry
anything the vendor rejected, on the reasoning that a bad key fails twice. But
an overloaded model says 503 and means "not now" — and that is exactly what
swallowed one summary during testing. Rate limits and the 5xx family are
retried once; 4xx still is not. The rule that a stream which already spoke is
never retried is untouched, because a caller must not hear the first half of a
sentence twice.

**Two migrations for one setting, deliberately.** V5 corrects a model that
Google has shut down; V6 replaces it with one that is fast enough. Folding them
into one file would have been tidier, but V5 had already been applied to the
development database, and editing an applied migration means a Flyway checksum
failure on the next boot — a confusing error in exchange for cosmetics. Both
match on the value they replace, so an operator's own choice is never
overwritten.

**Demo Courier's knowledge base is in English.** The greeting, the persona and
every panel string are bilingual, and a Bangla caller gets Bangla answers
because the model translates as it reads. A Bangla-only knowledge base would
have demonstrated less, not more: it would have forced translation in the other
direction for English callers, with prices and policies as the thing being
translated.

**The test file split at the cap.** `test_voice_pipeline.py` reached 559 lines
with the Twilio cases in it. The transport tests are a separate subject with
their own stand-ins, so they became `test_twilio_transport.py`.

---

## The tuning pass

Five real questions against Demo Courier's knowledge base, timed from the
caller's sentence being final to the first word of the reply arriving — the
model's own share of the budget, which is what can be measured without a
microphone. Same questions, same machine, same key, minutes apart:

| Model | Median | Fastest turn |
|---|---|---|
| `gemini-3.6-flash` | 3823 ms | 2018 ms |
| `gemini-3.1-flash-lite` | **800 ms** | **704 ms** |

The whole turn — recognition, model, speech — is meant to fit in 2000 ms, so
the first of those spends the entire allowance before the voice starts. The
lighter model was checked for the thing that would have ruled it out: it still
reaches for tools unprompted, and `escalate_to_human` fired correctly on a
refund it could not approve. It is now the seeded default, by V6.

**One thing is unexplained and should not be treated as solved.** Roughly one
turn in five stalls for 15–18 seconds. It is not the model tier — it happened
on both. It is not a retry and not a tool call; neither appears in the log for
those turns. It is not the question — the outlier moved between runs. And it is
not IPv6 fallback, which was the obvious guess: running the JVM with
`-Djava.net.preferIPv4Stack=true` did not reduce it. That leaves the upstream
endpoint or the local network, and pinning it down needs a packet-level look or
a stamp taken at the moment the request is written, which this app does not
currently take. It is the first thing to chase if the latency target matters.

---

## Verified

Against a live stack, with a real Gemini key:

- **V4, V5 and V6 applied** on boot, in order, from an existing database.
- **Config-only onboarding.** Activating Demo Courier and placing a call: the
  agent answered *"Demo Courier, this is Rafi speaking"*, from the seeded
  persona, with no restart and no code change.
- **A real conversation.** Correct answers from Demo Courier's own knowledge —
  delivery charges, coverage, Friday opening — none of which exists anywhere
  but in V4.
- **A real model-written summary**, parsed into the asked-for shape:
  *"The caller contacted Demo Courier to request a refund of [MASKED_AMOUNT]…"*
  with `structured` carrying caller, intent, outcome and `sentiment: unhappy`,
  and `mode_path` filled from the database rather than the model.
- **Masking survives the whole way out** — into the summary, into the action
  item, and into the escalation email body, while the stored transcript keeps
  what was really said.
- **The model chose `escalate_to_human` itself**, unprompted, on a refund it
  could not approve — with the caller's details as the note for the colleague.
- **Both Twilio guards.** `GET /api/twilio/token` answers 409 with a readable
  sentence while credentials are placeholders; `POST /api/twilio/voice` answers
  **200 with TwiML** that speaks an apology and hangs up, never an error.
- **The panel**, in a browser, no console errors: the Twilio button disabled
  with its tooltip, and the whole page still working around it.
- **The Twilio SDK** loads from the pinned URL and exposes what this code calls.
- `mvn -o test` — **59 Java tests**. `python -m pytest` — **22 Python tests**.
  The transport tests cover the call id arriving in custom parameters, a stream
  with no id being refused rather than guessed at, 20 ms of telephone audio
  arriving as 20 ms of internal audio, rubbish base64 not ending a call, and a
  reply going back as telephone-sized pieces rather than one lump.
- No file over 500 lines; the largest is `live_call.js` at 468.

## What is not verified

- **A Twilio call has never been placed.** No account, no credentials, no ngrok
  in this environment. What is tested is the transport's protocol handling, the
  token's shape, the TwiML, and every path taken when the credentials are
  absent. What is untested is Twilio accepting the token, the webhook being
  reached, and audio actually flowing.
- **SMTP still has never opened a connection**, so the escalation email is
  logged rather than sent.
- **Google Cloud speech, and the microphone.** Every call in this phase was
  driven over the turn socket with typed text, so no turn carries a `tTtsFirst`
  and the Dashboard's reply-time tiles stay empty. The measurements above are
  the model's share only.
- **`docker compose up` has still never been run.** Every session has used the
  embedded-PostgreSQL dev profile.
- **Bangla end to end.** The strings, greetings and seeded content exist and are
  served correctly; nobody has held a Bangla conversation.

`phase_07_test.md` walks all of it. Steps 4, 7 and 11 are the three that decide
the phase.

---

## Commit log

- Carry a call over a real telephone: Twilio media streams into the same voice server, an access token minted without the SDK, and TwiML that apologises out loud rather than erroring
- Seed a second business in another trade, so switching it in the top bar is the whole of onboarding
- Stop a masked phone number matching the first customer on the books, which greeted a stranger by somebody else's name
- Retry a model that says "not now" and not one that says "no", and move the default model to one that answers inside the budget
- Rewrite the README for the app this became, and fill in the Twilio and ngrok walkthrough

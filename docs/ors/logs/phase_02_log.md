# Phase 2 log — Voice loop v2: browser call, VAD, STT, TTS, echo

**Closed:** 2026-08-04
**Status:** built; the turn-taking logic is unit-tested and the panel was driven
with real live-feed events. Not yet run against a live stack — see "What is not
verified".

---

## What ships

The Live Call page places a call from the browser tab. Speech goes out over a
websocket to the Python voice server, an endpointer decides when a sentence has
finished, a recogniser turns it into text, and the agent speaks it back — "You
said: …" — while the transcript, the turn count and a per-turn latency badge
appear live in the panel.

Providers pick themselves: Google Cloud speech when a real credentials file is
present, the free offline pair otherwise. Nothing about that choice needs a
restart or a setting change.

---

## How a turn actually flows

```
browser mic ──48 kHz──▶ AudioWorklet ──16 kHz PCM16──▶ ws://…:8090/ws/browser/{callId}
                                                                    │
                                          ┌─────────────────────────┤
                                          ▼                         ▼
                                   Endpointer (VAD)          SttStream (gcp | free)
                                          │                         │
                                600 ms silence                 final text
                                          └──────────┬──────────────┘
                                                     ▼
                                              echo reply (Lang)
                                                     ▼
                                            TtsProvider ──PCM16──▶ browser speakers
                                                     │
                                       POST /api/call/{id}/line ──▶ Java
                                                                     ├─ call_message row
                                                                     └─ /ws/live ──▶ panel
```

Audio never passes through Java, and text never passes through the audio
socket. That split is deliberate: it keeps the path between a microphone and a
recogniser as short as it can be, which is where the two-second budget is won
or lost.

---

## Files added

**python-voice/** (renamed from `python-scripts/`)
`server.py` (FastAPI app, /health, session registry) · `session.py` (one call:
turn-taking, half-duplex gate, latency stamps) · `config.py` (settings from
Java, env fallback) · `audio.py` (resampling, PCM16, WAV, μ-law) ·
`java_link.py` (line and end reporting) · `transports/browser_ws.py` ·
`pipeline/{vad, providers, stt_gcp, stt_fallback, tts_gcp, tts_fallback}.py` ·
`tests/test_voice_pipeline.py` · new `requirements.txt` and `Dockerfile`.

**Java** — `api/{LiveEventSocket, WsConfig, CallController}.java`,
`services/CallLogService.java`, `api/dto/CallDtos.java`. `HealthController` now
really pings the voice server instead of reporting "not built". `Lang.java`
grew the Live Call vocabulary and the spoken echo line, both languages.

**Panel** — `js/ws.js`, `js/audio/{mic_stream, mic_worklet, player}.js`,
`js/pages/live_call.js`, plus the `list-row` and transcript parts in
`parts.css`. `app.js` gained the live feed, a page-cleanup hook and an
active-business getter.

**Compose** — the `python-voice` service, and the two voice URLs the backend
needs.

## Files deleted

`stt_sender.py`, `tts_speaker.py`, `ai_agent.py`, and the body of the old
`config.py` — exactly Phase 2's list. `PyAudio`, `google-genai` and
`deep-translator` left `requirements.txt` with them: audio now arrives over a
socket rather than from a local microphone, and the language model moved to
Java.

---

## Decisions worth knowing

**μ-law is written out by hand, not taken from `audioop`.** BUILD_SPEC names
`audioop`, which was removed from Python in 3.13. The G.711 tables are about
fifteen lines of numpy and work on every version. Verified against the
canonical values: silence encodes to `0xFF`, positive full scale to `0x80`,
negative full scale to `0x00`.

**One Google stream per utterance, on a worker thread.** Google's recogniser
pulls audio from a generator on its own thread, which does not fit inside an
asyncio handler. Audio goes in through a queue and results come back with
`call_soon_threadsafe`. Opening a fresh stream per sentence keeps it simple and
stays far inside Google's five-minute limit.

**The free recogniser has no interim results.** It takes one finished recording
and returns one answer, so in fallback mode the panel shows nothing until the
caller stops talking. That is a real difference in feel between the two
providers and it is why `auto` prefers Google whenever it can.

**Two addresses for the voice server.** `voice.internal-url` is how the backend
reaches it container-to-container; `voice.public-ws-url` is what the browser is
told to dial. They are different under Docker and identical on a laptop, and
the page never hard-codes either — `/api/call/start` hands the address back.

**The half-duplex gate lives on the server.** The page mutes the microphone
while the agent speaks, but the session drops incoming audio as well. Browser
echo cancellation is good, not perfect, and the consequence of getting this
wrong is the agent holding a conversation with itself.

**Resampling happens in the AudioWorklet.** 48 kHz to 16 kHz on the audio
thread, so a busy panel cannot make a call stutter, and the socket carries a
third of the bytes.

**`CallLogService` arrived a phase early.** BUILD_SPEC places it in Phase 3, but
Phase 2 promises the transcript is persisted, and that needs it. Phase 3 adds
summaries and mode transitions to it rather than creating it.

**`api/dto/CallDtos.java` holds four records in one file.** BUILD_SPEC caps the
dto directory at six files and five were already used. The call bodies only
mean anything as a set, so they share a container rather than pushing the
directory to nine files.

---

## Verified

- `mvn clean package` builds; 17 panel assets are packaged in the jar.
- `python -m pytest` — 11 tests pass, covering the parts that need no account:
  resampling, WAV round-trips, μ-law round-trips, and the turn-taking itself
  with the recogniser and voice replaced by stand-ins. Those assert that
  silence closes an utterance, that a turn produces a reply and reports both
  lines, that the reply is streamed in chunks rather than one lump, that the
  page is told when the agent starts and stops speaking, that audio heard while
  the agent talks is discarded, and that `tTtsFirst` never precedes
  `tSttFinal`.
- The Live Call page was driven with real `/ws/live` events through a stub
  socket: the live partial row is replaced by the real line, role badges render
  Caller/azure and Agent/jade, latency badges attach to the right rows and turn
  rose above the 2000 ms target, and the median of 1450 and 2650 came out at
  2050 ms.
- Degradation: with the voice server reported down, pressing **Start browser
  call** shows "The voice server is not answering" and leaves the page idle
  with the button live. No console errors, and the live-feed socket retried
  quietly rather than logging.
- All 112 panel strings exist in both languages, and every Bangla entry
  actually contains Bengali script.

## Two bugs the tests caught

- The endpointer measured an utterance's length including the 600 ms of silence
  that ended it, so a 60 ms cough counted as a sentence. It now counts only the
  frames that held speech.
- The μ-law exponent was derived with a bit test that was wrong for every
  magnitude that was not a power of two — a 60% error at full scale. Replaced
  with `frexp`.

## What is not verified

No Docker, no PostgreSQL, no microphone and no Google credentials in this build
environment. None of the following has run:

- `docker compose up --build` with three services
- The AudioWorklet, `getUserMedia`, and audio actually being heard
- Either real recogniser, or either real voice
- Google's streaming API, the credentials check against a real key file
- The espeak-ng path in the Python image
- End-to-end latency, and therefore whether a turn lands under two seconds

`phase_02_test.md` walks all of it. The demo in step 8 is the one that matters.

---

## Commit log

- Rewrite python-scripts as python-voice: FastAPI server, per-call session, browser websocket transport
- Add the speech pipeline with VAD endpointing and auto-selected Google or offline providers
- Add the per-call REST endpoints, transcript persistence and the /ws/live feed to the backend
- Build the Live Call page: microphone capture, streamed playback, live transcript and latency badges
- Cover the turn-taking, resampling and μ-law codec with tests that need no cloud account

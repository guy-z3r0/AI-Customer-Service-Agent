# AI Customer Service Agent v2

## The gist

A voice agent that picks up the phone for small businesses in English or Bangla, figures out
who's calling (regular, newcomer, spam, or something too hard for a bot), answers from that
business's own knowledge, and writes everything down — transcript, summary, and an email to
a human when it's out of its depth. The v1 in this repo already proved the idea with a
console app and a local microphone; v2 turns it into the thing the course proposal actually
describes: calls placed from a website, PostgreSQL under it, Docker around it, and a proper
control panel instead of typing `start-call` into a terminal.

The design bet is dual-mode everything. Calls work browser-direct (no accounts, no ngrok, no
mercy of trial credits) and upgrade to Twilio when credentials exist. Voice uses Google Cloud
when a key file is present and falls back to v1's free STT + offline TTS when it isn't. The
brain runs on Gemini or OpenAI — you bought both keys, so both are wired behind one
interface and you flip them in Settings. A demo that can't be killed by a missing credential.

## Scope

**In:** web control panel (replaces console), browser + Twilio calling, streaming Python
voice pipeline, four-way screening state machine, bilingual EN/BN, swappable LLM providers,
Postgres + Flyway + Docker Compose, full in-app editing of businesses/KB/prompts/clients/
config, call logs + LLM summaries, escalation email, PII masking, latency metrics, legacy
JSON import, placeholder-first setup.

**Out:** JavaFX desktop app, real phone numbers/PSTN, login/multi-user auth, live human call
transfer, custom Bangla model training, CRM push integrations, light mode.

## Features

**Calling** — dial from the Live Call page with your mic; live transcript with mode,
language, and per-turn latency badges; manual mode override for testing; Twilio button
lights up once configured.
**Brain** — greets with the mandatory AI disclosure, screens into four modes, answers from
the business KB, onboards new customers into the DB mid-call, politely dumps spam, escalates
complex cases, warns then hangs up on silence.
**Panel** — dashboard (calls, latency, mode split), businesses with a tabbed editor (about,
services, policies, FAQs, persona, hours & escalation), clients with past issues, call
history with transcripts + summaries + export, settings for every key and knob. Everything
you could edit in the JSON files, now with validation and without restarts.
**Ops** — one `docker compose up`; every credential starts as a named `PLACEHOLDER_*` you
fill in later (Settings page badges what's missing); one-click import of your existing v1
business data.

## What you need before running it

- Docker Desktop (or Java 21 + Maven + Python 3.10 to run on host)
- A copied `.env` from `.env.example` (only the DB password and PII key are truly required)
- Then, whenever you're ready, pasted into Settings: Gemini and/or OpenAI key; optional GCP
  service-account JSON at `secrets/`; optional Twilio credentials + ngrok URL; optional SMTP
- `docs/SETUP.md` walks all of it in order — the system runs (degraded, politely) without
  any of the optional ones

## Dependencies

| Name | Version | Why |
|---|---|---|
| Spring Boot (web, websocket, data-jpa, validation, mail) | 3.4.4 | the backend frame v1 already used, plus WS/DB/mail it now needs |
| PostgreSQL + Flyway | 16 / Boot-managed | the graded DB deliverable, with versioned migrations |
| Gson | 2.10.1 | already in the repo; LLM JSON |
| FastAPI + uvicorn | 0.115.6 / 0.32.1 | Python WS audio server |
| google-cloud-speech / -texttospeech | 2.28.0 / 2.21.1 | streaming STT + Bangla-capable TTS |
| webrtcvad | 2.0.10 | tiny, proven voice-activity detection |
| SpeechRecognition + pyttsx3 | 3.10.4 / 2.90 | the can't-be-killed fallback, straight from v1 |
| Twilio JS SDK + twilio-python | pinned at Phase 7 | the optional real-telephony path |

## The tricky parts

Honest list. **Latency** is the boss fight: 2 seconds is four network hops (STT, LLM,
TTS, transport) — the whole design streams everything and stamps every stage so when it's
slow you'll know which hop to blame. **Bangla STT/TTS** will be the weakest link; the
proposal itself predicts 35–50% WER in the wild, which is why fallback re-prompts exist and
why the report should present it as a finding, not a bug. **Echo/barge-in** in browser mode
is handled the blunt v1 way (mic gated while the agent talks) — fancy interruption handling
was deliberately not attempted. **Twilio Media Streams** needs a public URL; that's ngrok at
demo time and the single most likely thing to misbehave live, which is why it's Phase 7 and
optional, not the foundation.

## How it's built

Seven phases, each ending in something you can run: DB + panel shell first, then the voice
loop with no AI, then the brain, then screening + Bangla, then CRM + full editing, then
logging/summaries/metrics, then Twilio + polish. Claude Code builds one phase, hands you a
log and a test script, and waits. `CLAUDE.md` at the repo root tells any fresh session where
to pick up.

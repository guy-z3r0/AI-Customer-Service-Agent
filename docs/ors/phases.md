# Phases — AI Customer Service Agent v2

Mode: PHASED. Each phase ends runnable + demoable. At phase close: phase log, test script,
architecture.md update, PROJECT_STATE.md update — then stop and wait for approval.
Every phase entry is self-contained; load only the current one.

## Phase 1 — Foundations: Postgres, migrations, panel shell, placeholders
**Ships:** `docker compose up` starts Postgres + backend. Flyway creates schema, seeds Template Business + all PLACEHOLDER_* config. Panel loads at :8080: Nocturne shell (side-rail, top-bar, status-bar), Businesses read-only list, Settings page showing every config key with amber PLACEHOLDER badges and working save. One-shot legacy import brings v1 `data/businesses/*` into the DB.
**Touches:** docker-compose.yml, .env.example, docs/SETUP.md, pom.xml (Boot 3.4.4 + jpa/websocket/validation/mail/postgres/flyway), application.yml, db/migration/V1__baseline.sql + V2__seed.sql, domain/* (all entities + enums), repo/*, services/{BusinessService, ConfigService, LegacyImportService}, api/{BusinessController, ConfigController, HealthController, ImportController, ApiExceptionAdvice}, static/{index.html, css/nocturne.css, css/parts.css, js/{app,api,components}.js, js/pages/{businesses,settings}.js}, CLAUDE.md, secrets/ placeholder. Replaces managers/* and models/* (delete after services compile). Console stays functional-enough to not block (`start-call` may be stubbed with "use panel from Phase 2").
**Depends on:** nothing.

- Bump pom, add deps; entities/repos per BUILD_SPEC data model; pgcrypto for client contact columns (PII_ENC_KEY env)
- V2 seed: Template Business full starter content (KB, persona, greetings EN+BN, hours, escalation contact, clients C001/C002) — sample text written to be obviously editable
- ConfigService with isPlaceholder + secret masking on GET
- Panel shell strictly from STYLE-CONTRACT parts; no colors outside nocturne.css
- css/nocturne.css transcribed from STYLE-CONTRACT.md (read it; it is at repo root)
- docs/SETUP.md sections: prerequisites, .env, compose up, panel tour, key entry, editing Template Business; GCP/Twilio/SMTP sections marked "needed later, placeholders fine"

**Done when:** clean clone + `.env` → compose up → panel shows Template Business + imported v1 businesses; editing a Settings value persists across restart; health endpoint green (db) / amber (placeholder keys); Flyway runs idempotently.

## Phase 2 — Voice loop v2: browser call, VAD, STT, TTS, echo (no LLM)
**Ships:** Live Call page: Start browser call → speak → live partial transcript → on utterance end the agent speaks an echo reply ("You said: …" via Lang) in <2 s. Provider auto-select: GCP STT/TTS when credentials valid, else fallback (free STT + pyttsx3). Live transcript + agent speaking state visible in panel via LiveEventSocket.
**Touches:** python-voice/{server.py, session.py, config.py, audio.py, transports/browser_ws.py, pipeline/{vad.py, providers.py, stt_gcp.py, stt_fallback.py, tts_gcp.py, tts_fallback.py}, requirements.txt, Dockerfile}, compose service python-voice, api/{LiveEventSocket, WsConfig, CallController stub POST /api/call/start}, static/js/{ws.js, audio/mic_stream.js, audio/player.js, pages/live_call.js}. Deletes stt_sender.py, tts_speaker.py, ai_agent.py, old python config.
**Depends on:** Phase 1.

- AudioWorklet capture 48 k → resample 16 k PCM16; binary WS framing per BUILD_SPEC
- webrtcvad endpointing (30 ms frames, 600 ms silence closes utterance)
- Half-duplex gate: mic frames dropped while agent audio queue non-empty; agent_state to browser
- Latency stamps tSttFinal/tTtsFirst recorded per turn and shown as badges in Live Call
- Echo turn happens in Python only (java_link arrives Phase 3); transcript lines still POSTed to Java for live feed + persistence

**Done when:** demo: speak three sentences, each echoed back audibly <2 s (GCP mode) with transcript + latency badges in panel; unplugging GCP creds path switches to fallback providers and calls still work.

## Phase 3 — Brain in the loop: LLM providers, turn WS, English conversation
**Ships:** Real conversation in English grounded in the active business's KB/persona from the DB. Per-call WS Python⇄Java; reply sentences stream to TTS as they complete. Provider swap works: Settings gemini⇄openai changes which API answers the next call. Full call persisted (messages, stamps).
**Touches:** brain/{ConversationBrain, CallSession, CallRegistry, PromptBuilder}, brain/llm/* (all six files), services/{KbService, CallLogService}, api/{TurnSocket, CallController full}, python-voice/java_link.py, session.py (wire java_link, replace echo), static/js/pages/live_call.js (mode/latency polish). Deletes v1 api/{CallContextController, CallContextResponse, ChatMessageController, TranscriptController}.
**Depends on:** Phase 2.

- LlmProvider interface + Gemini/OpenAI REST streaming impls + LlmRouter (override → global); keys from config; placeholder → polite refusal line + toast
- PromptBuilder v1: persona, about/services/policies/faqs, hours, language directive, AI-disclosure greeting mandate
- Sentence assembly from deltas; say{seq,…} streamed per sentence; tLlmFirst stamped
- History window = ai_settings.max_history_turns; PII masking deferred to Phase 6 (note in prompt TODO ledger, not code comments)

**Done when:** demo: ask Template Business about its services and hours — correct grounded answers; flip provider in Settings and repeat on the other API; call_message rows carry all three stamps; median turn <2 s with real keys.

## Phase 4 — Screening + bilingual: modes, language select, Bangla, inactivity
**Ships:** Greeting asks language (EN/BN); whole call runs in the chosen language, mid-call switch via tool. Four-way screening live: existing/new/spam/complex with visible mode badges + transitions; polite spam termination hangs up; complex mode announces human follow-up and goes quiet-ish; inactivity warn-then-hangup. Manual mode override dropdown works.
**Touches:** brain/{CallModeMachine, InactivityWatchdog}, brain/tools/{ToolRegistry, ToolExecutor} (set_mode, set_language, end_call first), ConversationBrain (tool loop), PromptBuilder (mode instructions), utils/Lang (all BN strings), pipeline/stt_gcp.py + tts_gcp.py (bn-BD/bn-IN paths), session.py (set_language handling), api/CallController (mode override), live_call.js (badges). Deletes ai/CallMode.java (absorbed).
**Depends on:** Phase 3.

- Mode transitions per BUILD_SPEC legality table, all persisted to mode_transition with reason
- WRONG_NUMBER: farewell line then hangup{}; COMPLEX: gather-details instruction set
- Banglish handling: STT hint + fallback re-prompt line (Lang) when confidence low/empty twice

**Done when:** scripted run of all four scenarios in either language shows correct mode badge + termination behavior; silence for warn/hangup thresholds triggers both steps; transcript renders Bangla script correctly.

## Phase 5 — CRM + full editability: clients, onboarding, admin pages
**Ships:** Existing-customer calls (client code/phone) greet by name and use notes/past issues; new-customer calls capture name+phone+request and create the DB record via tool call, visible immediately in panel. Every admin surface editable: business CRUD, KB tab-strip editor, persona/prompt fields, clients, hours, escalation contacts — your "full JSON freedom" requirement, in-app. Console UI removed.
**Touches:** services/ClientService, brain/tools/ToolExecutor (+lookup_client, create_client, log_request), PromptBuilder (client context block), api/{ClientController, KbController, AiSettingsController}, static/js/pages/{businesses rw, business_editor, clients}.js, components (dialog forms, inline-error, destructive-confirm on deletes). Deletes utils/{ConsoleTerminal, Console}.
**Depends on:** Phase 4.

- Client contact fields encrypted (pgp_sym); panel shows decrypted via service
- Editor validation: inline-error per field, toast on save, sort_order drag or up/down for KB
- start-call with client code from Live Call page (dropdown of active business's clients)

**Done when:** demo: edit Template Business KB in panel → next call answers with the edit; call as C001 → greeted by name with history referenced; call as stranger → record created and visible without refresh… all with no console.

## Phase 6 — Handoff, logging, summaries, PII, metrics
**Ships:** Complex-request calls end with an escalation email (real SMTP or logged no-op on placeholders) containing an LLM summary; every call gets summary + structured JSON; Call History page with transcript viewer + export .txt; PII masking active before LLM and summaries; Dashboard with stat-tiles (calls today, median latency, mode distribution) + recent calls.
**Touches:** services/{PostCallService, MailService, MetricsService}, utils/PiiMasker, brain/tools (escalate_to_human), api/{CallHistoryController, MetricsController}, static/js/pages/{history, dashboard}.js.
**Depends on:** Phase 5.

- Summary prompt returns {summary_text, structured{caller, intent, outcome, mode_path}, action_items[]} — parsed, stored
- PiiMasker unit-tested against NID/phone/email/balance samples (both scripts)
- Metrics from DB only (no in-memory counters) so restarts don't lie

**Done when:** complex scenario produces email (or WARN no-op) with correct summary; history shows past calls incl. Phase 2–5 ones; masking visibly replaces a spoken NID in the stored prompt log; dashboard numbers match DB queries.

## Phase 7 — Twilio mode, second business, polish
**Ships:** Twilio Device calling end-to-end when real credentials + public_media_url are set: token endpoint, TwiML webhook pointing Media Streams at python-voice, μ-law transcoding — same pipeline after the transport. Placeholder-guarded everywhere (button disabled + tooltip until configured). Second seeded business (different vertical) proves config-only onboarding. Latency tuning pass, demo script, README rewrite.
**Touches:** api/TwilioController, python-voice/transports/twilio_ws.py, static/js/twilio_mode.js, V3__seed_demo_two.sql, docs/SETUP.md (Twilio + ngrok walkthrough filled in), README.md.
**Depends on:** Phase 6.

- TwiML: <Connect><Stream url="wss://{public_media_url}/ws/twilio"/> with callId param
- Trial-account caveats documented (verified numbers, "trial" preamble)
- Tuning: measure stage timings from call_message stamps, adjust (VAD tail, sentence min length, TTS chunk size); record before/after in log

**Done when:** with real Twilio config: browser Twilio call completes a full scenario through the same brain; without: button disabled, no errors anywhere; switching active business to demo-two changes greeting/KB/persona with zero code edits; final test script covers the proposal's four demo scenarios start-to-finish.

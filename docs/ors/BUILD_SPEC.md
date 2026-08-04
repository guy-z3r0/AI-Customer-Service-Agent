# BUILD SPEC — AI Customer Service Agent v2

## Target
- Java: 21 (existing). Build: Maven, `spring-boot-starter-parent` 3.4.4 (bump from 3.2.4). Package root: `com.ulab.agent` (existing, keep).
- Python: 3.10+. Run: `uvicorn server:app --port 8090`.
- Entry points: `com.ulab.agent.Main` (Spring Boot, port 8080) · `python-voice/server.py` (FastAPI, port 8090).
- Output artifacts: `java-backend/target/java-backend-2.0.0.jar` · `python-voice/` source run · static panel served from jar at `/`.
- Orchestration: `docker-compose.yml` — services `postgres` (16-alpine, port 5432, volume `pgdata`), `java-backend`, `python-voice`. Host-run (mvn/uvicorn) equally supported; SETUP.md documents both.
- Mode: PHASED. 7 phases in phases.md. Stop after each phase: write log + test script, update architecture.md + PROJECT_STATE.md, wait.
- Evolution not greenfield: keep git history; delete replaced v1 files in the phase that replaces them (listed per phase). Console UI (`ConsoleTerminal`, `Console`) removed Phase 5; `data/` JSON tree retired after Phase 1 import (keep on disk, stop reading).

## Structure
```
AI-Customer-Service-Agent/
├── CLAUDE.md  STYLE-CONTRACT.md  README.md  .env.example  docker-compose.yml  .gitignore
├── docs/SETUP.md
├── docs/ors/{proposal,BUILD_SPEC,phases,application_brief,architecture,PROJECT_STATE}.md
├── docs/ors/logs/
├── secrets/gcp-credentials.json.PLACEHOLDER      (gitignored dir, placeholder committed)
├── java-backend/
│   ├── pom.xml   Dockerfile
│   └── src/main/
│       ├── java/com/ulab/agent/
│       │   ├── Main.java
│       │   ├── api/         (REST controllers, WS endpoints, DTOs, advice)
│       │   ├── brain/       (conversation core)
│       │   ├── brain/llm/   (provider abstraction)
│       │   ├── brain/tools/ (function-calling tools)
│       │   ├── services/    (domain services)
│       │   ├── domain/      (JPA entities + enums)
│       │   ├── repo/        (Spring Data repositories)
│       │   └── utils/       (Lang, PiiMasker, TimeUtils, JsonUtils)
│       └── resources/
│           ├── application.yml
│           ├── db/migration/ (V1__baseline.sql, V2__seed.sql, V3__seed_demo_two.sql @Ph7)
│           └── static/       (panel: index.html, css/, js/, js/pages/, js/audio/)
└── python-voice/
    ├── requirements.txt   Dockerfile   server.py   session.py   config.py   audio.py   java_link.py
    ├── transports/{browser_ws.py, twilio_ws.py}
    └── pipeline/{vad.py, stt_gcp.py, stt_fallback.py, tts_gcp.py, tts_fallback.py, providers.py}
```

## Files
Max lines: hard 500, target 300 (all files). Listed = must exist at project end; phase column = created in.

| Path (java-backend/src/main/java/com/ulab/agent/) | Purpose | Ph |
|---|---|---|
| Main.java | Boot entry, enables scheduling + WS | 1 |
| api/BusinessController.java | CRUD /api/businesses + activate | 1 |
| api/KbController.java | CRUD /api/businesses/{id}/kb | 5 |
| api/ClientController.java | CRUD /api/businesses/{id}/clients | 5 |
| api/AiSettingsController.java | GET/PUT /api/businesses/{id}/ai-settings | 5 |
| api/ConfigController.java | GET/PUT /api/config (masked secrets in GET) | 1 |
| api/CallController.java | POST /api/call/start, POST /api/call/{id}/mode, POST /api/call/{id}/end | 3 |
| api/CallHistoryController.java | GET /api/calls, /api/calls/{id}, /api/calls/{id}/export | 6 |
| api/MetricsController.java | GET /api/metrics/summary (latency percentiles, mode counts) | 6 |
| api/HealthController.java | GET /api/health (db, python, provider key presence) | 1 |
| api/ImportController.java | POST /api/import/legacy — one-shot data/ JSON → DB | 1 |
| api/TwilioController.java | GET /api/twilio/token, POST /api/twilio/voice (TwiML) — placeholder-guarded | 7 |
| api/TurnSocket.java | WS /ws/turn/{callId} — python link, routes to ConversationBrain | 3 |
| api/LiveEventSocket.java | WS /ws/live — fan-out transcript/mode/latency/health events to panel | 2 |
| api/WsConfig.java | WS endpoint registration | 2 |
| api/ApiExceptionAdvice.java | @RestControllerAdvice → {error, detail}, 400/404/409/500 | 1 |
| api/dto/ (≤6 files) | Request/response records only where entity shape differs | 1+ |
| brain/ConversationBrain.java | Turn lifecycle per call; owns history (last 20 turns), latency stamps | 3 |
| brain/CallModeMachine.java | Modes NEW_CUSTOMER, EXISTING_CUSTOMER, WRONG_NUMBER, COMPLEX_REQUEST + transitions, per-mode instruction text (from Lang) | 4 |
| brain/PromptBuilder.java | System prompt: persona + business KB + client record + hours + mode instructions + language directive + tool guidance | 3 |
| brain/CallSession.java | Per-call state: mode, language, client, history, timestamps, telephony kind | 3 |
| brain/CallRegistry.java | Active sessions by callId | 3 |
| brain/InactivityWatchdog.java | @Scheduled sweep; warn at T, hangup at 2T (config) | 4 |
| brain/llm/LlmProvider.java | Interface: `id()`, `chatStream(LlmRequest, LlmStreamHandler)` | 3 |
| brain/llm/LlmRequest.java | model, system, messages, toolSchemas, temperature | 3 |
| brain/llm/LlmStreamHandler.java | Callbacks: onTextDelta, onToolCall(name,argsJson), onDone, onError | 3 |
| brain/llm/GeminiProvider.java | REST streamGenerateContent; tools→functionDeclarations; SSE parse | 3 |
| brain/llm/OpenAiProvider.java | REST chat/completions stream:true; tools→functions; SSE parse | 3 |
| brain/llm/LlmRouter.java | provider+model from ai_settings override else app_config; builds provider with key | 3 |
| brain/tools/ToolRegistry.java | Tool JSON schemas (provider-neutral) | 4 |
| brain/tools/ToolExecutor.java | Dispatch: lookup_client, create_client, log_request, escalate_to_human, set_mode, set_language, end_call → services; returns result JSON | 4 |
| services/BusinessService.java | Business CRUD + active business + hours | 1 |
| services/KbService.java | KB entries CRUD, ordered fetch by kind | 3 |
| services/ClientService.java | Client CRUD, lookup by code/phone (decrypt), past-issue append | 5 |
| services/ConfigService.java | app_config typed access, placeholder detection (`isPlaceholder(key)`), secret masking | 1 |
| services/CallLogService.java | Persist call, messages, transitions, summary; export txt | 3 |
| services/PostCallService.java | On end: summary via LlmRouter, escalation email if COMPLEX, persist | 6 |
| services/MailService.java | Spring Mail SMTP from config; no-op with WARN if placeholders | 6 |
| services/LegacyImportService.java | Parse java-backend/data/**→ entities (idempotent, skip existing slugs) | 1 |
| services/MetricsService.java | Percentiles from call_message timestamps; mode counts from transitions | 6 |
| domain/Business.java | entity: id, slug, name, phone, email, address, timezone, hoursJson, active, createdAt | 1 |
| domain/AiSettings.java | entity: businessId(pk), personaName, roleDescription, replyStyle, greetingEn, greetingBn, providerOverride, modelOverride, temperature, maxHistoryTurns | 1 |
| domain/KbEntry.java | entity: id, businessId, kind, question, content, sortOrder | 1 |
| domain/Client.java | entity: id, businessId, clientCode, name, phoneEnc, emailEnc, notes, pastIssuesJson, createdAt | 1 |
| domain/EscalationContact.java | entity: id, businessId, name, email, priority | 1 |
| domain/CallRecord.java | entity: id, businessId, clientId?, startedAt, endedAt, finalMode, finalLanguage, telephony, terminationReason | 1 |
| domain/CallMessage.java | entity: id, callId, seq, role, text, language, modeAtTime, tSttFinal, tLlmFirst, tTtsFirst | 1 |
| domain/ModeTransition.java | entity: id, callId, fromMode, toMode, reason, at | 1 |
| domain/CallSummary.java | entity: callId(pk), summaryText, structuredJson, actionItemsJson, generatedAt | 1 |
| domain/AppConfigEntry.java | entity: key(pk), valueJson | 1 |
| domain/enums (CallMode, MessageRole, KbKind, Telephony, Language) | one file each, ≤30 lines | 1 |
| repo/*Repository.java | one Spring Data interface per entity | 1 |
| utils/Lang.java | ALL user-facing strings EN+BN incl. greetings, mode instructions, warnings (evolve existing) | 1 |
| utils/PiiMasker.java | Regex masks: NID (10/13/17 digit), phone, email, money amounts → `[MASKED_*]`; applied to caller text before LLM + before summary | 6 |
| utils/TimeUtils.java, utils/JsonUtils.java | existing helpers, keep | — |

Deleted: utils/ConsoleTerminal.java + utils/Console.java (Ph5) · managers/* replaced by services/* (Ph1) · models/* replaced by domain/* + dto (Ph1) · api/CallContextController + CallContextResponse + ChatMessageController + TranscriptController + old ConfigController body (Ph3, superseded by TurnSocket flow) · ai/CallMode.java moves to brain/CallModeMachine + domain enum (Ph4).

| Path (resources/static/) | Purpose | Ph |
|---|---|---|
| index.html | SPA shell: side-rail, top-bar, status-bar, page mount | 1 |
| css/nocturne.css | CSS custom properties transcribed from STYLE-CONTRACT.md tokens (colors, spacing, radius, type ramp) — single source, no other hex anywhere | 1 |
| css/parts.css | Contract parts as classes (.panel, .card, .stat-tile, .table, .list-row, .button--primary/secondary/ghost/destructive, .text-field, .dropdown, .dialog, .toast, .tag-badge, .key-value, .inline-error, .tab-strip, .scroll-region…) | 1 |
| js/app.js | Hash router, shell wiring, active business selector | 1 |
| js/api.js | fetch wrapper, error→toast | 1 |
| js/ws.js | LiveEventSocket client, auto-reconnect | 2 |
| js/components.js | dialog/confirm/destructive-confirm/toast/table builders | 1 |
| js/pages/dashboard.js | stat-tiles + recent calls table | 6 |
| js/pages/live_call.js | dial card (Browser call primary, Twilio call secondary, End destructive), transcript list-rows + tag-badges, key-value facts, latency badges, manual mode override dropdown | 2 |
| js/pages/businesses.js | table + create/edit dialogs + activate | 1(ro) 5(rw) |
| js/pages/business_editor.js | tab-strip: About/Services/Policies/FAQs/Persona/Hours&Escalation | 5 |
| js/pages/clients.js | table + dialogs + past issues | 5 |
| js/pages/history.js | calls table + detail panel (transcript + summary + export) | 6 |
| js/pages/settings.js | key-value forms: LLM provider/model/keys, GCP path, TTS tuning, SMTP, Twilio, timeouts; placeholder highlighting (amber tag-badge "PLACEHOLDER") | 1 |
| js/audio/mic_stream.js | getUserMedia + AudioWorklet → 16 kHz 16-bit PCM frames → WS binary | 2 |
| js/audio/player.js | queue + play agent PCM/WAV chunks; emits speaking state for mic gate UI | 2 |
| js/twilio_mode.js | lazy-load Twilio SDK from CDN, Device setup via /api/twilio/token; disabled+tooltip when placeholders | 7 |

| Path (python-voice/) | Purpose | Ph |
|---|---|---|
| server.py | FastAPI app, WS route registration, /health, session registry | 2 |
| session.py | Per-call orchestrator: transport ⇄ pipeline ⇄ java_link; half-duplex mic gate; latency stamping (tSttFinal, tTtsFirst); barge-in policy = agent finishes sentence, queue cleared on hangup | 2 |
| config.py | GET java /api/config at call start; env fallback; provider selection logic | 2 |
| audio.py | resample 48k→16k, PCM16 helpers, μ-law encode/decode (audioop), WAV wrap | 2 |
| java_link.py | WS client to /ws/turn/{callId}; reconnect ×3 then end call with apology line | 3 |
| transports/browser_ws.py | /ws/browser/{callId}: binary PCM in, JSON control + binary audio out | 2 |
| transports/twilio_ws.py | /ws/twilio: Media Streams JSON protocol, μ-law 8k ⇄ internal PCM16 16k | 7 |
| pipeline/vad.py | webrtcvad frames (30 ms), endpointing: 600 ms silence = utterance end | 2 |
| pipeline/providers.py | STT/TTS provider registry + fallback chain (gcp → legacy), chosen from config + credential presence | 2 |
| pipeline/stt_gcp.py | streaming_recognize, model latest_short, language per session (en-US / bn-BD), interim results forwarded | 2 |
| pipeline/stt_fallback.py | SpeechRecognition recognize_google on VAD-segmented WAV (v1 parity) | 2 |
| pipeline/tts_gcp.py | synthesize per sentence; voices from config (`tts_voice_en` default en-US-Neural2-C, `tts_voice_bn` default bn-IN-Standard-A); rate/volume from config | 2 |
| pipeline/tts_fallback.py | pyttsx3 → WAV bytes (offline, v1 parity) | 2 |

Deleted Ph2: stt_sender.py, tts_speaker.py, ai_agent.py (LLM moved to Java), old config.py body.

## Dependencies
| Coordinate | Version | Purpose |
|---|---|---|
| org.springframework.boot:* (web, websocket, data-jpa, validation, mail) | parent 3.4.4 | HTTP, WS, ORM, validation, SMTP |
| org.postgresql:postgresql | 42.7.4 | driver |
| org.flywaydb:flyway-core + flyway-database-postgresql | Boot-managed | migrations |
| com.google.code.gson:gson | 2.10.1 | LLM payloads (existing) |
| fastapi | 0.115.6 | WS server |
| uvicorn[standard] | 0.32.1 | ASGI runtime |
| google-cloud-speech | 2.28.0 | STT |
| google-cloud-texttospeech | 2.21.1 | TTS |
| webrtcvad | 2.0.10 | VAD |
| numpy | 1.26.4 | resampling |
| requests, websockets | 2.32.3, 13.1 | java link, http |
| SpeechRecognition, pyttsx3 | 3.10.4, 2.90 | fallback providers |
| Twilio JS SDK (CDN, lazy) + twilio (py) | pin at Ph7 install | optional telephony |
Verify each pin at its phase's install; correct in-place if registry disagrees, note in phase log. No other dependencies without a log entry justifying them.

## Data model (Flyway V1__baseline.sql)
`CREATE EXTENSION IF NOT EXISTS pgcrypto`. All ids uuid default gen_random_uuid(). FKs cascade on business delete (confirm dialog in UI).
business(id, slug uq, name, phone, email, address, timezone, hours_json jsonb, active bool, created_at) · ai_settings(business_id pk/fk, persona_name, role_description, reply_style, greeting_en, greeting_bn, provider_override null, model_override null, temperature numeric default 0.7, max_history_turns int default 20) · kb_entry(id, business_id fk, kind check in(about,service,policy,faq), question null, content, sort_order) · client(id, business_id fk, client_code uq-per-business, name, phone_enc bytea, email_enc bytea, notes, past_issues_json jsonb, created_at) — phone/email via pgp_sym_encrypt(key = env `PII_ENC_KEY`) · escalation_contact(id, business_id fk, name, email, priority int) · call_record(id, business_id fk, client_id fk null, started_at, ended_at null, final_mode, final_language, telephony check in(browser,twilio), termination_reason) · call_message(id, call_id fk, seq int, role check in(caller,agent,system), text, language, mode_at_time, t_stt_final timestamptz null, t_llm_first timestamptz null, t_tts_first timestamptz null) · mode_transition(id, call_id fk, from_mode, to_mode, reason, at) · call_summary(call_id pk/fk, summary_text, structured_json jsonb, action_items_json jsonb, generated_at) · app_config(key text pk, value_json jsonb).

## Config & placeholders (V2__seed.sql + .env.example)
Placeholder convention: string values `PLACEHOLDER_<NAME>`; `ConfigService.isPlaceholder` = startsWith("PLACEHOLDER_"). Panel Settings shows amber PLACEHOLDER badge per unset key; features degrade, never crash: LLM keys placeholder → call refuses politely with system line + toast; GCP placeholder → fallback providers; Twilio placeholders → Twilio button disabled with tooltip; SMTP placeholders → escalation logged not emailed (WARN + panel toast).
app_config seed keys: llm_provider="gemini", llm_model="gemini-2.0-flash", gemini_api_key, openai_api_key, openai_model_default="gpt-4o-mini", gcp_credentials_path="./secrets/gcp-credentials.json", stt_provider="auto", tts_provider="auto", tts_voice_en, tts_voice_bn, tts_rate=170, tts_volume=1.0, default_language="en", inactivity_warn_s=20, inactivity_hangup_s=40, twilio_account_sid, twilio_auth_token, twilio_api_key_sid, twilio_api_key_secret, twilio_twiml_app_sid, twilio_caller_number, public_media_url (ngrok, for Twilio streams), smtp_host, smtp_port=587, smtp_username, smtp_password, smtp_from — all credentials seeded as PLACEHOLDER_*.
.env.example: POSTGRES_DB=agent, POSTGRES_USER=agent, POSTGRES_PASSWORD=PLACEHOLDER_DB_PASSWORD, DB_URL, JAVA_PORT=8080, PY_PORT=8090, PII_ENC_KEY=PLACEHOLDER_PII_ENC_KEY, GOOGLE_APPLICATION_CREDENTIALS=./secrets/gcp-credentials.json.
Seed businesses: slug `template-business` (name "Template Business", full starter KB with obviously-editable sample text, persona, hours, escalation contact placeholder email, clients C001/C002 with sample notes + past issues) and Ph7 adds slug `demo-two` (different vertical, proves config-only onboarding). docs/SETUP.md walks: .env → compose up → open panel → Settings (paste keys) → edit Template Business → test call → optional GCP/Twilio/SMTP sections.

## Contracts (signatures only where non-obvious)
- ConversationBrain: `onCallStart(CallSession)`, `onTranscriptFinal(callId, text, lang, tSttFinal)`, `onCallEnd(callId, reason)`. Flow per turn: PiiMasker.mask → history append → PromptBuilder.build(session) → LlmRouter.provider(session).chatStream → deltas assembled into sentences → TurnSocket.say(seq,…) as each sentence completes (streamed, not batched) → tool calls via ToolExecutor then continuation request → persist via CallLogService.
- CallModeMachine: `initialMode(hasClientId)`, `apply(session, toolModeRequest) → ModeTransition|reject`. Legal: NEW→any, EXISTING→COMPLEX|WRONG, COMPLEX/WRONG terminal (COMPLEX allows farewell turns; WRONG hangs up after farewell).
- LlmProvider impls: request timeout 30 s; on stream error mid-turn → one retry, then Lang.error line spoken + system message logged. Tool schema JSON identical for both providers; provider classes translate (Gemini functionDeclarations / OpenAI tools[].function).
- TurnSocket messages (JSON): py→java `call_start{telephony, languageHint, clientCode?}`, `transcript_partial{text}`, `transcript_final{text, language, tSttFinal}`, `call_end{reason}` · java→py `greeting{text, language}`, `say{seq, text, language, last}`, `set_language{language}`, `hangup{reason, farewellText}`.
- Browser WS: binary = PCM16 16 kHz mono frames (mic up / agent audio down); JSON text frames `start{language}`, `agent_state{speaking}`, `error{code,msg}`.
- LiveEventSocket events: `call_started, line{role,text,lang,mode}, partial, mode_change, language_change, latency{turnSeq, sttMs, llmMs, ttsMs, totalMs}, call_ended, health`.
- Latency stamps: Python owns tSttFinal + tTtsFirst (first audio byte queued to transport), Java owns tLlmFirst (first delta). total = tTtsFirst − tSttFinal; target <2000 ms; every turn's stamps persisted on call_message.

## Error handling
- Strategy: unchecked domain exceptions; validation at controller boundary (jakarta validation); external calls (LLM, GCP, SMTP, Twilio) wrapped with one retry + typed fallback as defined in Config section. No empty catch blocks anywhere.
- Logging: Java SLF4J/Logback INFO default, MDC key `callId`; Python stdlib logging, per-session `[callId]` prefix. No secrets or unmasked PII in logs.
- User-facing: panel toast (4 s) + inline-error on fields; call-facing: Lang error lines spoken, never silence.

## Naming glossary
| Abbreviation | Full name |
|---|---|
| kb | knowledge base |
| stt / tts / vad | speech-to-text / text-to-speech / voice activity detection |
(no abbreviated class names currently; add here if any name exceeds ~25 chars)

## Constraints
- Files ≤500 lines hard, target 300. Methods ≤50. Nesting ≤3. Comments explain why/how above non-obvious methods only.
- Panel colors/spacing/type: only var() from css/nocturne.css, which mirrors STYLE-CONTRACT.md. Contract drift checklist run at every phase close that touched the panel.
- All user-facing strings (both languages) live in utils/Lang.java (Java) and are delivered to Python/panel via API — no hardcoded English in Python or JS beyond dev logs.
- Bind 8080/8090 to localhost by default (compose maps to host). No auth layer (non-goal).
- Existing v1 behavior preserved as fallback path (free STT + pyttsx3) — never deleted.

## Phase index
phases.md — 7 phases. Load only the current phase. PROJECT_STATE.md says which.

## Definition of done
- [ ] `docker compose up` from clean clone + `.env` from example → panel at :8080, health all green (placeholders amber)
- [ ] Browser call: greeting → conversation → hangup, median turn latency <2000 ms shown in panel (with real GCP+LLM keys)
- [ ] All four scenarios pass per phase_04–06 test scripts (existing, new, spam, complex incl. escalation email or logged no-op)
- [ ] Bangla call end-to-end: select Bangla at greeting, converse, transcript in Bangla script
- [ ] Provider swap: Settings gemini→openai mid-session, next call uses OpenAI (visible in health + logs)
- [ ] Full editability: business, KB, persona, prompts, clients, hours, escalation, all keys editable in panel; changes affect next call without restart
- [ ] Legacy import: v1 data/businesses/* appear in panel after one-shot import
- [ ] Twilio mode works when real credentials + public_media_url set; cleanly disabled otherwise
- [ ] Every phase has log + test script in docs/ors/logs/; architecture.md matches built reality; contract checker passes

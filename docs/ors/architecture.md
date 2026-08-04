# Architecture — AI Customer Service Agent v2

Target architecture (approved scope). Regenerated at every phase close; nodes not yet built
are the plan, not the state — PROJECT_STATE.md says which phase is real.

**Built as of Phase 7 — everything below is real.** The Data layer (Postgres, Flyway, JPA
repositories), every domain service — businesses, config, knowledge, customers, call logging,
call history, summaries, email and metrics — the REST controllers over all of them, both call
transports (browser microphone and Twilio Media Streams) with VAD, speech recognition and
synthesis behind them, the conversation itself (ConversationBrain, TurnRunner, PromptBuilder,
the swappable LLM layer, the per-call turn websocket), the screening over it (CallModeMachine,
seven tools, the bilingual greeting, InactivityWatchdog), what happens after it (PiiMasker,
PostCallService, MailService), two seeded businesses in different trades, and the whole panel:
Dashboard, Live Call, Businesses, the six-tab business editor, Clients, Call History and
Settings.

Nothing is left as plan. What is left is verification: `docs/ors/PROJECT_STATE.md` lists what
has run against a live stack and what still needs a credential, a microphone or Docker.

```mermaid
graph TD
    Panel["Control Panel SPA<br/>operate + configure everything"]
    CallClient["Live Call page<br/>browser mic, dual-mode dial"]

    subgraph PythonVoice["python-voice"]
        Transports["Transports<br/>browser WS + Twilio streams"]
        Pipeline["AudioPipeline<br/>VAD → STT stream → TTS chunks"]
    end

    subgraph JavaBackend["java-backend"]
        RestApi["REST controllers<br/>CRUD + call control"]
        LiveFeed["LiveEventSocket<br/>transcript + events to panel"]
        Brain["ConversationBrain<br/>turns, LLM calls, tool dispatch"]
        ModeMachine["CallModeMachine<br/>4-way screening"]
        PromptBuilder["PromptBuilder<br/>business + client context"]
        PostCall["PostCallService<br/>LLM summary + email handoff"]
        DomainSvc["Domain services<br/>business, client, call, config"]
        PiiMasker["PiiMasker<br/>regex scrub before LLM"]
    end

    subgraph Data
        Repos["JPA repositories"]
        PG[("PostgreSQL<br/>Flyway schema, pgcrypto")]
    end

    Gemini["LLM APIs<br/>Gemini + OpenAI, swappable"]
    GCP["Google Cloud STT / TTS"]
    Twilio["Twilio Voice"]
    SMTP["SMTP email"]

    Panel -->|REST| RestApi
    LiveFeed -->|live transcript, mode, latency| Panel
    CallClient -->|mic audio WS| Transports
    CallClient -.->|Twilio Device mode| Twilio
    Twilio -.->|Media Streams| Transports
    Transports --> Pipeline
    Pipeline -->|final transcript, per-call WS| Brain
    Brain -->|streamed reply sentences| Pipeline
    Pipeline --> GCP
    Brain --> ModeMachine
    Brain --> PromptBuilder
    PromptBuilder --> DomainSvc
    Brain --> PiiMasker
    PiiMasker --> Gemini
    Brain -->|call end| PostCall
    PostCall --> Gemini
    PostCall --> SMTP
    RestApi --> DomainSvc
    DomainSvc --> Repos
    Repos --> PG
    Brain --> LiveFeed

    classDef ui fill:#144F63,stroke:#4DD4FF,stroke-width:2px,color:#FFFFFF
    classDef core fill:#6B4A12,stroke:#FFC24D,stroke-width:2px,color:#FFFFFF
    classDef data fill:#3D2A6B,stroke:#7C5CFF,stroke-width:2px,color:#FFFFFF
    classDef external fill:#3A3F4A,stroke:#9AA5B4,stroke-width:2px,color:#FFFFFF
    classDef util fill:#6B2438,stroke:#FF5C7A,stroke-width:2px,color:#FFFFFF

    class Panel,CallClient ui
    class Transports,Pipeline,RestApi,LiveFeed,Brain,ModeMachine,PromptBuilder,PostCall,DomainSvc core
    class Repos,PG data
    class Gemini,GCP,Twilio,SMTP external
    class PiiMasker util
```

## How it fits together

You open the panel at `localhost:8080` and hit "Start browser call" on the Live Call page.
The browser captures your mic and streams PCM frames over a WebSocket to the Python voice
server (port 8090). VAD watches the stream; when you stop talking, the streaming STT result
is finalized and sent up the per-call WebSocket to the Java brain. The brain runs the mode
machine, builds a prompt from the active business's knowledge, clients and persona (all read
from Postgres), scrubs PII, and calls the configured LLM (Gemini or OpenAI — LlmRouter
decides from Settings) with the tool schema. Reply text streams back
sentence by sentence; Python converts each sentence to TTS audio and plays it into the call
while the mic stays gated. Every line, mode switch and timestamp is echoed to the panel over
LiveEventSocket, so the transcript scrolls live with latency badges per turn.

In Twilio mode the front half changes and nothing else does: the Live Call page uses the
Twilio Device SDK, Twilio dials in, and its Media Streams WebSocket lands on the same
transport layer.

When the call ends — hangup, spam termination, or inactivity — the brain hands the finished
transcript to PostCallService, which asks Gemini for a structured summary, emails the
escalation contact if the mode was complex-request, and writes call, messages, timestamps
and summary to Postgres. The history page reads them back.

## Who owns what

| Component | Owns |
|---|---|
| ConversationBrain | Turn lifecycle: transcript in → mode check → prompt → LLM → tool dispatch → reply out |
| LlmRouter + providers | Picking provider/model from config (business override → global); Gemini + OpenAI REST clients behind one interface |
| CallModeMachine | The four screening states, legal transitions, per-mode instructions |
| PromptBuilder | Assembling system prompt from business KB, client record, persona, hours |
| Domain services | CRUD + validation for businesses, KB entries, clients, calls, config |
| PostCallService | Summaries, escalation email, final persistence |
| PiiMasker | Regex scrub of NIDs, balances, etc. before anything leaves for Gemini |
| AudioPipeline (py) | VAD, STT streaming, TTS synthesis, provider fallback, mic gating |
| Transports (py) | Browser WS framing + Twilio Media Streams framing → one internal format |
| LiveEventSocket | Fan-out of transcript/mode/latency events to the panel |
| Panel SPA | All operator screens, styled solely from STYLE-CONTRACT.md tokens |

## What exists after Phase 1

```
docker compose up
  ├── postgres:16-alpine ── volume pgdata
  └── java-backend  ── Flyway V1 (schema) + V2 (Template Business, 27 config keys)
                    ── serves the panel and the REST API on :8080
```

| Layer | Built in Phase 1 |
|---|---|
| `domain/` | 10 entities, 5 enums — the whole target schema, not just what Phase 1 reads |
| `repo/` | one Spring Data interface per entity |
| `services/` | BusinessService (list, CRUD, activate), ConfigService (typed reads, placeholder detection, secret masking), LegacyImportService (v1 JSON → DB, idempotent) |
| `api/` | BusinessController, ConfigController, HealthController, ImportController, LangController, ApiExceptionAdvice, 5 dto records |
| `static/` | shell + Businesses (read-only) + Settings; four sections render an empty-state naming their phase |

Three rules set here that later phases inherit:

- **Placeholders never block boot.** Every credential is a `PLACEHOLDER_*` string in
  `app_config`. `ConfigService.isPlaceholder(key)` is how a feature decides to switch itself
  off politely. Nothing in the app may hard-require a credential to start.
- **Secrets are masked at the boundary.** `GET /api/config` returns `••••1234` for a secret,
  never the value. A secret submitted blank keeps what is stored, so an ordinary save cannot
  destroy a key.
- **The panel holds no words.** Every string comes from `utils/Lang.java` over
  `GET /api/lang?lang=en|bn`. A wording or translation change touches one Java file.

The JSON tree under `java-backend/data/` is now read-only history: it is imported once at
start-up and nothing else reads it.

## What Phase 2 added

```
docker compose up
  ├── postgres:16-alpine ── volume pgdata
  ├── java-backend  ── panel + REST + /ws/live          :8080
  └── python-voice  ── call audio, VAD, speech          :8090
```

One turn, end to end:

```
mic ─48k─▶ AudioWorklet ─16k PCM16─▶ ws /ws/browser/{callId} ─┬─▶ Endpointer ─600ms silence─┐
                                                              └─▶ SttStream ────final text──┤
   speakers ◀──── PCM16 chunks ◀──── TtsProvider ◀──── reply ◀─────────────────────────────┘
                                          │
                       POST /api/call/{id}/line ─▶ CallLogService ─┬─ call_message row
                                                                   └─ /ws/live ─▶ panel
```

| Layer | Built in Phase 2 |
|---|---|
| `python-voice/` | `server.py`, `session.py` (turn-taking, half-duplex gate, latency stamps), `config.py`, `audio.py`, `java_link.py`, `transports/browser_ws.py`, `pipeline/{vad, providers, stt_gcp, stt_fallback, tts_gcp, tts_fallback}.py`, `tests/` |
| `api/` | CallController (start / line / end), LiveEventSocket, WsConfig, CallDtos |
| `services/` | CallLogService — the transcript, its timings, and the live broadcast |
| `static/js/` | `ws.js`, `audio/{mic_stream, mic_worklet, player}.js`, `pages/live_call.js` |

Four rules set here that later phases inherit:

- **Audio never touches Java, text never touches the audio socket.** The microphone talks
  straight to the voice server; the transcript comes back through the backend, which is also
  what writes it down. Anything added between a caller and a recogniser is latency.
- **Providers pick themselves.** Google Cloud when a real credentials file exists, the free
  offline pair otherwise. Named-but-broken degrades to free with a warning; a call never
  fails to start over a missing key.
- **The agent finishes its sentence, and never hears itself.** Incoming audio is discarded
  while the agent speaks — on the server, not just in the page — and a caller talking over
  the agent does not cut it off.
- **Timings are per turn, not averaged.** `tSttFinal` and `tTtsFirst` are stored on every
  agent line, so a slow reply can be traced to a stage instead of guessed at. Phase 3 filled
  in `tLlmFirst` between them.

Internally the voice server speaks one audio format everywhere: 16 kHz, 16-bit, mono PCM.
Transports convert on the way in and out, so nothing downstream asks what shape the bytes are.

## What Phase 3 added

The conversation moved to Java. The voice server no longer decides anything a caller hears; it
carries audio, recognises speech, and speaks whatever the brain sends it.

```
                    ws /ws/turn/{callId}          one socket per call, text only
python-voice ─────────────────────────────────────────▶ java-backend
   transcript_final{text, tSttFinal}                       │
   spoken{seq, tTtsFirst}                                  ▼
   call_end{reason}                            ConversationBrain
                                                 ├─ CallSession    history, persona, knowledge
                                                 ├─ PromptBuilder  the business's own words
                                                 ├─ LlmRouter ──▶ Gemini | OpenAI  (streaming)
                                                 └─ SentenceSplitter
python-voice ◀─────────────────────────────────────────  say{seq, text, last}
   speaks each sentence as it lands                       greeting{text, language}
```

| Layer | Built in Phase 3 |
|---|---|
| `brain/llm/` | `LlmProvider` + `LlmRequest` + `LlmStreamHandler`, `GeminiProvider`, `OpenAiProvider`, `LlmRouter`, and `SseChat` — the transport, timeout and retry both vendors share |
| `brain/` | `ConversationBrain` (turn lifecycle), `CallSession`, `CallRegistry`, `PromptBuilder`, `SentenceSplitter` |
| `api/` | `TurnSocket` at `/ws/turn/{callId}`; `CallController` lost `/line` to it |
| `services/` | `KbService`; `CallLogService` now records caller and agent lines separately and completes a timing that arrives late |
| `python-voice/` | `java_link.py` rewritten as a reconnecting websocket client; `session.py` speaks a queue instead of composing a reply |
| tests | `SentenceSplitterTest`, `PromptBuilderTest`, `LlmRouterTest`; the Python turn tests rewritten around a stand-in brain |

Four rules set here that later phases inherit:

- **The model is a setting, not a dependency.** Everything above `LlmProvider` is written
  against one interface, and which vendor sits behind it is read fresh at the start of every
  call — from the business's own AI settings first, the Settings page second. Swapping them
  mid-session changes the next call.
- **Grounded or not at all.** The prompt carries the business's About text, services, policies,
  questions, contact details and opening hours, and the standing orders say to use nothing
  else. A day the owner left blank is written out as closed rather than omitted, because a gap
  is an invitation to guess.
- **Sentences leave as they finish.** The reply is streamed, split at sentence endings, and
  handed to the voice one at a time. Waiting for the whole answer would cost most of the
  two-second budget, which is why `last` marks the end of a turn instead of being inferred.
- **A missing key is an answer, not an error.** No key, no brain, no model reply — each has a
  sentence the agent says out loud, in both languages, from `Lang.java`. Nothing in this layer
  may leave a caller listening to silence.

The three latency stamps now meet on one row. Python owns `tSttFinal` and `tTtsFirst`, Java
owns `tLlmFirst`, and because Java is what writes the row the voice server reports its second
stamp back over the same socket. Whichever half arrives second completes the reading.

## What Phase 4 added

The call now decides what kind of call it is, and in which language.

```
                    NEW_CUSTOMER ──┬──▶ EXISTING_CUSTOMER ──┐
                                   │                        ├──▶ COMPLEX_REQUEST   (stays on the line)
                                   └────────────────────────┴──▶ WRONG_NUMBER      (goodbye, hangup)

one turn:  prompt + 3 tool schemas ──▶ model ──┬── text ──▶ sentences ──▶ say{…}
                                               └── tool calls ──▶ ToolExecutor
                                                                    ├─ set_language ─▶ say{…, language}
                                                                    ├─ set_mode ─────▶ CallModeMachine
                                                                    └─ end_call ─────▶ hangup{reason, farewell}
                                                        │
                          second pass, NO tools offered ─┘   so it has to answer in words
```

| Layer | Built in Phase 4 |
|---|---|
| `brain/` | `CallModeMachine` (legality table, per-mode standing orders, who hangs up), `TurnRunner` (one turn, split out of the brain), `InactivityWatchdog` |
| `brain/tools/` | `ToolRegistry` — three schemas in a shape both vendors read; `ToolExecutor` — carries them out and answers in JSON, including its refusals |
| `api/` | `POST /api/call/{id}/mode`, the operator's override |
| `services/` | `CallLogService` writes mode transitions and language changes and pushes both to the panel |
| `python-voice/` | a second language on the recogniser, a voice per sentence rather than per call, and reporting an utterance nobody could make out |
| `static/js/` | `live_transcript.js` (the transcript view, lifted out), the screening facts, the override dropdown, mode and language notes in the transcript |

Four rules set here that later phases inherit:

- **One table, one answer, whoever is asking.** The model and the operator go through the same
  legality check. A call that has already said goodbye does not get talked back into being a
  customer, from the phone or from the panel.
- **What the model can do, it does through a tool.** Version 1 had it write `[MODE:…]` at the
  end of a reply and stripped the tag out before speaking; that works until the day it does
  not strip cleanly and a caller hears it. A function call cannot be spoken by accident.
- **A turn goes around at most twice.** The pass after a tool call is offered no tools, so the
  only thing the model can do with it is talk to the caller. That is the loop guard — not a
  counter, an absence.
- **The agent says the language question in both languages, in both voices.** It is the one
  moment nobody knows which is wanted. Each half is its own `say`, tagged with its own
  language, and the whole greeting is one turn, so the microphone opens once at the end of it.

Silence is measured in Java, not in the audio path. The voice server knows about frames; only
the brain knows whether a call is quiet because nobody is talking or because a model is
thinking, so a call mid-turn is skipped by the sweep.

## What Phase 5 added

The agent learned who it is talking to, and everything it reads became editable in the browser.

```
   panel                          java-backend                        postgres
 ┌────────────┐   REST      ┌──────────────────────┐
 │ Businesses │────────────▶│ BusinessService      │──▶ business, ai_settings, escalation_contact
 │ + editor   │             │ KbService            │──▶ kb_entry   (order = prompt order)
 │ Clients    │             │ ClientService ───────┼──▶ client     phone_enc / email_enc
 └────────────┘             └──────────┬───────────┘        ▲  pgp_sym_encrypt on the way in
                                       │                    │  try_decrypt   on the way out
 a call, mid-conversation              ▼
   lookup_client  ─┐         ┌──────────────────┐
   create_client  ─┼────────▶│  ToolExecutor    │──▶ CallSession.client ──▶ PromptBuilder
   log_request    ─┘         └──────────────────┘         │
                                                          └──▶ call_record.client_id + /ws/live
```

| Layer | Built in Phase 5 |
|---|---|
| `services/` | `ClientService` — the one class that reads and writes the encrypted columns; `KbService` and `BusinessService` grew the rest of the editing |
| `api/` | `ClientController`, `KbController`, `AiSettingsController`, `ClientDtos`, `EditorDtos` |
| `brain/tools/` | `lookup_client`, `create_client`, `log_request` — six tools now |
| `db/migration/` | `V3__try_decrypt.sql`, one function so a changed PII key costs contacts rather than the page |
| `static/js/` | `business_editor.js`, `clients.js`, read/write `businesses.js`, and the dialog / confirm / tab-strip / form parts in `components.js` |
| `utils/` | `Prompts.java` — the model's standing orders, moved out of `Lang` |
| deleted | `ConsoleTerminal`, `Console`, and nineteen console-only strings |

Four rules set here that later phases inherit:

- **One class knows how to open the box.** Contact details are pgcrypto blobs in the database
  and plain strings everywhere above `ClientService`. That means those rows are read and
  written in SQL rather than through JPA, and it means nothing else in the app has to know.
- **A key that no longer fits costs contacts, not the page.** `try_decrypt` turns an
  undecryptable row into nulls instead of an exception that aborts the transaction. The record
  still shows its name, notes and history, and the panel says plainly why the rest is missing.
- **Nothing a call reads is copied anywhere.** The prompt is assembled from these rows at the
  moment the call is placed. There is no cache to invalidate and no restart to remember, which
  is what makes the editor worth having.
- **The order of the knowledge base is editorial.** Entries reach the prompt in the order their
  owner put them in, so moving one up is a button of its own rather than a field on a form.

Writing a customer down does not make them a known customer *on that call*: they were a
stranger when they rang, and the transcript should say so. Only `lookup_client` promotes a call
to EXISTING_CUSTOMER.

## What Phase 6 added

A call is no longer over when the caller hangs up.

```
 caller says                                    what is kept                what is sent
 "my NID is 1990123456789"
        │
        ├──▶ call_message.text ──────────────▶ the real words          (panel, history, download)
        │
        └──▶ PiiMasker.mask ──▶ "[MASKED_NID]" ──▶ CallSession history ──▶ the model
                                                └──▶ PostCallService ──┬──▶ summary
                                                                       └──▶ escalation email

 hangup ──▶ CallLogService.end ──true, once──▶ PostCallService  (own thread; nobody is waiting)
                                                  ├─ LlmRouter ──▶ {summary_text, structured, action_items}
                                                  ├─ mode_path ◀── mode_transition rows, not the model
                                                  ├─ call_summary row              every call, always
                                                  └─ COMPLEX_REQUEST? ──▶ MailService ──▶ SMTP
                                                                                     └──▶ the log, in full,
                                                                                          when unconfigured
```

| Layer | Built in Phase 6 |
|---|---|
| `utils/` | `PiiMasker` — national IDs, phones, emails and amounts, in both scripts; `LangPages`, the per-page half of the catalogue after it outgrew one file |
| `services/` | `PostCallService` (the write-up and the handoff), `MailService` (SMTP from settings, or the log), `CallHistoryService` (reading a finished call back), `MetricsService` (percentiles and the distribution) |
| `api/` | `CallHistoryController` — `GET /api/calls`, `/api/calls/{id}`, `/api/calls/{id}/export`; the history shapes in `CallDtos` |
| `brain/tools/` | `escalate_to_human` — seven tools now |
| `static/js/` | `history.js` (the list and one call in full), the finished `dashboard.js`, and the contract's `progress` part in `parts.css` |

Four rules set here that Phase 7 inherits:

- **Masking is about what leaves the building.** The transcript keeps what was said, because
  the operator watching a call is supervising it. The model, the summary and the email get the
  masked copy. A number the masker cannot account for — an order number, a year, a date — is
  left alone, because a model that can see no numbers cannot answer the question it was asked.
- **The write-up reads the database, not a session.** Nothing is carried in memory between the
  hangup and the email. A call whose record says a person was promised is a call that gets one,
  whatever happened to the process in between.
- **Every call gets a summary, including the ones no model saw.** No key, no answer, unreadable
  JSON — each ends with a summary row saying so in the call's own language. That is what makes
  "written up" a column worth trusting.
- **What the app already knows, it does not ask the model.** `mode_path` is assembled from the
  call's own transitions. Asking for it would be inviting a guess about a fact on file.

Reading a call back is a different job from writing it down, and they are different classes:
`CallLogService` writes and broadcasts while the call is live; `CallHistoryService` only reads,
and has no live feed at all.

## What Phase 7 added

A call can arrive over a telephone, and a second business proves that onboarding is
configuration.

```
 browser tab ──PCM16 16k──────────────────────────┐
                                                  ├──▶ VoiceSession ──▶ the same brain
 a telephone ──Twilio──▶ /ws/twilio ──mu-law 8k───┘      (telephony="browser"|"twilio")
                            │  base64 in JSON, converted at this edge and nowhere else
                            │
   panel ──▶ /api/twilio/token ──▶ JWT signed with the API key secret (no SDK)
   Twilio ─▶ /api/twilio/voice ──▶ <Connect><Stream url="wss://…/ws/twilio">
                                     <Parameter name="callId"/>       ← ties audio to a record
                                   or, unconfigured, <Say>…</Say><Hangup/>   never an HTTP error
```

| Layer | Built in Phase 7 |
|---|---|
| `api/` | `TwilioController` — the access token, minted by hand, and the TwiML that points Twilio's media stream at the voice server |
| `python-voice/` | `transports/twilio_ws.py` — the Media Streams event protocol and the mu-law edge; `server.py` counts calls from both transports |
| `static/js/` | `twilio_mode.js` — the SDK lazy-loaded from a pinned CDN URL, the device, and the tooltip when there is nothing to press |
| `db/migration/` | `V4__seed_demo_two.sql` (Demo Courier), `V5` and `V6` correcting the model a clean install starts on |
| fixes | `ClientService.sameNumber` (a masked number matched the first customer on the books), `SseChat` retrying a transient refusal, `CallLogService` recognising an inbound number |

Three rules set here:

- **The transport is the only thing that knows about telephones.** Audio is converted to
  16 kHz PCM16 at the edge of `twilio_ws.py`, and nothing past it — session, brain, tools,
  transcript — can tell which way the call arrived. That is what makes "the same brain" true
  rather than aspirational.
- **A caller on the line is never shown an error.** The TwiML endpoint answers 200 with a
  spoken apology when the system is unconfigured, because an HTTP error would make Twilio play
  its own recording to a stranger.
- **A number with no digits matches nobody.** Masking sends `[MASKED_PHONE]` to the model, the
  model passes it back into `lookup_client`, and a matching rule built on `endsWith` treated
  that as matching everyone. Six digits minimum, on both sides.

The model is now chosen by measurement: `gemini-3.1-flash-lite` answers in a median 800 ms
against `gemini-3.6-flash`'s 3823 ms, and the whole turn budget is 2000 ms.

## Panel screens in contract parts

Named in Nocturne vocabulary so screens are assembled, not invented: shell = `side-rail`
(Dashboard, Live Call, Businesses, Clients, Call History, Settings) + `top-bar` (active
business `dropdown`, health `status-bar`). Live Call = `panel` with primary/secondary/
destructive `button`s, transcript `scroll-region` of `list-row`s with `tag-badge`s (role,
timing, and a note row for each change of mode or language), `key-value` call facts including
which model is answering, who is calling and how the call is screened, `dropdown`s to dial as a
known customer and to overrule the screening, and a `toast` when it has no key to answer with
or refuses an override. Businesses = `table` + `dialog` forms + `destructive-confirm` deletes,
opening a `tab-strip` editor (About / Services / Policies / Questions / Persona / Hours &
handover) with `inline-error` validation and `toast` saves. Clients = `table` + `dialog` forms,
with a `tag-badge` where a contact detail cannot be decrypted. Settings = `key-value` forms.
Dashboard = `stat-tile` strip (one accented number, the median reply time) + capability
`list-row`s + a screening distribution of `list-row`s with `progress` bars in the data palette
+ recent-calls `table`. Call History = `table` of calls, and one call as `key-value` facts, a
summary `panel`, the screening steps as `list-row`s and the transcript as a `scroll-region` of
`list-row`s with role and timing `tag-badge`s. The Twilio dial is a secondary `button` beside
the primary one, disabled with a tooltip until its credentials are real — the contract's own
answer for an action that exists but cannot be taken yet.

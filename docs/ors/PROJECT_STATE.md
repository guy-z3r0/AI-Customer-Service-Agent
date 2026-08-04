# PROJECT STATE

**Project:** AI Customer Service Agent v2
**Mode:** PHASED
**Updated:** 2026-08-04

## Current phase
Phase 5 — CRM + full editability (built, awaiting approval)

## Done
- Planning ✓ — proposal approved WITH CHANGES (swappable LLM providers; placeholder-first setup)
- STYLE-CONTRACT.md emitted + validated (aurora / comfortable / cyan, 38/38) ✓
- BUILD_SPEC.md, phases.md, application_brief.md, architecture.md written ✓
- Phase 1 ✓ approved — compose stack, Flyway V1+V2, 10 entities + repos, Business/Config/
  LegacyImport services, Nocturne panel shell
  → `docs/ors/logs/phase_01_log.md`, `phase_01_test.md`
- Phase 2 ✓ — python-voice server (VAD, streaming STT, TTS, provider fallback), browser call
  transport, LiveEventSocket, Live Call page
  → `docs/ors/logs/phase_02_log.md`, `phase_02_test.md`
- Phase 3 ✓ approved — swappable LLM layer (Gemini + OpenAI behind one streaming interface),
  ConversationBrain + PromptBuilder + SentenceSplitter, per-call `/ws/turn/{callId}`, all three
  latency stamps on the transcript row
  → `docs/ors/logs/phase_03_log.md`, `phase_03_test.md`
- Phase 4 ✓ approved — CallModeMachine and the four-way legality table, brain/tools with a
  tool-free second pass, bilingual greeting and mid-call language switch, Banglish handling,
  InactivityWatchdog, operator mode override
  → `docs/ors/logs/phase_04_log.md`, `phase_04_test.md`
- Phase 5 built ✓ — ClientService over the encrypted columns + V3 `try_decrypt`, three customer
  tools (lookup_client, create_client, log_request), the caller block in the prompt, Kb/Client/
  AiSettings controllers, the six-tab business editor, the Clients page, read/write Businesses,
  dial-as-a-customer, console removed. 41 Java tests + 17 Python tests
  → `docs/ors/logs/phase_05_log.md`, `phase_05_test.md`

## In progress
Nothing. Waiting for Nanjiba to run `phase_05_test.md` and approve.

## Blocked
Nothing blocked.

## Next action
**Approval gate.** Run `docs/ors/logs/phase_05_test.md`, steps 1–27. Steps 1–5 need no API key.
Step 7 (an edit in the browser changing what the agent says on the next call), step 12 (a
caller greeted by name from a record) and step 16 (the agent writing a record itself, mid-call)
are the three that decide the phase. Step 23 proves the encryption is a safety net rather than
a trap.

Then Phase 6 per phases.md: PostCallService and the escalation email, PiiMasker, the summaries,
MetricsService, and the Dashboard and Call History pages.

## Notes for next session
- **No phase has run against a live stack.** There is no Docker, PostgreSQL, microphone,
  Google account or model API key in this build environment. Each phase log lists exactly what
  is and is not verified. Phase 5's test script exercises Phases 1–4 as a side effect.
- **V3 has never been applied.** It is one `CREATE OR REPLACE FUNCTION` in plain PL/pgSQL and
  touches no rows, but no Postgres has parsed it. Phase 7's demo business becomes **V4**, not
  V3 as BUILD_SPEC says.
- **The encrypted round trip is untested end to end.** The customer tools are covered by an
  in-memory customer list; the SQL underneath — `pgp_sym_encrypt` on the way in, `try_decrypt`
  on the way out — has never run. Section F of `phase_05_test.md` is what proves it.
- `spring.jpa.hibernate.ddl-auto` is still `none`. Flip it to `validate` once the stack has
  booted once and the entity mapping is proven.
- **Other unverified guesses, in the order worth checking:** the greeting is put into history
  as an `assistant` message, so a Gemini request's `contents` can begin with a model turn — if
  Gemini objects, drop it from `CallSession.remember` at greet time; neither vendor has seen
  `ToolRegistry`'s six schemas; `alternative_language_codes` on `latest_short` is refused in
  some regions and `stt_gcp.py` turns it off on the first refusal.
- Phase 6's `escalate_to_human` slots into the layer that exists: add the schema to
  `ToolRegistry` and the case to `ToolExecutor.run`. It needs `MailService`, and the escalation
  contacts it emails are already editable on the business editor's Hours & handover tab.
- `PiiMasker` (Phase 6) belongs between `onTranscriptFinal` and `session.remember` in
  `ConversationBrain`, and again before `PostCallService` writes a summary.
- The turn socket carries two messages BUILD_SPEC does not name: `spoken{seq, tTtsFirst}` and
  `transcript_final` with empty text. Both documented in the phase logs.
- Six files exist that BUILD_SPEC does not list, each justified in its phase log:
  `brain/llm/SseChat.java`, `brain/SentenceSplitter.java`, `brain/TurnRunner.java`,
  `utils/Prompts.java`, `static/js/pages/live_transcript.js`, and the two grouped dto
  containers `ClientDtos` / `EditorDtos` (the `api/dto/` cap of six was reached in Phase 2).
- One dependency added across the project: `spring-boot-starter-test` (test scope,
  Boot-managed), plus `websockets==13.1` on the Python side, which BUILD_SPEC listed.
- Credentials are all `PLACEHOLDER_*`; never hard-require one to boot or to place a call.
  `ConfigService.isPlaceholder(key)` in Java, `config.google_credentials_available()` in
  Python, `LlmRouter.isReady(selection)` for the model.
- `utils/Lang.java` is what a person reads — 222 keys, both languages, over `GET /api/lang`;
  the only entry without Bengali script is `settings.badge_placeholder`, deliberately.
  `utils/Prompts.java` is what the model reads, English only, and never heard by anyone.
- Panel styling only via `var()` from `css/nocturne.css` ← transcribed from `/STYLE-CONTRACT.md`.
- **Nothing is committed.** Every phase since v1 is uncommitted in the working tree; the last
  commit is `d0031eb TTS`. Each phase log ends with a commit message ready to use.
- `python-voice/.venv/` is a leftover v1 virtualenv on disk. It is gitignored and was left
  alone rather than deleted.
- User (Nanjiba) approves each phase before the next starts.

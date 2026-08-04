# PROJECT STATE

**Project:** AI Customer Service Agent v2
**Mode:** PHASED
**Updated:** 2026-08-05

## Current phase
Phase 6 — handoff, logging, summaries, PII, metrics (built and run live, awaiting approval)

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
- Phase 5 ✓ — ClientService over the encrypted columns + V3 `try_decrypt`, three customer
  tools, the caller block in the prompt, Kb/Client/AiSettings controllers, the six-tab business
  editor, the Clients page, read/write Businesses, dial-as-a-customer, console removed
  → `docs/ors/logs/phase_05_log.md`, `phase_05_test.md`
- Phase 6 built ✓ — PiiMasker, PostCallService, MailService, CallHistoryService,
  CallHistoryController, `escalate_to_human` (seven tools), the Call History page, the finished
  Dashboard (reply-time percentiles + screening distribution), and the string catalogue split
  at the 500-line cap. 55 Java tests + 17 Python
  → `docs/ors/logs/phase_06_log.md`, `phase_06_test.md`

## In progress
Nothing. Waiting for Nanjiba to run `phase_06_test.md` and approve.

## Blocked
Nothing blocked.

## Next action
**Approval gate.** Run `docs/ors/logs/phase_06_test.md`, steps 1–19. Steps 1–8 need no API key.
Step 6 (a call written up by the system), step 9 (a call written up by the model) and step 12
(an ID number spoken aloud reaching the colleague's inbox as `[MASKED_NID]` while the
transcript keeps it) are the three that decide the phase.

**Step 19 needs a decision from you.** The string catalogue passed the 500-line file cap, so
`utils/Lang.java` was split — the per-page half is now `utils/LangPages.java`, package-private
and reached only through `Lang`. That is CLAUDE.md's "strings live in Lang.java only" against
CLAUDE.md's "no file over 500 lines", and at 301 bilingual entries they cannot both hold.
Say which one you want and it takes two minutes either way.

Then Phase 7 per phases.md: the Twilio transport and controller, the second seeded business
(**V4**, not V3 — V3 was used in Phase 5), the latency tuning pass and the README rewrite.

## Notes for next session
- **Phase 6 is the first phase run against a live stack.** The backend was started on the
  embedded PostgreSQL (`mvn spring-boot:run -Dspring-boot.run.profiles=dev`, or `run-local.ps1`
  from the repo root), Flyway was already at V3, and two calls were driven through
  `/ws/turn/{callId}` by a script standing in for the voice server. Verified live: the history
  list and one call in full, the export, the escalation email built and logged, masking in the
  email with the transcript untouched, and the Dashboard. **V3 has been applied** — the note in
  earlier states saying it never had is out of date.
- **What a live run still has not shown:** a real model summary (no API key, so every summary
  so far is the fallback line), a real email (SMTP all placeholders), a model choosing
  `escalate_to_human` itself, and any of the voice path — the calls bypassed the microphone,
  so the reply-time tiles stayed empty for want of a `tTtsFirst`.
- **Amounts are masked as well as identifiers**, per BUILD_SPEC. "I paid 2500 taka" reaches the
  model as "I paid [MASKED_AMOUNT]". That is a real trade-off against ordinary service talk;
  dropping `AMOUNT_PATTERN` from `PiiMasker.mask` is the one-line change if you want it off.
- `spring.jpa.hibernate.ddl-auto` is still `none`. The stack has now booted repeatedly against
  a real schema, so flipping it to `validate` is a safe next step and would catch entity drift.
- **Unverified guesses still standing:** the greeting goes into history as an `assistant`
  message, so a Gemini request's `contents` can begin with a model turn; neither vendor has
  seen `ToolRegistry`'s seven schemas or the summary prompt's JSON request;
  `alternative_language_codes` on `latest_short` is refused in some regions and `stt_gcp.py`
  turns it off on the first refusal.
- Files above BUILD_SPEC's list, each justified in its phase log: `brain/llm/SseChat.java`,
  `brain/SentenceSplitter.java`, `brain/TurnRunner.java`, `utils/Prompts.java`,
  `utils/LangPages.java`, `services/CallHistoryService.java`,
  `static/js/pages/live_transcript.js`, and the grouped dto containers `ClientDtos` /
  `EditorDtos` (the `api/dto/` cap of six was reached in Phase 2).
- One dependency added across the project: `spring-boot-starter-test` (test scope,
  Boot-managed), plus `websockets==13.1` on the Python side, which BUILD_SPEC listed.
  `spring-boot-starter-mail` was already in the pom from Phase 1 and is used from Phase 6.
- Credentials are all `PLACEHOLDER_*`; never hard-require one to boot or to place a call.
  `ConfigService.isPlaceholder(key)` in Java, `config.google_credentials_available()` in
  Python, `LlmRouter.isReady(selection)` for the model, `MailService.isConfigured()` for SMTP.
- The string catalogue is 301 entries in both languages, served over `GET /api/lang`, split
  across `utils/Lang.java` and `utils/LangPages.java`. The only entry without Bengali script is
  `settings.badge_placeholder`, deliberately. `utils/Prompts.java` is what the model reads,
  English only, and never heard by anyone.
- Panel styling only via `var()` from `css/nocturne.css` ← transcribed from `/STYLE-CONTRACT.md`.
  Exactly one number on a screen may wear the accent; on the Dashboard that is the median reply
  time. The data palette is for charts, which is why the distribution bars use it.
- **Nothing is committed.** Phases 5 and 6 are uncommitted in the working tree; the last commit
  is `a72a723`. Each phase log ends with a commit message ready to use.
- **`.gitignore` had been swallowing `docs/ors/logs/` since Phase 1** — its `logs/` pattern for
  runtime logs also matched the phase records, so none of the twelve log and test-script files
  had ever been committed. Fixed with one negation line in Phase 6; the earlier eleven files
  now show as untracked and go in with the next commit.
- `python-voice/.venv/` is a leftover v1 virtualenv on disk. It is gitignored and was left
  alone rather than deleted.
- User (Nanjiba) approves each phase before the next starts.

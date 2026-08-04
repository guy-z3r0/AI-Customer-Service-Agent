# PROJECT STATE

**Project:** AI Customer Service Agent v2
**Mode:** PHASED
**Updated:** 2026-08-05

## Current phase
Phase 7 — Twilio mode, second business, polish (built and run live, awaiting approval)

**This is the last phase.** All seven in `phases.md` are built. What remains after approval is
not construction but verification: five of the nine boxes in BUILD_SPEC's definition of done
need a credential, a microphone or Docker, not more code.

## Done
- Planning ✓ — proposal approved WITH CHANGES (swappable LLM providers; placeholder-first setup)
- STYLE-CONTRACT.md emitted + validated (aurora / comfortable / cyan, 38/38) ✓
- BUILD_SPEC.md, phases.md, application_brief.md, architecture.md written ✓
- Phase 1 ✓ approved — compose stack, Flyway V1+V2, 10 entities + repos, Business/Config/
  LegacyImport services, Nocturne panel shell
- Phase 2 ✓ — python-voice server (VAD, streaming STT, TTS, provider fallback), browser call
  transport, LiveEventSocket, Live Call page
- Phase 3 ✓ approved — swappable LLM layer (Gemini + OpenAI behind one streaming interface),
  ConversationBrain + PromptBuilder + SentenceSplitter, per-call `/ws/turn/{callId}`, all three
  latency stamps on the transcript row
- Phase 4 ✓ approved — CallModeMachine and the four-way legality table, brain/tools with a
  tool-free second pass, bilingual greeting and mid-call language switch, Banglish handling,
  InactivityWatchdog, operator mode override
- Phase 5 ✓ — ClientService over the encrypted columns + V3 `try_decrypt`, three customer
  tools, the caller block in the prompt, Kb/Client/AiSettings controllers, the six-tab business
  editor, the Clients page, read/write Businesses, dial-as-a-customer, console removed
- Phase 6 ✓ — PiiMasker, PostCallService, MailService, CallHistoryService, escalate_to_human,
  the Call History page, the finished Dashboard, the string catalogue split at the 500-line cap
- Phase 7 built ✓ — TwilioController (hand-minted access token + TwiML), `transports/twilio_ws.py`
  (Media Streams, mu-law edge), `twilio_mode.js`, V4 Demo Courier, V5+V6 the default model,
  the caller-matching fix, transient-refusal retry, README rewritten, SETUP's Twilio walkthrough.
  59 Java tests + 22 Python
  → every phase has its log and test script in `docs/ors/logs/`

## Since Phase 7 was written — six fixes from Nanjiba's own testing
- **Silence was measured from the wrong moment.** The clock ran from when the call
  connected, while the agent was still reading the greeting, so a 15/30 setting warned after
  ~3 seconds of real silence and hung up after ~18. The voice server now reports `agent_done`
  once its audio has actually played, and the brain starts counting there. Measured: 15.2 s and
  30.6 s of true silence.
- **Bangla was read by an English voice.** The offline engine ignored the language argument
  entirely. It now picks a voice matching the call's language — and this machine turns out to
  have only two voices, both English, which is the whole reason Bangla sounded wrong. The panel
  now says so instead of sounding broken.
- **Voices are a menu, not a blank box.** `pipeline/voices.py` enumerates what is really
  installed (plus Google's when configured), served at `GET /voices` → `GET /api/config/voices`.
- **A live call timer** on the Live Call page, which stops rather than resets at the end.
- **Calls nobody spoke on** report as "No answer", or "Known customer — no answer" when
  dialled as one, in red. Derived for display in `static/js/call_outcome.js`; the stored
  screening mode is untouched, because what the machine decided is still a fact about the call.
- **SETUP.md** now carries the speech, voice, email and telephony walkthroughs in full, plus
  the call-behaviour timings.

## In progress
Nothing. Waiting for Nanjiba to run `phase_07_test.md` and approve.

## Blocked
Nothing blocked.

## Next action
**Approval gate.** Run `docs/ors/logs/phase_07_test.md`, steps 1–15. Steps 1–8 and 11 need no
new credentials. Steps 4 (a second business answering as itself), 7 (a stranger no longer
mistaken for a customer) and 11 (everything behaving with no Twilio credentials) decide it.

After that the project is built, and what is left is the definition-of-done sitting:

| Definition of done | State |
|---|---|
| Full editability in the panel | ✓ |
| Legacy import | ✓ |
| Every phase has a log + test script | ✓ |
| Twilio works, or is cleanly disabled | ✓ disabled path verified; **a real Twilio call has never been placed** |
| `docker compose up` from a clean clone, health green | **never run** — every session used the dev profile |
| Browser call under 2 s median | needs Google speech + a microphone |
| All four scenarios end to end | needs the same |
| Bangla call in Bengali script | needs the same |
| Provider swap gemini → openai mid-session | needs a second key |

## Notes for next session
- **Phase 7 ran with a real Gemini key**, which closed four items carried since Phase 3: the
  summary prompt returns parseable JSON from a real vendor; the vendor accepts all seven tool
  schemas; the model chooses `escalate_to_human` on its own; and Gemini does not object to a
  history that opens with an assistant turn.
- **A real bug was found and fixed that only a live model could expose.** `ClientService.byPhone`
  matched with `endsWith`, masking sends the model `[MASKED_PHONE]`, that has no digits, and
  every string ends with the empty one — so a stranger was greeted as the first customer on the
  books and answered from their record. Both sides now need six digits. `ClientMatchingTest`
  covers it, including the masked case verbatim.
- **The seeded model was shut down by Google.** V2's `gemini-2.0-flash` returns 404, so a clean
  clone with a perfectly good key had an agent that could only apologise. V5 and V6 correct it,
  matching on the old value so an operator's own choice is never overwritten.
- **The latency stall is the one open thread.** Roughly one turn in five takes 15–18 seconds.
  Not the model tier, not a retry, not a tool call, not IPv6 fallback — all four tested and
  ruled out. Chasing it needs a timestamp taken when the request is written, which this app
  does not currently take.
- **Twilio is built but unproven.** No account, no credentials, no ngrok here. The protocol
  handling, the token shape, the TwiML and every unconfigured path are tested; Twilio accepting
  the token and audio actually flowing are not.
- Still never exercised: SMTP sending, Google Cloud speech, a microphone, Bangla end to end,
  and `docker compose up`. Docker is installed and working on this machine — that one is worth
  doing early, since it is the documented path a clean clone takes.
- `spring.jpa.hibernate.ddl-auto` is still `none`. The schema has now booted many times; moving
  it to `validate` would catch entity drift and is a safe next step.
- **Running two backends at once will break the database.** `DevDatabase.clearStaleLock` stops
  whatever process `postmaster.pid` names, so a second instance kills the first one's Postgres
  and then fails to lock. Symptom: `could not lock .embedded-postgres\data\epg-lock` and a live
  backend answering 500s. Fix: stop everything, delete `epg-lock`, start one.
- Files above BUILD_SPEC's list, each justified in its phase log: `brain/llm/SseChat.java`,
  `brain/SentenceSplitter.java`, `brain/TurnRunner.java`, `utils/Prompts.java`,
  `utils/LangPages.java`, `services/CallHistoryService.java`, `static/js/pages/live_transcript.js`,
  and the grouped dto containers `ClientDtos` / `EditorDtos`.
- Dependencies added across the whole project: `spring-boot-starter-test` (test scope) and
  `websockets` on the Python side. **No Twilio library on either side** — the access token is
  a JWT signed with `javax.crypto`, and Media Streams is four JSON events. The browser SDK is
  pinned to `cdn.jsdelivr.net/npm/@twilio/voice-sdk@2.18.3`, verified by loading it in a
  browser; the `sdk.twilio.com` path most guides give does **not** resolve.
- The string catalogue is 304 entries in both languages across `utils/Lang.java` and
  `utils/LangPages.java`. The only entry without Bengali script is `settings.badge_placeholder`.
  **The Lang split still needs your decision** — it was Phase 6's open question and is unchanged.
- **Nothing is committed.** Phases 5, 6 and 7 are uncommitted; the last commit is `a72a723`.
  Each phase log ends with a commit message ready to use. The eleven earlier log and test-script
  files are newly visible to git after Phase 6's `.gitignore` fix and go in with the next commit.
- User (Nanjiba) approves each phase before the next starts.

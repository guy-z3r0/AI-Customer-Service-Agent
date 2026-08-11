# PROJECT STATE

**Project:** AI Customer Service Agent v2
**Mode:** PHASED
**Updated:** 2026-08-11

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
*(and three more at the end of this list, added after the turn-taking pass below)*
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
- **A business can be downloaded and uploaded as one file.** Setting one up is an afternoon of
  typing and it did not travel: no backup, no second machine, no handing a finished setup to
  somebody else. **Download setup** on a Businesses row writes everything the six editor tabs
  hold as `business-<handle>.json`; **Import a setup file** reads one back, either as a new
  business or over an existing one, which empties that business's knowledge, persona, hours and
  handover contacts first. Customers and call history are deliberately not in the file — a
  business's knowledge is not personal data and its customer list is — and neither is the
  active flag. `api/dto/TransferDtos`, `services/BusinessTransferService`, and
  `static/js/pages/business_transfer.js`; every write goes through the same two services the
  editor's own forms post to, so a file cannot put anything in the database that could not be
  typed into the panel. **A round trip found a real bug that no unit test would have:** the
  seeded escalation contact is `PLACEHOLDER_ESCALATION_EMAIL`, `@Email` refused it, and so the
  app refused its own downloaded file — which is the first thing anyone tries. Verified end to
  end against a real Postgres: export → import → export is byte-identical, Bangla and all.
- **The Bangla voice menu shows only Indian voices because those are the only ones there.**
  Asked for its catalogue with Nanjiba's own key, Google returns 38 Bengali voices — four
  Standard, four WaveNet, thirty Chirp 3 HD — and every one is `bn-IN`. There is no `bn-BD`
  voice to list, from Google or from Windows, and the panel was not filtering anything. What
  changed is that Settings now says so under the menu, and says in the same breath that
  recognition does use `bn-BD` — so the pair no longer looks like an oversight.
- **A Twilio call needed two public tunnels, and a free ngrok account has one.** The webhook
  wants 8080 and the media stream wanted 8090; asked for two, ngrok gives one hostname and the
  second tunnel quietly lands on the first, so Twilio's requests reach the wrong service. The
  backend now serves `/ws/twilio` itself (`api/TwilioMediaSocket`) and relays every frame,
  unread, to the voice server's own `/ws/twilio` over the Docker network — so one tunnel at
  8080 carries both, and **Public media URL** now means "your one public host". `twilio_ws.py`
  is untouched and cannot tell the difference. Both close directions and an unreachable voice
  server were exercised against a running backend with a stand-in on 8090; a real Twilio call
  still has not been placed.

## Since then — four more from one live call
All four came out of a single four-minute call to Bengal Power System, transcript kept.
Test script: `docs/ors/logs/live_call_fixes_test.md`.

- **The call length went on counting after the agent had hung up.** Two paths end a call and
  only one of them finished the page: pressing **End call** stopped the clock, while the agent
  ending it left the page redrawing a timer against a call that was over. **And the microphone
  was never released on that path either** — the browser held it open after the call, which
  is the more serious half of the same bug and was not visible on screen at all. Both paths now
  go through one teardown.
- **The agent said goodbye and stayed on the line.** It wrote "thank you for contacting us,
  and have a good day" and did not call `end_call`, so the caller was left listening to
  nothing and had to ask "are you still there?" — twice. A model that has just written a
  farewell is exactly the model that stops reaching for tools, so what it *said* is now read:
  `brain/FarewellSense` ends the call on a reply that says goodbye and asks nothing. Both
  halves are needed, because the standing orders already say every other reply ends with a
  question — so a reply that merely forgot its question mark is not hung up on.
- **"I want to talk with your manager" was escalated on that sentence alone.** The colleague
  was handed a call whose entire content was the request itself, and nobody ever asked what it
  was about — half of those callers want something the agent could have answered outright.
  `escalate_to_human` now refuses an escalation that cannot say what the matter is, and the
  prompt says to ask once and try to answer it first. Deliberately not a second hoop for a
  caller who has already explained themselves: a complaint described on the first turn is
  escalated on the first turn.
- **"Recognised: Sadman" was neither recognition nor safe.** Two separate faults wearing one
  badge. The panel said "Recognised" over a record the call had just written from what the
  caller said their name was — it now says **Written down as a new customer**. And identity
  rested on the number alone, which is not identity: handsets are shared, numbers are
  reassigned, and one wrong digit over a bad line lands on somebody else's record, whose name
  the caller would then be greeted by. `lookup_client` now needs the name as well as the
  number, part of a name matching the whole of it, and `create_client` refuses a number
  already on the books rather than giving one person two histories.

## Since then — nine more faults from live calls, all fixed
Seven of the nine turned out to be one question asked in different places: **who has the
floor, and how does anything else know?** See `architecture.md`, "What the turn-taking pass
changed". Test script: `docs/ors/logs/turn_taking_test.md`.

- **Speech was cut off at the end.** The turn ended on an estimate made from the length of
  the samples, which is when the audio *would* finish if it began the instant it was sent. It
  does not — it crosses a socket and plays out of a buffer. The page now reports `audio_done`
  when the sound has really stopped, and the session waits for the later of the two plus
  400 ms.
- **The agent heard its own tail.** The same estimate reopened the microphone early, the last
  syllable of the reply came back as an utterance, the recogniser made no words of it, and the
  agent asked the caller to repeat something the caller never said. Same fix.
- **"Sorry, I could not make that out" ran for ever.** The counter behind it was reset every
  time it asked, and asking is the agent speaking, which restarts the silence clock — so
  neither the re-prompt limit nor the inactivity hangup could ever fire. Two asks, then
  goodbye, and the count is no longer reset by the asking.
- **"Are you still there?" landed on people mid-sentence.** The free recogniser says nothing
  at all until a sentence is finished, so a caller talking for twenty seconds was
  indistinguishable from an empty room. The voice detector now reports `caller_speaking` and
  `caller_stopped`, and the agent holds the floor from the moment a line is sent until its
  audio has played — so the watchdog cannot talk over either party.
- **Replies did not always ask anything back.** A caller has no screen; the reply has to say
  what to do next. Now a standing rule, with the goodbye as the only exception.
- **Bangla would not switch back to English.** A Bangla call kept a Bangla recogniser however
  the caller spoke, so every English sentence came back as Bengali letters spelling English
  sounds — including the sentence asking to switch. `LanguageSense` reads the script the words
  came back in and moves the call itself, without waiting for the model to call `set_language`.
  The free recogniser also retries a failed utterance in the other language.
- **Nuisance callers were answered politely for ever.** Prompt work: a nuisance block naming
  what is not a customer, the exchange count so "a second time" is checkable, and `WRONG_NUMBER`
  in the tool schema now describes them. **This one is the model's judgement, not a rule, and
  is the only fix here that can vary between calls.**
- **Swearing had no consequence.** `SlangGuard` matches a short list of words with no other
  meaning, whole-word, in both languages. First one warns and stays on the line; the second
  ends the call. An irritated customer — "this is useless", "your service is terrible" — is
  deliberately not caught.
- **Settings showed the Bangla voice as PLACEHOLDER.** Choosing "whichever the provider picks"
  stores an empty value, which the panel then badged as undecided. Blank is now an answer for
  the two voice keys, the option names the voice it will really use, and an empty setting is
  no longer quietly replaced by a hard-coded Google default.

**And the reason Bangla did not work after adding the API key:** the file in `secrets/` is
named `gcp-credentials.json.json` — Windows hides known extensions — while the setting points
at `gcp-credentials.json`. Everything downstream degraded politely and silently around it.
Settings now says the file is missing and names the near-miss. **Rename that file and Google
speech comes on within a minute, with no restart.**

Windows has no Bangla voice to install: this machine's SAPI and OneCore registries hold three
English voices between them and nothing else, and Microsoft ships no Bangla TTS. Bangla speech
means Google Cloud. The OneCore renderer added here (`pipeline/tts_windows.py`) still earns
its place — it speaks with voices `pyttsx3` refuses outright, verified against Microsoft Mark
— but it cannot conjure a language Windows does not have.

## In progress
Nothing. Waiting for Nanjiba to run `phase_07_test.md`, `turn_taking_test.md`,
`one_tunnel_test.md`, `business_transfer_test.md` and `live_call_fixes_test.md`, and approve.

## Blocked
Nothing blocked.

## Next action
**Approval gate.** Five scripts now.

1. `docs/ors/logs/turn_taking_test.md`, which covers the nine faults above. Steps 5 (a reply
   that is not cut off), 7 (an agent that does not talk over you) and 9 (a call that ends
   itself rather than asking for ever) decide it. **Rename `secrets/gcp-credentials.json.json`
   to `secrets/gcp-credentials.json` before section E** — that one rename is what turns Bangla
   on.
2. `docs/ors/logs/phase_07_test.md`, steps 1–15, unchanged except that step 1 now expects
   184 Java tests and 51 Python. Steps 4 (a second business answering as itself), 7 (a
   stranger no longer mistaken for a customer) and 11 (everything behaving with no Twilio
   credentials) decide that one. **Steps 9–10 now need only one ngrok tunnel**, pointed at
   8080, with that same host in both Public media URL and the TwiML App's request URL.
3. `docs/ors/logs/one_tunnel_test.md`, new: Twilio over that single tunnel. Steps 4 (one
   tunnel carrying both the webhook and the audio) and 6 (a real call, with the voice
   server's own log unchanged) decide it. **This is the script no part of the project has
   ever passed** — it is where a telephone is met for the first time.
4. `docs/ors/logs/business_transfer_test.md`, new: a business downloaded as a file and put
   back. Steps 3 (a file that goes back in unedited) and 5 (a replace run twice that does not
   double anything) decide it. Section E is the Bangla voice list, which needs the Google key.
5. `docs/ors/logs/live_call_fixes_test.md`, new: the four faults from the Bengal Power System
   call. Steps 3 (a clock that stops), 5 (an agent that hangs up when it says goodbye) and 7
   (a person fetched only once the agent knows what for) decide it. Step 4 is worth doing
   even though it looks trivial — it is the microphone, and it was the one nobody could see.

After that the project is built, and what is left is the definition-of-done sitting:

| Definition of done | State |
|---|---|
| Full editability in the panel | ✓ |
| Legacy import | ✓ |
| Every phase has a log + test script | ✓ |
| Twilio works, or is cleanly disabled | ✓ disabled path verified; **a real Twilio call has never been placed** |
| `docker compose up` from a clean clone, health green | ✓ run 2026-08-05 — it did not work until two things were fixed, see SECURITY-AUDIT.md WARN-006 |
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
- Still never exercised: SMTP sending, Google Cloud speech, a microphone, and Bangla end to
  end. `docker compose up` is now done — all three containers start, all eight migrations
  apply, and both run as uid 10001 rather than root.
- **The test suite had never actually run on this machine.** Mockito's Byte Buddy did not
  understand Java 25's class files, so the fourteen login tests and both other security suites
  errored before executing. `byte-buddy.version` is pinned to 1.18.0. **184 Java tests and 51
  Python now pass**, up from 119 and 32 — the turn-taking pass added `CallEndsItselfTest`,
  `InactivityWatchdogTest`, `LanguageSenseTest`, `SlangGuardTest`,
  `test_talking_over_each_other.py` and `test_voice_choices.py`, the one-tunnel fix added
  `TwilioRelayTest` plus a case in `ApiRequiresLoginTest`, the setup-file pass added
  `BusinessTransferTest`, and the live-call pass added `FarewellSenseTest` plus cases in
  `ToolExecutorTest`, `ClientMatchingTest` and `CallEndsItselfTest`.
- `spring.jpa.hibernate.ddl-auto` is now `validate` (WARN-002). Flyway still owns the schema;
  Hibernate checks it at boot and refuses to start if an entity has drifted from a migration.
- **Running two backends at once still breaks the database, but for one reason now instead of
  two.** `DevDatabase.clearStaleLock` no longer stops whatever process `postmaster.pid` happens
  to name — it checks the process is a postgres and that it started before the file was written
  (BUG-001). What remains is zonky's own `epg-lock`, which a second instance cannot take.
  Symptom: `could not lock .embedded-postgres\data\epg-lock`. Fix: stop everything, then start
  one. If a run is killed rather than stopped, its Postgres is orphaned and the next boot clears
  it — or stop it by hand with `pg_ctl -D java-backend/.embedded-postgres/data -m fast stop`.
- Files above BUILD_SPEC's list, each justified in its phase log: `brain/llm/SseChat.java`,
  `brain/SentenceSplitter.java`, `brain/TurnRunner.java`, `utils/Prompts.java`,
  `utils/LangPages.java`, `services/CallHistoryService.java`, `static/js/pages/live_transcript.js`,
  and the grouped dto containers `ClientDtos` / `EditorDtos`. The turn-taking pass added
  `brain/CallInterventions.java`, `brain/Greeter.java`, `brain/LanguageSense.java`,
  `utils/SlangGuard.java`, `python-voice/pipeline/tts_windows.py` and
  `static/js/pages/call_stats.js` — the first two are `ConversationBrain` split at 532 lines to
  get back under the 500-line cap, and the last is `live_call.js` for the same reason. The
  one-tunnel fix added `api/TwilioMediaSocket.java`, which BUILD_SPEC could not have listed:
  it exists because of what a free ngrok account will not do. The setup-file pass added
  `api/dto/TransferDtos.java`, `services/BusinessTransferService.java` and
  `static/js/pages/business_transfer.js`.
- Dependencies added across the whole project: `spring-boot-starter-test` (test scope) and
  `websockets` on the Python side. **No Twilio library on either side** — the access token is
  a JWT signed with `javax.crypto`, and Media Streams is four JSON events. The browser SDK is
  pinned to `cdn.jsdelivr.net/npm/@twilio/voice-sdk@2.18.3`, verified by loading it in a
  browser; the `sdk.twilio.com` path most guides give does **not** resolve.
- The string catalogue is 312 entries in both languages across `utils/Lang.java` and
  `utils/LangPages.java`. The only entry without Bengali script is `settings.badge_placeholder`.
  **The Lang split still needs your decision** — it was Phase 6's open question and is unchanged.
- **Nothing is committed.** Phases 5, 6 and 7 are uncommitted, and so are three passes over
  `SECURITY-AUDIT.md`; the last commit is `87883d5`. Each phase log ends with a commit message
  ready to use. **Do not commit before reading SEC-001** — the two steps that need you are at
  the top of the audit, and the pre-commit hook that stops a repeat is installed with
  `git config core.hooksPath .githooks`.
- User (Nanjiba) approves each phase before the next starts.

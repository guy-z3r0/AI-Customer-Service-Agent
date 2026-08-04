# Phase 4 log — Screening + bilingual: modes, language select, Bangla, inactivity

**Closed:** 2026-08-04
**Status:** built; the screening table, the tool layer and the bilingual turn
are unit-tested on both sides, and the whole project builds and packages. Not
yet run against a live stack or a real API key — see "What is not verified".

---

## What ships

The call now screens itself. It opens as a stranger, and the agent can decide
part-way through that it is talking to a nuisance caller or to somebody whose
problem needs a person — and those two decisions are the end of the road. A
wrong number gets a polite goodbye and a hangup; a complex request stays on the
line to take details down. The operator can overrule any of it from the panel,
and gets refused by exactly the same table the model does.

The greeting asks which language the caller wants, once in each, in the right
voice for each half. Saying "Bangla" switches the recogniser, the voice and the
agent's own wording for the rest of the call, and the transcript comes back in
Bengali script.

Silence is handled rather than waited out: the agent asks whether anyone is
still there, and if nothing comes back it says goodbye and hangs up. Both waits
come from Settings.

---

## How a turn goes now

```
transcript_final ─▶ ConversationBrain ─▶ TurnRunner
                                            │
                          ┌─────────────────┴────────────────────┐
                          │ pass 1: prompt + 3 tool schemas      │
                          │   text ──▶ sentences ──▶ say{…}      │
                          │   tool calls ──▶ ToolExecutor        │
                          └─────────────────┬────────────────────┘
                                            │  set_language ─▶ say{…, language}
                            CallModeMachine ┤  set_mode     ─▶ mode_transition row
                              legality table│  end_call     ─▶ hangup{reason, farewell}
                                            │
                          ┌─────────────────┴────────────────────┐
                          │ pass 2: same prompt, NO tools        │
                          │   so it has to answer in words       │
                          └─────────────────┬────────────────────┘
                                            ▼
                              say{…, last:true} ─▶ hangup, if one was asked for
```

The second pass is offered no tools at all. That is what bounds the loop: a
model with nothing left to reach for has to speak, so a turn goes around at
most twice however hard it tries.

---

## Files added

**Java** — `brain/CallModeMachine.java` (the legality table, per-mode standing
orders, and who hangs up), `brain/InactivityWatchdog.java`,
`brain/TurnRunner.java` (one turn, split out of the brain),
`brain/tools/ToolRegistry.java` and `brain/tools/ToolExecutor.java`.

**Panel** — `js/pages/live_transcript.js`, the transcript view lifted out of
`live_call.js`.

**Tests** — `CallModeMachineTest`, `ToolExecutorTest`, and `TestCalls`, a call
with no database, websocket or model behind it. 32 Java tests, 17 Python.

## Files changed

`ConversationBrain` gained the screening lifecycle, the bilingual greeting, the
re-prompt, the silence lines and the hangup plumbing, and handed one turn's
worth of work to `TurnRunner`. `CallSession` gained the pending hangup, the
silence clock and the unheard counter. `PromptBuilder` puts the mode's standing
orders and the tool guidance into every prompt. `CallLogService` writes mode
transitions and language changes and pushes both to the panel. `CallController`
gained `POST /api/call/{id}/mode`. `Language` learned to read and write its own
two-letter code, which three classes were doing by hand.

`stt_gcp.py` asks Google to listen for the other language as well.
`session.py` speaks each sentence in the language it arrived tagged with, and
reports an utterance the recogniser made nothing of instead of dropping it.
`live_call.js` shows the screening and the language, offers the override, and
puts mode and language changes into the transcript where they happened.

`Lang.java` grew the four modes' instruction text, the tool guidance, and seven
spoken lines in both languages. 133 panel strings now.

## Files deleted

`ai/CallMode.java` — Phase 4's list, exactly. Its four descriptions were the
source material for `CallModeMachine.instructionsFor` and are now in
`Lang.java` with the rest of the prompt text; its `[MODE:…]` tag protocol is
gone, replaced by a real tool call the model cannot accidentally speak aloud.

---

## Decisions worth knowing

**The mode table refuses the operator too.** It would have been easy to let a
person set any mode they liked from the panel. But the reason a call cannot go
back from WRONG_NUMBER is not that the model is untrustworthy — it is that the
call has already said goodbye. One table, one answer, whoever is asking.

**A complex request does not hang up; a wrong number does.** They look like the
same kind of terminal state and they are not. A call being handed to a person
still has details to take down, so it stays on the line with different standing
orders. A wrong number has nothing left to say after goodbye.

**Mode switching is a tool, not a tag.** Version 1 had the model write
`[MODE:WRONG_NUMBER]` at the end of a reply and stripped it out before
speaking. That works until the day it does not strip cleanly and a caller hears
it. A function call cannot be spoken by accident.

**The second pass has no tools.** The obvious loop guard is a counter. This is
better: the model is not told "you have had your turn", it simply has nothing
to reach for, so the only thing it can do is talk to the caller.

**Tool results ride in as a caller-side message.** Both vendors have their own
protocol for handing a function result back, and they disagree about it in ways
that need call ids threaded through the whole layer. A plain message, marked as
what it is, reads the same to both and keeps the provider classes ignorant of
each other. It is deliberately not remembered: the history holds what was said
aloud, not the machinery under it.

**The language question is two sentences, not one.** It is the only thing the
agent says before anyone knows which language they want, so it goes out once in
each — as separate `say` messages, each tagged with its own language, so the
voice server reads the Bangla half in a Bangla voice instead of putting an
English accent on it. The whole greeting is one agent turn, so the microphone
opens once, at the end of it.

**Google gets one chance to refuse the second language.** Asking the recogniser
to listen for Bangla and English at once is what makes Banglish transcribe at
all, but it is not accepted with every model in every region. The first
rejection turns it off for the rest of the process rather than failing every
utterance the same way — the caller repeats one sentence and everything after
it works.

**An unrecognised utterance is reported, not dropped.** The voice server used
to say nothing when the recogniser produced nothing. Now it says so, and the
brain counts: one is a cough, two in a row is somebody who needs to be asked to
repeat themselves.

**Silence is measured in Java, not in the audio path.** The voice server knows
about frames; only the brain knows whether a call is quiet because nobody is
talking or because a model is thinking. A call mid-turn is skipped by the
sweep, which is why a slow reply cannot trigger "are you still there?".

**`TurnRunner` and `live_transcript.js` are splits, not new features.**
`ConversationBrain` and `live_call.js` both crossed the 300-line target this
phase. Both came apart along the same seam: running a call versus running one
turn of it, showing a call versus showing what was said on it.

---

## Verified

- `mvn clean package` builds and packages; **32 Java tests pass**.
- `python -m pytest` — **17 tests pass**, none needing a network, a cloud
  account or a microphone.
- The legality table is tested from every mode to every mode: a new caller can
  turn out to be anything, a known customer cannot be demoted back to a
  stranger, and neither terminal mode goes anywhere at all. A refused move
  changes nothing and writes nothing down; an accepted one is stored with the
  reason that was given for it.
- The tool layer is tested for what it does and what it refuses: a language the
  call does not speak, a mode change the call cannot make, a tool that does not
  exist, and arguments that are not JSON all come back as readable JSON
  refusals rather than exceptions. A wrong number leaves a farewell behind it;
  a complex request does not. A Bangla call is said goodbye to in Bangla.
- The prompt carries the current mode's standing orders and swaps them when the
  mode changes rather than stacking them.
- On the Python side: each half of the greeting is synthesised in its own
  language and the whole greeting is one agent turn; an utterance nobody could
  make out still reaches the brain and does not count as a turn; a language
  switch changes the voice for everything after it; a farewell is spoken before
  the call is hung up.
- 133 panel strings in both languages — the only entry without Bengali script
  is `settings.badge_placeholder`, which is the literal word PLACEHOLDER.
- Every panel JavaScript file parses as a module.

## What is not verified

No Docker, no PostgreSQL, no microphone, no Google credentials and no model API
key in this build environment. None of the following has run:

- `docker compose up --build` with three services
- A model actually choosing to call `set_mode` or `set_language` — the schemas
  are written to the shape both vendors document, but neither has seen them
- Google's `alternative_language_codes` on `latest_short`, and therefore
  whether the fallback path above ever fires
- Bangla speech recognition and the Bangla voice, and whether the transcript
  renders Bengali script end to end
- The inactivity sweep against a real silent call, and the six-second link
  grace
- End-to-end latency, and therefore whether a turn with a tool call in it still
  lands under two seconds — a turn that goes around twice is two model calls

`phase_04_test.md` walks all of it. Steps 6, 11 and 15 are the three that
decide the phase.

---

## Commit log

- Add the four-way screening machine, its legality table, and the mode transitions it writes down
- Give the model three actions it can take — language, screening, hanging up — behind one provider-neutral schema
- Split one turn out of the brain, and give it a tool-free second pass so a turn can never loop
- Ask which language at the greeting, in both, and carry the choice through recogniser, voice and wording
- Warn then hang up on a silent line, and let the operator overrule the screening from the panel

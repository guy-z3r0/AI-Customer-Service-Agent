# Phase 3 log — Brain in the loop: LLM providers, turn WS, English conversation

**Closed:** 2026-08-04
**Status:** built; the pure logic is unit-tested on both sides and the whole
project builds and packages. Not yet run against a live stack or a real API
key — see "What is not verified".

---

## What ships

The agent answers. Speak into the Live Call page and the reply comes back from
a language model that has been handed the active business's About text, its
services and prices, its policies, its common questions and its opening hours,
and has been told it may use nothing else.

Which model that is comes from Settings. Change **Provider** from `gemini` to
`openai`, press save, place another call, and the other company's API answers —
no restart, no code, no redeploy. A business may also override the choice for
itself alone.

With no key at all the call still connects, the caller is still greeted, and
every question gets the same polite apology instead of a stack trace. The panel
says why, once, in a toast.

---

## How a turn actually flows

```
browser mic ──16 kHz PCM16──▶ ws /ws/browser/{callId} ──▶ Endpointer ──600 ms silence──┐
                                                                                       ▼
                                                            SttStream (gcp | free) ─final text
                                                                                       │
                                        ws /ws/turn/{callId}   transcript_final ◀───────┘
                                                                     │
                                                       ┌─────────────▼──────────────┐
                                                       │ ConversationBrain          │
                                                       │  history + PromptBuilder   │
                                                       │  LlmRouter → Gemini/OpenAI │
                                                       │  deltas → SentenceSplitter │
                                                       └─────────────┬──────────────┘
                                        say{seq, text, last} ────────┘
                                                 │
                       TtsProvider ──PCM16──▶ browser speakers
                                                 │
                       spoken{seq, tTtsFirst} ───┴──▶ brain ──▶ call_message row
                                                                └─ /ws/live ──▶ panel
```

Two sockets, and neither carries the other's traffic. Audio never touches Java;
words never touch the audio socket. That split is what keeps the path between a
microphone and a recogniser as short as it can be, and it is where the
two-second budget is won or lost.

---

## Files added

**Java — the model layer** (`brain/llm/`)
`LlmProvider.java` (the interface), `LlmRequest.java`, `LlmStreamHandler.java`,
`GeminiProvider.java` (streamGenerateContent), `OpenAiProvider.java` (chat
completions, `stream:true`), `LlmRouter.java` (business override → Settings),
and `SseChat.java` — the transport, timeout and retry both vendors share.

**Java — the conversation** (`brain/`)
`ConversationBrain.java` (the turn lifecycle), `CallSession.java` (one call's
business, persona, knowledge, history and timings), `CallRegistry.java`,
`PromptBuilder.java`, `SentenceSplitter.java`.

**Java — the rest**
`api/TurnSocket.java` (`/ws/turn/{callId}`), `services/KbService.java`.
`Lang.java` grew the model's standing instructions and the three lines the
agent says when something has gone wrong, both languages.

**Tests**
`SentenceSplitterTest`, `PromptBuilderTest`, `LlmRouterTest` — 13 Java tests.
The Python suite grew to 13 as well, the turn tests rewritten around a stand-in
brain.

## Files changed

`CallLogService` now writes caller and agent lines separately and can fill in a
timestamp that arrives after the line it belongs to. `CallDtos.LineRequest`
became `LineToStore` — it is built by the brain, not posted over HTTP, so its
validation annotations went with the endpoint. `CallController` lost `/line`.
`WsConfig` mounts the turn socket. `pom.xml` gained `spring-boot-starter-test`.

`python-voice/java_link.py` is a websocket client instead of two HTTP posts.
`session.py` no longer composes a reply: it sends the caller's sentence up and
speaks what comes back, in order, from a queue. `requirements.txt` gained
`websockets==13.1`.

`live_call.js` shows which model is answering, hangs the latency badge on the
right line and splits it into model and voice time on hover, and toasts the
notice when no key is set.

## Files deleted

Phase 3's list — `api/CallContextController`, `CallContextResponse`,
`ChatMessageController`, `TranscriptController` — was already carried out in
Phase 1, when `services/` replaced `managers/`. Nothing was left to delete.

---

## Decisions worth knowing

**The turn socket carries one message BUILD_SPEC does not name: `spoken`.**
The spec gives Python `tSttFinal` and `tTtsFirst` and Java `tLlmFirst`, but
Java is what writes the row, so the third stamp has to travel. Python reports
the moment the first audio byte of a turn left for the caller, and the agent's
line is completed by whichever of the two halves lands second — the write
checks for a stamp that came early, and the stamp checks for a line that has
already been written.

**`say{seq}` is the turn number, not the sentence number.** The voice server
does not need to count sentences; it needs to know which turn's clock a sentence
stops. `last` is what ends the turn, and a turn that ends with nothing left to
say still sends an empty `say` carrying it, because that flag is also what
un-gates the microphone.

**A sentence is spoken the moment it closes.** Holding one back to find out
whether the next one exists would make `last` easy and cost most of the latency
budget, so the turn ends with an explicit marker instead.

**A full stop only ends a sentence once something follows it.** Otherwise
"500.00 BDT" is two sentences and a price is read out as one. This is the one
place the splitter is deliberately not clever: it waits for the next character
rather than trying to tell an abbreviation from an ending.

**Losing the link does not end the call straight away.** The voice server dials
back three times, and Java gives it six seconds before writing the call off. A
reconnected call keeps its history and its turn count, and the caller is not
greeted twice. Each session remembers which socket is currently carrying it, so
the socket that was replaced cannot end the call the new one is running.

**The system prompt is English only, and lives in `Lang.java` as constants.**
Nobody ever reads or hears it — it is standing orders to a model, not words for
a person, and the language it is written in has nothing to do with the language
the agent answers in. The three lines the agent *speaks* when things go wrong
are in the UI catalogue with the rest, in both languages, and the voice server
fetches them at the start of every call precisely so it can still apologise
when Java is what has gone missing.

**The greeting is an agent line with turn 0.** It is not an exchange: nobody
asked for it, and it has no latency reading. The panel skips it when counting
turns, which is why the first reply is numbered one rather than two.

**Two files BUILD_SPEC does not list.** `SseChat` holds the HTTP, the SSE
parsing, the 30-second timeout and the single retry, which both providers would
otherwise duplicate word for word. `SentenceSplitter` is thirty lines of buffer
logic that would have pushed `ConversationBrain` over its size budget for no
gain in clarity.

**One dependency added: `spring-boot-starter-test`.** The phase's core is Java
logic — where a sentence ends, what goes into a prompt, which provider answers
— and none of it needs a database, a key or a network. Leaving it untested
because the project had no test scope yet would have been the wrong trade. The
version comes from the Boot parent, so nothing is pinned by hand.

**`/api/call/{id}/line` is gone.** It was Phase 2's stand-in for the per-call
socket. Its DTO survives as `LineToStore` because the brain still needs a shape
to hand the log service.

---

## Verified

- `mvn clean package` builds and packages; 13 Java tests pass.
- `python -m pytest` — 13 tests pass, all without a network, a cloud account or
  a microphone. They assert that the caller's finished sentence reaches the
  brain with its timestamp, that the brain's sentences are spoken in the order
  it wrote them, that the reply is streamed in chunks rather than one lump,
  that the page is told when the agent takes and gives back the floor, that
  audio heard while the agent talks is discarded, that one turn produces one
  latency reading and not one per sentence, and that a brain which cannot be
  reached is apologised for out loud rather than met with silence.
- The Java tests cover the three pieces that decide whether an answer is any
  good: a full stop mid-price does not split a sentence, a Bangla dari does, a
  day left out of the opening hours is stated as closed rather than left for
  the model to guess, a business override picks up that vendor's default model
  rather than the other one's, and a placeholder key reports itself as not
  ready instead of throwing.
- 116 panel strings exist in both languages; the only entry without Bengali
  script is `settings.badge_placeholder`, which is the literal word
  PLACEHOLDER in both.
- Every panel JavaScript file parses as a module.

## What is not verified

No Docker, no PostgreSQL, no microphone, no Google credentials and no model API
key in this build environment. None of the following has run:

- `docker compose up --build` with three services
- Either vendor's streaming endpoint, and therefore the SSE parsing against
  real chunks, the tool-call fragments, or a rejected key's error body
- Whether Gemini accepts a history whose first entry is the agent's greeting.
  OpenAI certainly does; Gemini's `contents` normally start with a user turn,
  and if it objects the fix is to leave the greeting out of the request and
  name it in the system prompt instead
- The turn websocket end to end, the reconnection, and the six-second grace
- End-to-end latency, and therefore whether a turn lands under two seconds

`phase_03_test.md` walks all of it. Steps 8 and 12 are the two that decide the
phase: a grounded answer, and the same question answered by the other vendor.

---

## Commit log

- Add the swappable language model layer: one interface, Gemini and OpenAI streaming clients, and the router that chooses between them
- Add the conversation brain — per-call session, prompt built from the business's own knowledge, replies split into sentences as they stream
- Replace the voice server's HTTP reporting with a per-call turn websocket that reconnects, and carry all three latency stamps to the transcript
- Speak the model's sentences from a queue in Python, and apologise out loud when there is no key or no brain to reach
- Cover the splitter, the prompt and the provider choice with tests on both sides of the stack

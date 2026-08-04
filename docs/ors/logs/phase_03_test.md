# Phase 3 test script — Brain in the loop

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 2's test script passed, Docker Desktop is running,
and you have a working microphone and headphones. Steps 1–7 need no API key at
all — that is deliberate, and step 6 is the one proving it. From step 8 onward
you need a Gemini or OpenAI key, and step 12 needs both.

---

## A. It still builds and the tests still pass

**1.** Build the backend.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, and `Tests run: 13, Failures: 0, Errors: 0`. One
`WARN` about `llama-on-a-toaster` is a test doing its job, not a problem.

**2.** Run the voice server's tests.

```bash
cd python-voice && python -m pytest
```

*Expected:* `13 passed`.

**3.** Start everything.

```bash
docker compose up --build
```

*Expected:* three services ready, no stack traces. The backend log includes
Flyway finding nothing new to migrate — Phase 3 added no migration.

---

## B. No key at all: the agent apologises instead of breaking

**4.** Open http://localhost:8080 and go to **Settings**. Confirm
**Gemini API key** and **OpenAI API key** both still wear the amber
**PLACEHOLDER** badge. If you have already entered one, this section will not
show what it is meant to — skip to section C and come back later.

**5.** Go to **Live call**. Look at the facts panel before dialling.

*Expected:* a row reading **Answering model** with `gemini — needs a key`.

**6.** Press **Start browser call** and let the greeting finish.

*Expected:* the agent greets you by the business's name, exactly as
`Settings → the Template Business greeting` says. A toast appears in the bottom
right: "No language model key is set, so the agent can only apologise. Add one
in Settings." The greeting line appears in the transcript with an **Agent**
badge and **no** timing badge, and **Turns** still reads `0` — the greeting is
not an exchange.

**7.** Ask it anything. Wait for the reply, then press **End call**.

*Expected:* it says it cannot answer questions right now because it is not
connected to a language model, and asks you to try again later. **Turns** goes
to `1` and the agent's line gets a timing badge. One toast, not one per
question. Nothing in the backend log is an ERROR.

---

## C. With a key: a grounded answer

**8.** In **Settings**, paste a real key into **Gemini API key**, leave
**Provider** on `gemini`, and press **Save settings**. No restart.

*Expected:* the amber badge on that key disappears, the status line at the
bottom changes to `Language model: gemini — ready`, and the placeholder count
on the right drops by one.

**9.** Go to **Live call**, press **Start browser call**, and after the greeting
ask: *"What are your opening hours?"*

*Expected — this is the step that decides the phase:* the agent answers with
Saturday to Thursday, 10am to 8pm, closed Friday. Those hours are in the seeded
Template Business and nowhere in the code. The reply starts being spoken before
it has finished being written, and the agent's transcript line carries a timing
badge. Green means the whole turn landed inside two seconds.

**10.** Hover the timing badge.

*Expected:* a tooltip splitting the number in two — "Model … ms, voice … ms".
The two roughly add up to the badge.

**11.** Ask two more questions from the seeded knowledge, then something it
cannot possibly know — *"Do you sell motorbikes?"*

*Expected:* the first two are answered from the About, Services or Policies
text. The last one is refused: it says it does not have that information and
offers to pass you to a person. It does **not** invent an answer. **Median
reply time** in the facts panel is a real number.

---

## D. The provider swap

**12.** End the call. In **Settings**, paste a real key into
**OpenAI API key**, change **Provider** to `openai`, and save. Place a new call
and ask the opening-hours question again.

*Expected:* the same correct answer, from the other company's API. The facts
panel reads `openai — ready` and the status line agrees. The backend log for
this call says `answering as openai on gpt-4o-mini`, where the previous call
said `gemini`. Nothing was restarted between the two calls.

**13.** Swap back to `gemini` and place one more call.

*Expected:* it answers again. Two swaps, no restart, no redeploy.

---

## E. What was written down

**14.** Open a database shell.

```bash
docker compose exec postgres psql -U agent agent
```

**15.** Look at the last call's transcript and its timings.

```sql
SELECT seq, role, left(text, 40) AS said,
       t_stt_final IS NOT NULL AS stt,
       t_llm_first IS NOT NULL AS llm,
       t_tts_first IS NOT NULL AS tts
FROM call_message
WHERE call_id = (SELECT id FROM call_record ORDER BY started_at DESC LIMIT 1)
ORDER BY seq;
```

*Expected:* the greeting first, with all three stamp columns `f`. Then caller
and agent lines alternating. Every **agent** line that answered a question has
`stt`, `llm` and `tts` all `t` — all three stamps, which is the phase's
promise. Caller lines have only `stt`.

**16.** Check the reply times directly.

```sql
SELECT seq,
       extract(milliseconds FROM (t_tts_first - t_stt_final)) AS total_ms
FROM call_message
WHERE role = 'AGENT' AND t_tts_first IS NOT NULL
ORDER BY seq DESC LIMIT 5;
```

*Expected:* numbers, and with real Google speech credentials most of them under
2000. Without Google credentials the free offline voice is slower and some will
be over — that is the fallback path, not a failure of this phase.

**17.** Confirm the call was closed properly.

```sql
SELECT ended_at IS NOT NULL AS closed, termination_reason
FROM call_record ORDER BY started_at DESC LIMIT 3;
```

*Expected:* `t` and `hangup` for calls you ended with the button. Type `\q` to
leave.

---

## F. Things going wrong on purpose

**18.** Start a call, and while it is live stop the backend from another
terminal:

```bash
docker compose stop java-backend
```

Then say something.

*Expected:* the agent says it has lost its connection and has to end the call,
out loud, and the call ends. It does not sit in silence. The voice server's log
shows three attempts to dial back before it gives up.

**19.** Bring the backend back and place a normal call to confirm nothing is
stuck.

```bash
docker compose start java-backend
```

*Expected:* the panel's status line returns to green and a new call works.

**20.** Restart only the voice server during a call:

```bash
docker compose restart python-voice
```

*Expected:* the browser's audio socket drops, so the page reports the call was
cut off and hangs up — the caller's end really did go. Check the backend log:
the call is written off as `voice_link_lost` a few seconds later, not
instantly, and the record in `call_record` is closed rather than left open.

**21.** Leave the Live Call page mid-call by clicking **Businesses**.

*Expected:* the microphone light in the browser goes out, the call ends, and
returning to Live call shows an empty transcript and **Not on a call**.

---

## What passing means

Steps 9 and 12 are the phase. An answer that is right because it was read out
of the database, and the same answer from a different company's model with
nothing changed but a dropdown. Everything else is there to prove that neither
of those is a special case.

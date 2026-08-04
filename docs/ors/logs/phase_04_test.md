# Phase 4 test script — Screening + bilingual

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 3's test script passed, Docker Desktop is running,
and you have a working microphone and headphones. Steps 1–4 need no API key.
From step 5 onward you need a real Gemini or OpenAI key in Settings. Section E
is much more convincing with Google Cloud speech credentials in place, and
section F needs nothing but patience.

---

## A. It builds and the tables hold

**1.** Build the backend.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, and `Tests run: 32, Failures: 0, Errors: 0`. The
`INFO` lines about calls moving between modes and the `WARN` about
`llama-on-a-toaster` are tests doing their job.

**2.** Run the voice server's tests.

```bash
cd python-voice && python -m pytest
```

*Expected:* `17 passed`.

**3.** Start everything.

```bash
docker compose up --build
```

*Expected:* three services ready, no stack traces. Flyway finds nothing new to
migrate — Phase 4 added no migration.

**4.** Open http://localhost:8080 and go to **Live call**.

*Expected:* the facts panel now has a **Screening** row reading **New caller**
and a **Change it by hand** row with a dropdown, greyed out because no call is
running. The note at the top no longer mentions a later phase.

---

## B. The greeting asks which language

**5.** With a real model key in Settings, press **Start browser call** and
listen to the whole greeting without saying anything.

*Expected:* the business greeting, then the same question twice — "Would you
like to carry on in English, or in Bangla?" and its Bangla equivalent. With
Google credentials the second half is spoken by a Bangla voice, not an English
one reading Bengali. Both halves land in the transcript as agent lines with no
timing badge, and **Turns** still reads `0`.

**6.** Say **"English, please."**

*Expected — one of the three steps that decide the phase:* the agent carries on
in English. Ask it something from the knowledge base and you get the Phase 3
behaviour back, with a timing badge.

**7.** End the call, start a new one, and this time say **"বাংলা"** or
**"Bangla please"** when asked.

*Expected:* a violet row appears in the transcript reading "The call switched to
Bangla", the **Language** fact changes to **Bangla**, and everything the agent
says after that is in Bangla. With Google credentials the transcript of your own
speech comes back in Bengali script, not transliterated.

**8.** Still in Bangla, ask about the opening hours.

*Expected:* a correct answer, in Bangla, with the same hours from the seeded
Template Business. Without Google credentials the offline voice reads the Bangla
with an English accent — that is the documented fallback, not a failure.

---

## C. Screening: the four scenarios

**9.** Start a fresh call. After the greeting, say something ordinary —
*"I wanted to ask about your prices."*

*Expected:* **Screening** stays **New caller**. No note appears in the
transcript; the opening classification is not a change.

**10.** On the same call or a new one, ask for something the business plainly
cannot do — *"I need a refund of forty thousand taka and I want to speak to
your lawyer."*

*Expected:* within a turn or two the transcript gains a gold row reading
"Screening is now: Needs a person" with the reason the agent gave. The
**Screening** fact changes to match. The agent tells you a member of staff will
follow this up and starts collecting details. **The call does not hang up** —
there are details to take down.

**11.** Start a new call and be a wrong number: *"Is that the taxi office?"*,
and keep insisting.

*Expected — the second step that decides the phase:* a rose row appears reading
"Screening is now: Wrong number", the agent says a short goodbye, and the call
ends on its own. The **State** fact goes to **Call ended** without you touching
the End button.

**12.** Check what was written down.

```bash
docker compose exec postgres psql -U agent agent
```

```sql
SELECT c.termination_reason, c.final_mode, c.final_language,
       t.from_mode, t.to_mode, t.reason
FROM call_record c
JOIN mode_transition t ON t.call_id = c.id
WHERE c.id = (SELECT id FROM call_record ORDER BY started_at DESC LIMIT 1)
ORDER BY t.at;
```

*Expected:* two rows. The first has `from_mode` empty and `to_mode` of
`NEW_CUSTOMER` — the call opening. The second is `NEW_CUSTOMER` →
`WRONG_NUMBER` with the agent's own reason in plain words. The call's
`termination_reason` is `wrong_number` and its `final_mode` matches. Type `\q`
to leave.

---

## D. Overruling the agent

**13.** Start a call. While it is live, use the **Change it by hand** dropdown
and pick **Needs a person**.

*Expected:* the **Screening** fact and the transcript both update, exactly as
if the agent had decided it. The reason recorded is "changed by the operator".
The agent's next reply is noticeably different in tone — it has new standing
orders.

**14.** Now try to move that same call back to **Known customer**.

*Expected:* a red toast reading "A call cannot move to that from where it is
now.", and the dropdown snaps back to **Needs a person**. The same table that
refuses the model refuses you.

**15.** Start a fresh call and pick **Wrong number** from the dropdown.

*Expected — the third step that decides the phase:* the agent says its goodbye
and hangs up, from a decision you made rather than one it made.

---

## E. The caller who is hard to hear

**16.** Start a call and, after the greeting, mumble twice — two utterances the
recogniser will produce nothing from. Cover the microphone and speak, or say a
single syllable each time.

*Expected:* nothing at all after the first one. After the second, the agent
says "Sorry, I could not make that out. Could you say it once more, a little
slower?" **Turns** does not go up for either — an unheard utterance is not an
exchange.

**17.** (With Google credentials only.) Say a sentence that mixes both
languages — *"আমার একটা appointment লাগবে tomorrow."*

*Expected:* the transcript captures both halves rather than turning the English
into nonsense. If the backend log shows "Google will not take a second language
on latest_short here", the region does not support it: that one utterance is
lost, everything after it works with one language, and step 16's re-prompt is
what covers the gap.

---

## F. The line that goes quiet

**18.** In **Settings**, set **Warn after silence (seconds)** to `10` and
**Hang up after silence (seconds)** to `20`, and save. No restart.

**19.** Start a call, listen to the greeting, then say nothing at all. Time it.

*Expected:* at about ten seconds the agent asks "Are you still there?". At about
twenty it says it cannot hear anything, says goodbye, and the call ends. Both
lines appear in the transcript with no timing badge.

**20.** Start another call and speak once every few seconds.

*Expected:* neither line ever fires. Any activity resets the clock, including
your own speech being recognised.

**21.** Put both settings back to `20` and `40`.

---

## G. Nothing regressed

**22.** Place one ordinary call: greeting, choose English, ask two questions
about the business, then press **End call**.

*Expected:* correct grounded answers, timing badges on both agent lines, a
median reply time in the facts, and the call closed cleanly. Then:

```sql
SELECT seq, role, left(text, 40) AS said, language, mode_at_time,
       t_llm_first IS NOT NULL AS llm
FROM call_message
WHERE call_id = (SELECT id FROM call_record ORDER BY started_at DESC LIMIT 1)
ORDER BY seq;
```

*Expected:* the greeting and the language question first, then the exchanges.
Every line carries the language it was said in and the mode the call was in at
the time. Every agent line that answered a question has `llm` true.

---

## What passing means

Step 6 is the language question working at all. Step 11 is the agent screening
a call and ending it on its own. Step 15 is you overruling it and the same
thing happening. If those three work, and step 14 refuses you, the screening is
real rather than decorative — which is the whole of this phase.

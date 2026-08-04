# Phase 6 test script — handoff, logging, summaries, PII, metrics

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 5's test script passed, and either Docker Desktop is
running or you are using `.\run-local.ps1`. Steps 1–8 need no API key and no
microphone. Steps 9–13 need a working microphone and a real Gemini or OpenAI key
in Settings. Step 14 needs SMTP credentials and is optional.

**No migration this phase.** The schema is unchanged; `call_summary` has been
there since V1 and nothing has been writing to it until now.

**Steps 6, 9 and 12 decide the phase**: a call written up by the system, a call
written up by the model, and a caller's ID number reaching the colleague's inbox
as `[MASKED_NID]` while the transcript keeps it.

---

## A. It builds

**1.** Build the backend.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, and `Tests run: 55, Failures: 0, Errors: 0`.

**2.** Run the voice server's tests.

```bash
cd python-voice && python -m pytest
```

*Expected:* `17 passed`. This phase does not touch Python.

**3.** Start everything, then open http://localhost:8080.

```bash
docker compose up --build
```

*Expected:* the backend serves; Flyway reports the schema already at version 3
with nothing to do.

---

## B. The Dashboard finished

**4.** The Dashboard is the landing page. Look at the stat strip and the two
panels under it.

*Expected:* six tiles, ending in **Median reply** and **Slowest one in ten**.
Both read "no calls yet" until a call has produced a fully timed turn. Under
**What works right now**, the voice server reads **Off** with the line "Not
running. Start it before placing a call." if you have not started it — not
"Needs a key", which it does not need.

**5.** Look at **How calls end up**.

*Expected:* all four screening kinds are listed, each with a count and a bar,
including the ones at zero. A call that was never reclassified counts as **New
caller** — that is deliberate, so the ordinary call is not the one kind the
dashboard never shows.

---

## C. A call gets written up — with no API key

**6.** Place a browser call from the Live Call page, say anything at all, and
hang up. Then open **Call history** in the rail.

*Expected:* the call is at the top of the list, with the business, the caller
("not recognised"), the outcome, the turn count, the length, and **Written up:
Yes**. Turns counts what *you* said, not the lines on screen.

**7.** Click **Open** on that call.

*Expected:* the facts, then **What the call was about**. With no model key the
summary reads "This call was not written up: no language model was reachable
when it ended." — the transcript below it is the whole record. Under **What the
model made of it** there is a **Mode path** reading `NEW_CUSTOMER`, which came
from the call's own transitions rather than from any model. **How it was
screened** shows one step, "call started".

**8.** Click **Download as text**.

*Expected:* a file named `call-<id>.txt` saves. Open it: the headings, the
summary and every line of the transcript, readable by somebody with no access to
this panel. If the call was in Bangla, the headings are in Bangla too.

---

## D. A call written up by the model

From here you need a real key in Settings.

**9.** Place a call. Ask two or three real questions about the business and hang
up. Wait five seconds, then open the call in Call history.

*Expected:* **What the call was about** is now two or three sentences the model
wrote about *your* call. **What the model made of it** lists caller, intent,
outcome and sentiment beside the mode path. **Follow-up** lists anything the
call left to do, or says there is nothing.

**10.** Look at the transcript on that page.

*Expected:* every line, with a timing badge on each agent line that answered a
question — green under two seconds, red over. The badges match what the Live
Call page showed while you were on the call.

**11.** Go back to the Dashboard.

*Expected:* **Median reply** now shows a number, in the accent colour if it is
under 2000 ms. **Slowest one in ten** shows a number beside it, in plain type —
only one number on a screen wears the accent. The call is in **Recent calls**
with an **Open** button.

---

## E. Personal details, and the colleague who gets the call

**12.** Place a call. Say, out loud: *"My NID is 1990123456789 and my number is
01712345678."* Then ask for a refund the agent cannot approve, or simply ask to
speak to a person. Hang up when the agent says a colleague will follow it up.

*Expected, on the call:* the agent does not read your ID number back, and it
does not hang up on you — it stays on the line and takes down the details.

**13.** Open the call in Call history, then look at the backend log.

*Expected, three things at once:*

- The **transcript on the page** contains `1990123456789` and `01712345678` in
  full. That is on purpose: you are supervising the call.
- The **backend log** has a line per turn reading "had personal details in it;
  the model was given the masked version".
- The log then has the escalation email in full, because SMTP is still
  placeholders. Its subject is "A caller needs a person — Template Business",
  its recipient is the escalation contact on the business editor's Hours &
  handover tab, and inside it your ID number reads `[MASKED_NID]` and your phone
  `[MASKED_PHONE]`. The last line says so.

The call's outcome on the list is now **Needs a person**, and the Dashboard's
distribution has moved by one.

**14.** *(Optional — needs real SMTP.)* Put working SMTP settings in Settings and
repeat step 12.

*Expected:* the email arrives instead of being logged, at the escalation
contact's address, with the same body. The log says "Escalation email sent to
[…]" rather than "logged only".

---

## F. What it refuses to do

**15.** Open a call, then end it a second time — press **End call** on the Live
Call page for a call the voice server has already reported as finished.

*Expected:* nothing happens twice. One summary row, one email. Ending a call
that has already ended is not an error.

**16.** Change `PII_ENC_KEY`, restart, and open Call history.

*Expected:* the list still works. A caller whose contact details can no longer
be decrypted shows as "not recognised" rather than taking the page down — the
Phase 5 safety net, still holding.

**17.** Delete the escalation contact on the business editor's Hours & handover
tab, then place a call that needs a person.

*Expected:* the call still ends properly and is still written up. The log says
"Nobody is listed to escalate to" and prints the message that would have gone
out. Nothing crashes, and no caller hears about it.

---

## G. The words

**18.** Switch the panel to Bangla and walk the history page.

```bash
curl "http://localhost:8080/api/lang?lang=bn" | grep -o "history\.[a-z_]*"
```

*Expected:* every history, export and email string comes back in Bengali script.
There are 301 entries in the catalogue and exactly one has no Bengali in it:
`settings.badge_placeholder`, which is the literal word PLACEHOLDER.

**19.** Look at where the strings now live.

```bash
wc -l java-backend/src/main/java/com/ulab/agent/utils/Lang*.java
```

*Expected:* `Lang.java` around 200 lines and `LangPages.java` around 400. **This
is the one change in this phase that needs your agreement** — the catalogue
outgrew the 500-line file cap, so the per-page half moved into a second
package-private file. Both languages are still required on every entry and a
wording is still changed in one place. If you would rather keep one file, say so
and it goes back.

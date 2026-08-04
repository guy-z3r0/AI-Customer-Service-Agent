# Phase 7 test script — Twilio mode, a second business, polish

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 6's test script passed, and you have a real Gemini
or OpenAI key in Settings. Steps 1–8 need no new credentials. Steps 9–10 need a
Twilio account and ngrok. Step 12 needs a microphone.

**This phase adds three migrations.** V4 seeds the second business; V5 and V6
correct the model a clean install starts on. None of them touches a row you
have edited. Flyway applies all three on the next boot.

**Steps 4, 7 and 11 decide the phase**: a second business answering as itself,
a stranger no longer being mistaken for a customer, and the whole thing still
behaving with no Twilio credentials at all.

---

## A. It builds and migrates

**1.** Build and test both halves.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, `Tests run: 59, Failures: 0, Errors: 0`.

```bash
cd python-voice && python -m pytest
```

*Expected:* `22 passed`.

**2.** Start the stack and watch the log.

```bash
docker compose up --build
```

*Expected:* Flyway migrates to **version 6**, applying `4 - seed demo two`,
`5 - current default model` and `6 - faster default model` in order.

**3.** Check the model a clean install now starts on.

*Expected:* **Settings → Model** reads `gemini-3.1-flash-lite`. If you had
already chosen a model of your own, it is untouched — V5 and V6 only replace
the value they are correcting.

---

## B. The second business

**4.** Go to **Businesses**.

*Expected:* **Demo Courier** is listed alongside Template Business, inactive,
with 14 knowledge entries and 2 customers. Open **What it says** — the tabs are
a parcel service: same-day delivery at 80 BDT, a compensation cap, a
prohibited-items policy, open every day including Friday.

**5.** Switch the active business to **Demo Courier** in the top bar, then place
a browser call from the Live Call page and let it greet you.

*Expected:* it answers **"Demo Courier, this is Rafi speaking. You are talking
to an AI assistant."** — a different name, a different business, a different
voice on the page. No restart happened and no file was edited.

**6.** Ask it two things only Demo Courier knows: *"How much is same-day
delivery inside Dhaka?"* and *"Do you deliver on Friday?"*

*Expected:* 80 BDT for up to 1 kg booked before 2pm, and yes, every day 8am to
10pm. Neither fact exists anywhere except in `V4__seed_demo_two.sql`. Switch
back to Template Business and ask the same questions — it will not know them.

---

## C. The caller-matching fix

**7.** Still on Demo Courier, place a call and say, out loud:
*"Hello, my NID is 1990123456789 and my number is 01712345678."*

*Expected:* the agent says it **cannot find a record** with that number and asks
for your name. It must **not** greet you as "Example Sender One".

Before this phase it did exactly that. The model passes the number on as
`[MASKED_PHONE]`, that has no digits in it, and the old matching rule treated
"no digits" as "matches everybody" — so a stranger was answered from the first
customer's record. Open the call in **Call history** afterwards: **Caller**
should read *not recognised* and the outcome should still be **New caller**.

**8.** Now dial as a real customer: choose **D001** in the **Dial as** dropdown
and place the call.

*Expected:* greeted by name as Example Sender One, and the screening reads
**Known customer**. Matching still works — it is only the empty case that
changed.

---

## D. Twilio, if you have it

Skip to section E if you have no Twilio account. Nothing below is needed for
the phase to pass.

**9.** Follow the Twilio and ngrok walkthrough in
[docs/SETUP.md](../../SETUP.md#optional-twilio-for-calls-over-a-real-telephone-line),
fill in all seven settings, and reload the Live Call page.

*Expected:* the **Twilio call** button is now enabled and its tooltip is gone.

**10.** Press it and hold a short conversation.

*Expected:* your phone rings, or the browser connects as a Twilio device, and
the call runs through the same brain: the same greeting, the same knowledge, the
same live transcript in the panel, the same summary at the end. On a trial
account Twilio speaks its own "trial account" message first — that is Twilio,
not this app.

*If the call connects and then goes silent:* the **Public media URL** is stale.
ngrok gives a new address every restart on the free plan, and this is the single
most common cause.

---

## E. It behaves without Twilio

**11.** With every Twilio setting still on its placeholder, look at the Live
Call page and then ask the backend directly.

```bash
curl -i http://localhost:8080/api/twilio/token
curl -i -X POST http://localhost:8080/api/twilio/voice
```

*Expected, three things:*

- The **Twilio call** button is disabled, with the tooltip "Fill in the Twilio
  settings and the public media URL to enable this."
- `/api/twilio/token` answers **409** with
  `{"error":"Telephone calling needs its Twilio settings filled in first."}`
- `/api/twilio/voice` answers **200** with **TwiML**, not an error — a `<Say>`
  apologising and a `<Hangup/>`. That distinction matters: by the time Twilio
  asks this question there is a caller on the line, and an HTTP error would play
  Twilio's own error recording at them.

Browser calling works normally throughout.

---

## F. Latency

**12.** Place a browser call with a microphone and a real model key, ask three
or four questions, hang up, and open the **Dashboard**.

*Expected:* **Median reply** and **Slowest one in ten** both show numbers.
The median should sit near or under 2000 ms with the default model.

*Expected, and worth knowing before it surprises you:* roughly one turn in five
may stall for 15–18 seconds. That is documented and **unexplained** — see the
tuning pass in `phase_07_log.md`. It is not the model tier, not a retry, not a
tool call, and not IPv6 fallback; all four were tested. If the target matters
to your demo, this is the open thread.

**13.** Change **Model** in Settings to `gemini-3.6-flash`, place another call,
and compare.

*Expected:* noticeably slower — the tuning pass measured a median of 3823 ms
against 800 ms, which is the entire turn budget spent before the voice starts.
Change it back.

---

## G. The documentation

**14.** Read `README.md`.

*Expected:* it describes this app — Docker, the panel, the two businesses, the
seven-phase build record. Until this phase it still described version 1, with
JSON files, an `agent>` prompt and a `python-scripts/` folder that has not
existed since Phase 2.

**15.** Confirm the build record is complete and, from this phase, actually in
git.

```bash
ls docs/ors/logs/
git status --short docs/ors/logs/
```

*Expected:* seven logs and seven test scripts, and git can see them. The
`.gitignore` fix that made that true landed in Phase 6 — before it, its `logs/`
pattern had been quietly excluding the entire build record.

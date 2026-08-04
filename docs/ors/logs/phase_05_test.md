# Phase 5 test script — CRM + full editability

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 4's test script passed, Docker Desktop is running,
and you have a working microphone and headphones. Steps 1–5 need no API key.
From step 6 onward you need a real Gemini or OpenAI key in Settings.

**This phase adds a migration.** V3 creates one function; it does not touch a
single row. Flyway applies it on the next boot.

---

## A. It builds, migrates, and the console is gone

**1.** Build the backend.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, and `Tests run: 41, Failures: 0, Errors: 0`.

**2.** Run the voice server's tests.

```bash
cd python-voice && python -m pytest
```

*Expected:* `17 passed`.

**3.** Start everything.

```bash
docker compose up --build
```

*Expected:* the backend log shows Flyway migrating to version **3**. There is no
`agent>` prompt any more, and nothing waits for input — the backend just serves.
Press Ctrl-C and it stops cleanly.

**4.** Confirm the new function exists.

```bash
docker compose exec postgres psql -U agent agent -c "SELECT try_decrypt(NULL, 'anything') IS NULL AS handles_null;"
```

*Expected:* `t`.

**5.** Open http://localhost:8080. The rail now has a working **Clients**
section, and **Businesses** has an extra column.

*Expected:* Businesses shows **What it says**, **Edit** and **Delete** on every
row. Clients lists C001 and C002 with their phone numbers and email addresses in
full — those are stored encrypted and decrypted for this page.

---

## B. Editing what the agent says

**6.** With a real model key in Settings, place a call and ask
*"What are your opening hours?"*. Note the answer, then hang up.

**7.** Go to **Businesses → What it says** on Template Business. Open the
**Questions** tab, edit the opening-hours entry, and change the closing time
from 8pm to 9pm. Save. Place a new call and ask the same question.

*Expected — the step that decides half the phase:* the agent now says 9pm.
Nothing was restarted and nothing was redeployed. The old answer is gone.

**8.** On the **Services** tab, add an entry — *"Example rush job - same day, 1500 BDT."* —
then use **Move up** to put it first. Place a call and ask what services are
offered.

*Expected:* the new service is mentioned, and it is mentioned early. The order
on the page is the order the model reads.

**9.** On the **Persona** tab, change the agent's name from Ayesha to something
else and save. Place a call.

*Expected:* the greeting is unchanged — it is its own field — but ask *"who am I
speaking to?"* and it gives the new name.

**10.** On the **Hours & handover** tab, clear both boxes for Thursday and save.
Place a call and ask if you are open on Thursday.

*Expected:* it says you are closed on Thursday. A day left blank is told to
callers as closed rather than left for the model to guess.

**11.** Add an escalation contact with a real-looking email and priority 1.

*Expected:* it appears in the list immediately. Nothing is emailed yet — that
arrives in Phase 6.

---

## C. Existing customers

**12.** Go to **Live call**. In **Dial as**, choose **C001 — Example Customer
One**, and start a call.

*Expected — the second step that decides the phase:* the agent greets you by
name without being told who you are. The **Caller** fact reads Example Customer
One, the **Screening** fact reads **Known customer**, and the transcript opens
with that classification rather than "New caller".

**13.** Ask it something that its notes cover — *"do you have anything on file
about me?"*

*Expected:* it refers to the note and the past issues seeded on C001, and does
not ask for your name or number, because the record already has them.

**14.** End the call. Start a new one with **Dial as** set back to *Somebody not
on the records*, and part-way through say *"my customer code is C001"*.

*Expected:* a jade row appears in the transcript reading "Recognised: Example
Customer One", the **Caller** fact fills in, and **Screening** moves to **Known
customer**. From that point it answers as it did in step 13.

---

## D. New customers

**15.** Start a fresh call as an unknown caller. Say you would like to book
something, and when asked give a name and a phone number that are not on the
list.

**16.** While still on the call, open **Clients** in a second browser tab.

*Expected — the third step that decides the phase:* the person you just invented
is in the list, with a new C-code, their number, and a line under **On record**
saying what they called about. It was written during the call, by the agent, not
by you.

**17.** End the call and check what was stored.

```bash
docker compose exec postgres psql -U agent agent
```

```sql
SELECT client_code, name, past_issues_json,
       phone_enc IS NOT NULL AS phone_is_stored,
       length(phone_enc) > 20 AS phone_looks_encrypted
FROM client ORDER BY client_code;
```

*Expected:* the new row is there. `phone_is_stored` and `phone_looks_encrypted`
are both `t` — the bytes in the column are pgcrypto output, not the number you
said out loud. Confirm that directly:

```sql
SELECT encode(phone_enc, 'escape') NOT LIKE '%017%' AS not_readable FROM client LIMIT 1;
```

*Expected:* `t`. Type `\q` to leave.

---

## E. Editing customers by hand

**18.** On **Clients**, press **Add a customer**.

*Expected:* the code box is pre-filled with the next free C-number. Fill in a
name and save; the row appears.

**19.** Edit that customer and change their code to `C001`.

*Expected:* the save is refused, and the message appears **under the code box**,
not as a general failure. The dialog stays open with what you typed still in it.

**20.** Edit them again and put three lines into **What they have needed
before**. Save, then place a call as that customer and ask about their history.

*Expected:* the agent knows all three.

**21.** Delete that customer.

*Expected:* a confirmation that names what will be lost and does not ask a
question, with the destructive button on the right and the focus on **Cancel**.
Pressing Escape closes it and deletes nothing.

---

## F. When the encryption key changes

**22.** Stop the stack. Change `PII_ENC_KEY` in `.env` to anything else, and
start it again.

```bash
docker compose down && docker compose up --build
```

**23.** Open **Clients**.

*Expected:* the page still works. Every customer written under the old key shows
their name, notes and history as before, with an amber **•••** badge where their
phone and email were; hovering it explains why. Nothing 500s, and the backend
log has no stack trace. This is what V3 exists for.

**24.** Add a new customer now, under the new key.

*Expected:* their contact details save and display normally. Old rows and new
rows sit in the same list, and only the old ones are unreadable.

**25.** Put `PII_ENC_KEY` back to what it was and restart.

*Expected:* the old rows read again. Nothing was lost — it was never decryptable
with the wrong key, only unreadable.

---

## G. Nothing regressed

**26.** Place one ordinary call as an unknown caller: choose English, ask two
questions about the business, then press **End call**.

*Expected:* correct grounded answers, timing badges on both agent lines, and the
call closed cleanly — exactly as it behaved in Phase 4.

**27.** Try deleting a business you do not need.

*Expected:* the confirmation says its knowledge, its customers and its calls go
with it. After deleting, Clients and the business editor no longer offer it, and
the active-business dropdown at the top has one fewer entry.

---

## What passing means

Step 7 is an edit in the browser changing what the agent says on the next call.
Step 12 is a caller being greeted by name from a record. Step 16 is the agent
writing a record itself, mid-call. Those three are the phase. Step 23 is the one
that says the encryption is a safety net rather than a trap.

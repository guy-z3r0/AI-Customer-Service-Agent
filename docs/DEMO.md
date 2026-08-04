# Demo script

Twenty minutes, seven beats. Read the bold lines out loud; the rest is for you.

---

## Before you start (5 minutes)

Open PowerShell in the project folder:

```
powershell -ExecutionPolicy Bypass -File run-local.ps1
```

Two console windows open and the browser lands on the panel. The first run
downloads PostgreSQL, so give it a minute. **Leave both windows open** — closing
one stops that half of the app.

Then paste your Gemini key: **Settings → Gemini API key → Save settings**.
Within ten seconds the bottom bar changes to `Language model: gemini — ready`.

Wear headphones. On speakers the agent hears its own voice and answers itself.

> Docker works too, once Docker Desktop is running: `docker compose up --build`.
> The script above needs nothing running and is what has been tested.

---

## Beat 1 — Overview · "what is this"

Land on **Overview**.

> **This is a bilingual AI agent that answers customer service calls for small
> businesses. Right now it knows four businesses, fifty-three facts about them,
> and five customers.**

Point at **What works right now**. Every line says Ready, Free fallback, or
Needs a key.

> **The system tells you what it can do rather than making you find out.**

---

## Beat 2 — Businesses · "where the knowledge lives"

Click **Businesses**.

> **Each business has its own knowledge, its own personality and its own
> customers. The agent can only say what is in here — it cannot invent a price.**

Click **What it says** on Template Business. This is the exact text the agent is
given before a call.

> **Nothing is hidden in the code. This is the whole brief the AI gets.**

---

## Beat 3 — The editor · "change it while it runs"

Click **Edit** on Template Business. Walk the six tabs: About, Services,
Policies, Questions, Persona, Hours & handover.

Change one service price. Save.

> **No restart, no deployment. The next call uses the new price.**

Keep this one — you will prove it in Beat 5a.

---

## Beat 4 — Clients · "it remembers people"

Click **Clients**. You see C001 and C002 with phone numbers and emails.

> **Those are stored encrypted. The database holds ciphertext; the panel
> decrypts them for you. A stolen copy of the database is not a stolen contact
> list.**

**On record** is how many past issues each customer has. Open one to show them.
Remember **C001 — Example Customer One** for the next beat.

---

## Beat 5a — A stranger calls

Click **Live call**. Leave **Dial as** on nobody. **Start browser call**, allow
the microphone.

Say, pausing after each so the agent knows you have finished:

1. *"Hello, what are your opening hours?"*
2. *"How much is example service one?"* ← the price you edited in Beat 3
3. *"Thanks, goodbye."*

While it runs, point at:

- The transcript filling in, tagged **Caller** and **Agent**
- The green badge on each agent line — **the time from me stopping to the first
  sound of the answer**
- **Screening** — the agent worked out for itself that this is a new customer

> **The price it just quoted is the one I typed two minutes ago.**

Press **End call**.

---

## Beat 5b — A regular calls · the one to land

Set **Dial as** to **C001 — Example Customer One**. Start the call.

> **Listen to how it opens.**

It greets them by name, because it looked them up.

Ask: *"Did you sort out my last problem?"*

> **It has their history. Nobody typed that into the prompt — the agent asked
> the database for it mid-call.**

Press **End call**.

---

## Beat 6 — Back to Overview · "and it was all written down"

Click **Overview**.

> **The call is logged, the turns are counted, and the median reply time is
> measured — not estimated.**

---

## If something goes wrong

**Nothing happens when I press Start browser call.**
Check the bottom bar says `Voice server: up`. If not, the voice window closed —
re-run `run-local.ps1`.

**The agent hears itself / talks in circles.**
Headphones. Every time.

**Backend window shows an error and exits.**
Close both windows and run the script again — it clears its own leftovers.

**Replies are slow, or the transcript only appears after I stop talking.**
Expected without a Google Cloud key. It is using the free speech fallback.
Say so; it is a deliberate feature, not a fault.

---

## If asked what is not finished

Be straight about it:

- **Call history and post-call summaries** — next phase
- **Escalation email to a human** — next phase
- **Real phone numbers via Twilio** — last phase, browser calling is the default
- **Google Cloud speech** — works without it, better with it

Everything else you just showed is done and running.

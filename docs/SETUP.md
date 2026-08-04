# Setup — AI Customer Service Agent

This guide takes a fresh clone to a running control panel. You do not need a
single API key to finish it. Every credential ships as a fake `PLACEHOLDER_`
value, and the features that need one switch themselves off politely until you
fill it in.

---

## 1. Prerequisites

| You need | Why | Check with |
|---|---|---|
| Docker Desktop (with Compose) | runs Postgres and the backend together | `docker compose version` |
| A browser | the control panel is a web page | — |

If you would rather run things by hand instead of with Docker, you need Java 21
and Maven 3.9 as well, plus a PostgreSQL 16 server of your own. See
[Running without Docker](#6-running-without-docker) at the end.

---

## 2. Make your `.env`

From the repository root:

```bash
cp .env.example .env
```

That is all this step needs. The copied file already works. If you want to look
at what is inside:

- `POSTGRES_PASSWORD` — the database password. Change it if you like; Compose
  and the backend both read the same value, so they stay in step.
- `PII_ENC_KEY` — the key that encrypts customer phone numbers and email
  addresses inside the database. **Change this before you store anyone's real
  contact details**, and then leave it alone: rows encrypted with the old key
  cannot be read with a new one.
- `GOOGLE_APPLICATION_CREDENTIALS` — where the Google Cloud speech key lives.
  Leave it as it is if you have no key; the voice server falls back to the free
  offline speech providers on its own.

---

## 3. Start the stack

```bash
docker compose up --build
```

The first run takes a few minutes because it downloads and builds everything.
You are ready when the log settles and shows a line like
`Started Main in 4.2 seconds`.

What just happened:

1. Postgres started with an empty database.
2. Flyway created every table (`V1__baseline.sql`) and filled in the starter
   content (`V2__seed.sql`): a business called **Template Business** with a
   full example knowledge base, an example persona, opening hours, an
   escalation contact and two example customers.
3. The backend imported anything it found in `java-backend/data/businesses/`,
   which is where version 1 of this project kept its data.

Open **http://localhost:8080**.

---

## 4. A tour of the panel

**The rail down the left** lists the six sections. **Businesses**, **Settings**
and **Live call** are built; the other three say which phase they arrive in.

**The bar at the top** shows which business is active — the one whose knowledge
and persona the next call will use. Change it from the dropdown at any time.

**The bar at the bottom** is the health line. Right now it should say:

```
Database: up   Voice server: up   Language model: gemini — needs a key
```

with a count on the right of how many settings are still placeholders. That
count going down is the shape of the rest of this guide.

**Businesses** lists what is in the database: name, handle, contact, how many
knowledge entries and customers each has, and which one is active. **What it
says** opens the editor behind each one. The import button re-runs the import of
the old JSON files, which is safe to press as often as you like — anything
already imported is left alone.

**Customers** is the list the agent recognises callers from. Phone numbers and
email addresses are encrypted in the database and shown in full here.

---

## 5. Entering your keys

Go to **Settings**. Every value can be changed while the app is running; there
is no restart. A key still holding its stand-in wears an amber **PLACEHOLDER**
badge.

Secrets are handled a little differently. Once you save a real key, the panel
only ever shows you a mask of it (`••••a9f2`) and the field starts empty. An
empty secret field on save means "keep what is stored", so you can edit
anything else on the page without retyping your keys.

### Now: the language model

Without one of these the agent still answers the phone, but every reply is the
same apology — it has nothing to think with. Fill in **one** of them:

| Setting | Where to get it |
|---|---|
| `Gemini API key` | https://aistudio.google.com/apikey |
| `OpenAI API key` | https://platform.openai.com/api-keys |

Then set **Provider** to `gemini` or `openai` to match. Press **Save settings**.
The health line at the bottom should change to `Language model: … ready`.

### Optional: Google Cloud speech

Needed for the good voices and the streaming speech recognition. Without it the
voice pipeline falls back to the free offline providers, which is slower and
rougher but works with no account at all.

1. In the Google Cloud console, enable **Cloud Speech-to-Text** and
   **Cloud Text-to-Speech**.
2. Create a service account, download its JSON key.
3. Save it as `secrets/gcp-credentials.json` in this repository.

`secrets/` is ignored by git, so the key cannot be committed by accident.
`secrets/gcp-credentials.json.PLACEHOLDER` shows the shape of the file.

### Later: escalation email (Phase 6)

`SMTP host`, `SMTP port`, `SMTP username`, `SMTP password`, `Send from address`.
A Gmail account with an app password works, and so does a Mailtrap inbox if you
would rather test without emailing anyone real. Use port **587**, not 465. Until
these are set, a call that needs a human is written to the log instead of
emailed — nothing breaks.

**[KEYS_FOR_TESTING.md](KEYS_FOR_TESTING.md)** walks the speech and email
credentials properly, with links and the path gotcha that catches people running
without Docker.

### Later: Twilio (Phase 7, optional)

`Account SID`, `Auth token`, `API key SID`, `API key secret`, `TwiML app SID`,
`Caller number`, and a `Public media URL` (an ngrok address, because Twilio has
to reach your machine from the internet). Until these are set, the Twilio button
on the Live Call page is disabled and browser calling works as normal.

---

## 6. Editing Template Business

Template Business exists so there is something real to talk to before you have
typed anything of your own. Its knowledge base is deliberately obvious filler —
"Example service one", "Example warranty" — so nothing fake ever survives into a
demo unnoticed.

Go to **Businesses** and press **What it says**. The six tabs are everything a
call reads:

| Tab | What it decides |
|---|---|
| About, Services, Policies, Questions | The only facts the agent is allowed to state. Order matters — what is first is read most carefully. |
| Persona | Who it says it is, how it should sound, and the greeting it opens with in each language. |
| Hours & handover | When you are open, and who gets a call the agent cannot settle. A day left blank is told to callers as closed. |

Nothing here is copied anywhere. The prompt for the next call is assembled from
these rows at the moment it is placed, so a sentence changed on this page is a
sentence the agent says a minute later, with no restart.

A business may also name its own model provider on the Persona tab, overruling
the Settings page for its calls alone. Leave those two fields blank unless you
have a reason.

---

## 7. Running without Docker

```bash
# a Postgres 16 of your own, with a database and user called "agent"
createdb agent

# then, from the repository root
cd java-backend
DB_URL=jdbc:postgresql://localhost:5432/agent \
POSTGRES_USER=agent POSTGRES_PASSWORD=your-password \
PII_ENC_KEY=your-key \
mvn spring-boot:run
```

The panel is at http://localhost:8080 as before. Note the backend binds to
`127.0.0.1` when run this way, which is deliberate — the panel has no login and
is not meant to be reachable from the network.

---

## 8. If something goes wrong

**The panel loads but the health line says `Database: down`.**
Postgres has not finished starting, or the password in `.env` does not match the
one the database volume was created with. If you changed `POSTGRES_PASSWORD`
after the first run, the old volume still has the old password: remove it with
`docker compose down -v` and start again. That deletes everything in the
database, so only do it while the data is still throwaway.

**Flyway refuses to start, complaining about a checksum.**
A migration file was edited after it had already run. Restore the file, or wipe
the database as above.

**The Businesses page is empty.**
The seed did not run. Check the backend log for `V2__seed` and for any Flyway
error above it.

**I want to start over.**

```bash
docker compose down -v
docker compose up --build
```

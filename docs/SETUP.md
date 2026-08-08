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

**Turn on the commit guard while you are here.** Once per clone:

```bash
git config core.hooksPath .githooks
```

That makes git run `.githooks/pre-commit`, which refuses a commit carrying
something that looks like a credential, or anything under `secrets/`, `.env` or
`java-backend/data/`. It is one line and it is the control that would have
stopped this project's worst security finding — an API key committed on day one
and present in every commit after it. Install
[gitleaks](https://github.com/gitleaks/gitleaks) for the thorough version; the
hook falls back to a small pattern check without it.

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

**The rail down the left** lists the six sections, all of them built:
**Dashboard** for what the system holds and what works right now, **Live call**
to place one, **Businesses** and **Clients** for what the agent knows,
**Call history** for what it has done, and **Settings**.

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

### Speech recognition and the agent's voice

Without any of this the app still makes calls: it falls back to the free
recogniser and to whatever voices Windows or the container already has. Those
voices are the robotic ones, and **they are almost certainly English only** —
which is what makes Bangla sound wrong. See
[Bangla](#bangla-needs-its-own-voice) below.

> **Read this before you take a real customer call on the free path.** The
> credential-free recogniser is `SpeechRecognition.recognize_google()`, which
> posts the caller's audio to an **undocumented Google endpoint using a public
> key baked into that library**. There is no contract behind it, no retention
> statement, and no way to audit what happens to the recording. It is genuinely
> convenient for a demo and it is the wrong place to send a real customer saying
> their national ID number out loud. Setting up the credentials below moves you
> onto Google Cloud proper, where there is an agreement. The Dashboard says
> which one you are on.

**What you get for setting it up:** streaming recognition instead of
record-then-send, natural Neural2 voices, and Bangla that actually sounds like
Bangla.

**1. Create a project.** https://console.cloud.google.com/ → the project
dropdown → **New Project**.

**2. Turn on billing.** https://console.cloud.google.com/billing → link a
billing account. The speech APIs will not enable without one, even to use the
free monthly allowance. Check current prices yourself before relying on them:
[speech-to-text](https://cloud.google.com/speech-to-text/pricing) ·
[text-to-speech](https://cloud.google.com/text-to-speech/pricing).

**3. Enable both APIs.** Open each and press **Enable**:

- https://console.cloud.google.com/apis/library/speech.googleapis.com
- https://console.cloud.google.com/apis/library/texttospeech.googleapis.com

**4. Make a service account.**
https://console.cloud.google.com/iam-admin/serviceaccounts → **Create service
account** → name it `voice-server` → give it the role **Cloud Speech Client**,
and nothing broader.

**5. Download its key.** Open the account → **Keys** → **Add key → Create new
key → JSON**. Save the file as `secrets/gcp-credentials.json` in this
repository. `secrets/` is gitignored, so it cannot be committed by accident;
`secrets/gcp-credentials.json.PLACEHOLDER` shows the shape.

**6. Point the app at it — and read this if you are not using Docker.** The
**Google credentials file** setting is `./secrets/gcp-credentials.json`, a
*relative* path:

| How you run it | Where that lands | Works? |
|---|---|---|
| `docker compose up` | `/app/secrets/…`, which is your `secrets/` folder mounted in | yes |
| `run-local.ps1` or `mvn spring-boot:run` | `java-backend/secrets/…` and `python-voice/secrets/…`, neither of which exists | **no** |

So outside Docker, set that setting to an **absolute** path:

```
F:\3.GitHub_ORS\AI-Customer-Service-Agent\secrets\gcp-credentials.json
```

The Dashboard's **Speech recognition and voice** line changes from "Free
fallback" to "Ready" within a few seconds when the app can really see the file.
That line is a reliable test — it checks for a real, non-empty file at exactly
that path.

**7. Pick the voices.** Settings has an **English voice** and a **Bangla voice**
dropdown, listing what this installation can really speak with. It reads them
from the voice server, so it shows Google's voices once step 6 is done and the
machine's own before that. Leave either on *"Whichever the provider picks"* to
let it choose.

#### Bangla needs its own voice

A voice reads letters; it does not translate. Give Bengali text to an English
voice and you get English pronunciation of Bengali script, which is the
gibberish you may have already heard. The agent now picks a voice matching the
call's language — but it can only pick from what exists.

**A fresh Windows install has English voices only.** If Settings shows a red
line saying no voice speaks Bangla, that is this. Two ways out:

- **Google Cloud** (recommended, and the reason to do the steps above) — its
  `bn-IN` voices are far better than anything offline. Recognition uses
  `bn-BD`; the voice uses `bn-IN`, because that is where Google has Bangla
  voices.
- **A Windows Bangla voice** — Settings → Time & language → Language & region →
  **Add a language** → বাংলা, and tick **Speech** among the optional features.
  Then restart the voice server so it re-reads the list. Availability varies by
  Windows edition, and the quality is well below Google's.

Until one of those exists, Bangla replies come out in an English voice. The
panel says so rather than pretending otherwise.

### Escalation email

When the agent hands a call to a person, the colleague gets an email with the
summary, the reason and the masked transcript. Until SMTP is configured that
email is written to the log in full instead — the escalation is never lost, it
just does not travel.

**Five settings:** `SMTP host`, `SMTP port`, `SMTP username`, `SMTP password`,
`Send from address`. Pick one of three ways to fill them:

| | Host | Port | Good for |
|---|---|---|---|
| **Mailtrap** (easiest to test with) | `sandbox.smtp.mailtrap.io` | 587 | Catches mail instead of delivering it, so nothing can reach a real person by accident. No card, no phone number. https://mailtrap.io/ |
| **Gmail** | `smtp.gmail.com` | 587 | Landing in a real inbox you own |
| **Brevo** | `smtp-relay.brevo.com` | 587 | Reaching real addresses without involving your personal Gmail. https://www.brevo.com/ |

**For Gmail you need an app password, not your normal one.** Turn on 2-Step
Verification first (https://myaccount.google.com/signinoptions/two-step-verification),
then create one at https://myaccount.google.com/apppasswords. Google shows the
16-character password once. `Send from address` must be that same Gmail address —
Gmail refuses to send as anyone else.

**587 and 465 both work.** On 587 the connection starts in the clear and is
upgraded to TLS, and the upgrade is now *required* — a server that will not do
it gets nothing rather than your password in plain text. On 465 the connection
is encrypted from the first byte instead. Either is fine; 587 is what the three
relays above hand you. (Earlier versions of this page said 465 would simply time
out. That was true of the app at the time, not of SMTP.)

**Then say who receives it.** The seeded businesses list a fake escalation
contact, so even perfect SMTP settings would send the email nowhere useful. Go
to **Businesses → What it says → Hours & handover** and put a real address in
*"Who takes a call the agent cannot"*.

**Logging in is a setting, not a guess.** `SMTP auth` is on by default and is
what nearly every relay wants. Turn it off only for a relay on your own network
that takes mail from anybody and refuses a login attempt.

If a send fails the backend tries once more, then writes the message to the log.
By default that is the summary plus a count of the withheld transcript lines,
because the body of an escalation is a whole call and application logs are not
treated as customer records. Turn `Log unsent email body` on while you are
debugging a relay to see the lot.

**[KEYS_FOR_TESTING.md](KEYS_FOR_TESTING.md)** covers the same credentials
oriented around the Phase 6 test script, including which test steps need which.

### Optional: Twilio, for calls over a real telephone line

Browser calling needs no accounts and is the demo default. Twilio adds a real
phone number. Until every setting below holds a real value the **Twilio call**
button stays disabled with a tooltip saying so, and nothing else changes.

**You will need:** a Twilio account (https://www.twilio.com/try-twilio) and
ngrok (https://ngrok.com/download). ngrok is not optional — Twilio's media
stream has to reach your machine from the internet, and your machine is behind
a router.

**1. Start ngrok** against the voice server, not the panel:

```bash
ngrok http 8090
```

Copy the forwarding host it prints — the `abc123.ngrok-free.app` part, without
`https://`. That goes in **Public media URL**. It changes every time you restart
ngrok on a free account, and a stale one is the single most common reason a
Twilio call connects and then goes silent.

**2. In the Twilio console** (https://console.twilio.com/):

| Where | What to copy |
|---|---|
| Account dashboard | **Account SID** |
| Account dashboard | **Auth token** |
| Account → API keys & tokens → Create API key (Standard) | **API key SID** and **API key secret** — the secret is shown once |
| Phone Numbers → Manage → Buy a number (a trial gives you one free) | **Caller number** |
| Voice → TwiML → TwiML Apps → Create | **TwiML app SID** |

On the TwiML App, set its **Voice Request URL** to your ngrok address plus the
path, with the method **POST**:

```
https://abc123.ngrok-free.app/api/twilio/voice
```

Note that this one points at ngrok forwarding to **8080**, the panel, while the
media URL points at **8090**, the voice server. If you only want to run one
ngrok tunnel, point it at 8080 and put your backend behind it — but two tunnels
is simpler to reason about, and ngrok's free plan allows one at a time, so most
people run the media tunnel and use a paid plan or a second machine for the
webhook.

**3. Paste all seven values into Settings** and save. Reload the Live Call page:
the **Twilio call** button is now enabled. Press it and the call runs through
exactly the same brain, knowledge base and transcript as a browser call — the
only thing that changed is who carries the audio.

**Trial account caveats**, which will affect your demo:

- A trial can only call **verified** numbers. Verify your own under Phone
  Numbers → Verified Caller IDs before you test.
- Twilio speaks a **"you have a trial account"** message before connecting every
  call. It is not coming from this app and cannot be suppressed on a trial.
- Trial credit is small. A demo costs cents, but it does run out.

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

## 8. Call behaviour you can tune

**Settings → Call behaviour** holds two numbers, both in seconds:

| Setting | What it does |
|---|---|
| `Warn after silence` | How long a caller may say nothing before the agent asks "Are you still there?" |
| `Hang up after silence` | How long before it says goodbye and ends the call |

Both are measured **from the moment the agent stops speaking**, not from when
the call connected. That distinction matters: the greeting and the bilingual
language question take around thirteen seconds to read out, so a clock started
at connect would spend most of the allowance before the caller could get a word
in. If the agent seems to interrupt you almost immediately, check that the
voice server is running — it is the half that reports when the audio has
finished playing.

---

## 9. If something goes wrong

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

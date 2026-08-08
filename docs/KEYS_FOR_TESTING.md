# What to set up before testing Phase 6

Everything in this project boots with fake credentials, so you can run most of
`docs/ors/logs/phase_06_test.md` today with nothing but the model key you
already have. This file is about the rest: the two credentials that turn on the
parts of Phase 6 that are currently only being *simulated*, and the two settings
that are not credentials but will bite you if you skip them.

Nothing here asks you to give a key to anyone. Every value is typed into your
own machine — the Settings page, your `.env`, or a file in `secrets/`.

---

## 1. What each one unlocks

| What | What it turns on | Which test steps need it | Cost |
|---|---|---|---|
| **Escalation email (SMTP)** | Step 14 — the email actually arrives instead of being printed to the log | 14 only (13 works without it) | Free |
| **Google Cloud speech** | Real speech recognition and a natural voice, and the reply-time numbers on the Dashboard | 9, 10, 11 read properly; 4 stops saying "Free fallback" | Free allowance, **needs a card on file** |
| **`PII_ENC_KEY`** | Nothing new — but step 16 tests what happens when you change it | 16 | Free |
| **Escalation contact address** | Who the Phase 6 email is addressed to | 13, 14, 17 | Free |
| ~~Twilio~~ | Nothing in Phase 6 — it is Phase 7 | none | skip for now |

**You can pass the phase without either credential.** Steps 1–13 and 15–19 all
run on placeholders; that is the whole design. What you gain by setting them up
is seeing the two things that are currently only *described* in a log line: a
real email landing in a real inbox, and a real voice on a real call.

If you only do one, do **the email** — it is fifteen minutes, free, and it is
what Phase 6 is actually about.

---

## 2. Escalation email (SMTP)

This is the address the agent's handoff email is *sent from*. Pick one of three
routes. They all end at the same five settings.

### Route A — Mailtrap (recommended for testing)

Mailtrap catches email instead of delivering it. Nothing you send can reach a
real person by accident, which is exactly what you want while testing a feature
that emails people. It is also the fastest to set up: no phone number, no card.

1. Sign up at **https://mailtrap.io/** (free plan).
2. Go to **Email Testing → Inboxes** and open the default inbox.
3. Open the **SMTP Settings** tab and choose **Show Credentials**.
4. You will see a host, a port, a username and a password. Copy them.
5. Fill in the Settings page as in [section 5](#5-where-each-value-goes), using
   the **port 587** option from Mailtrap's list.
6. `Send from address` can be anything, e.g. `agent@example.com` — Mailtrap does
   not check it.

When you run test step 14, the email appears in the Mailtrap inbox in your
browser, formatted, with the masked transcript in it.

### Route B — Gmail with an app password

Use this if you want the email to land in a real inbox you own. It needs
2-Step Verification on the Google account — an ordinary Gmail password will not
work, and Google removed the "less secure apps" switch years ago.

1. Turn on 2-Step Verification:
   **https://myaccount.google.com/signinoptions/two-step-verification**
2. Create an app password: **https://myaccount.google.com/apppasswords**
   - If that page says the option is not available, 2-Step Verification is not
     fully on yet. Finish step 1 and come back.
   - Name it something like `AI agent`. Google shows you a 16-character
     password once. Copy it now; you cannot see it again.
3. The settings are:
   - host `smtp.gmail.com`
   - port `587`
   - username your full Gmail address
   - password the 16-character app password (spaces can be left out)
   - from address the same Gmail address — Gmail refuses to send as anyone else

Reference: **https://support.google.com/mail/answer/7126229**

### Route C — Brevo (a free SMTP relay)

Use this if you want mail to reach real addresses but would rather not involve
your personal Gmail. Free tier is a few hundred emails a day, which is far more
than any demo needs.

1. Sign up at **https://www.brevo.com/**.
2. Go to **SMTP & API → SMTP**.
3. It gives you a server (`smtp-relay.brevo.com`), port `587`, a login and an
   SMTP key.
4. `Send from address` must be an address you have verified in Brevo — it walks
   you through it.

### Ports, and what is encrypted

**587 and 465 both work now.** 587 starts in the clear and upgrades to TLS; 465
is encrypted from the first byte. This page used to say 465 would time out,
which was a fact about the app rather than about SMTP, and is no longer true.

The upgrade on 587 is *required*, not attempted. A relay that does not offer
STARTTLS — or an attacker who strips the offer — gets the send refused rather
than your password and a call transcript in plain text. The certificate has to
match the host, too.

If a send fails, the backend tries twice and then writes it to the log: the
summary, plus a count of the transcript lines it withheld. Turn on
`Log unsent email body` in Settings while you are debugging a relay to see them.

---

## 3. Google Cloud speech (optional)

Without this, calls still work: the voice server falls back to a free offline
recogniser and a robotic offline voice. With it you get streaming recognition,
Bangla that actually transcribes, and a natural voice.

**Be aware before you start:** Google requires a billing account with a card on
it to enable these APIs, even to use the free monthly allowance. New accounts
get trial credit. Check the current allowance and prices yourself before you
rely on them — they change:

- Speech-to-Text pricing: **https://cloud.google.com/speech-to-text/pricing**
- Text-to-Speech pricing: **https://cloud.google.com/text-to-speech/pricing**
- Free trial terms: **https://cloud.google.com/free**

### Step by step

1. **Create a project.** Go to **https://console.cloud.google.com/** and sign
   in. In the project dropdown at the top, **New Project**. Call it something
   like `ai-customer-agent`.

2. **Turn on billing.** **https://console.cloud.google.com/billing** → link a
   billing account to the project. The speech APIs will not enable without it.

3. **Enable the two APIs.** With your project selected, open each of these and
   press **Enable**:
   - **https://console.cloud.google.com/apis/library/speech.googleapis.com**
   - **https://console.cloud.google.com/apis/library/texttospeech.googleapis.com**

4. **Create a service account.** Go to
   **https://console.cloud.google.com/iam-admin/serviceaccounts** →
   **Create service account**.
   - Name: `voice-server`
   - On the "Grant this service account access" step, give it the role
     **Cloud Speech Client**. That is enough for both APIs; do not give it
     anything broader.
   - Finish.

5. **Download its key.** Click the service account you just made → the **Keys**
   tab → **Add key → Create new key → JSON**. A `.json` file downloads. This
   file *is* the credential — treat it like a password.

6. **Put it where the app looks.** Rename it to `gcp-credentials.json` and save
   it in the `secrets/` folder at the root of this repository:

   ```
   AI-Customer-Service-Agent/
     secrets/
       gcp-credentials.json          ← yours, ignored by git
       gcp-credentials.json.PLACEHOLDER
   ```

   `secrets/` is in `.gitignore`, so the key cannot be committed by accident.

7. **Point the app at it — and read the next section before you do.**

### The path gotcha (this one is real)

The `Google credentials file` setting ships as `./secrets/gcp-credentials.json`,
a *relative* path. Where that lands depends on how you started the app:

| How you run it | What `./secrets/…` resolves to | Works? |
|---|---|---|
| `docker compose up` | `/app/secrets/…` inside the container, which is your `secrets/` folder mounted in | ✅ |
| `.\run-local.ps1` or `mvn spring-boot:run` | `java-backend/secrets/…` and `python-voice/secrets/…`, neither of which exists | ❌ |

So **if you are not using Docker, change that setting to an absolute path**.
In the panel: **Settings → Google credentials file**, set it to something like

```
F:\3.GitHub_ORS\AI-Customer-Service-Agent\secrets\gcp-credentials.json
```

Save. The Dashboard's **Speech recognition and voice** line should change from
"Free fallback" to "Ready" within a few seconds. If it does not, the file is not
where the app is looking — that line is checking for a real, non-empty file at
exactly that path, so it is a reliable test.

### While you are in there

Two voice settings are worth knowing about. Recognition uses `bn-BD`
(Bangladesh) for Bangla; the *voice* uses `bn-IN` (India), because that is where
Google has Bangla voices. The defaults are `en-US-Neural2-C` and
`bn-IN-Standard-A`. If you want a different one, pick from the list and paste
the exact name into **English voice** / **Bangla voice**:

- **https://cloud.google.com/text-to-speech/docs/voices**
- Recognition languages: **https://cloud.google.com/speech-to-text/docs/languages**

---

## 4. The two that are not credentials

### `PII_ENC_KEY` — before you store anyone real

This encrypts customer phone numbers and email addresses in the database. It
ships as `PLACEHOLDER_PII_ENC_KEY`, and it lives in `.env`, not in the panel:

```
PII_ENC_KEY=some-long-random-string-you-choose
```

**Change it before you put a real person's contact details in**, and then leave
it alone — rows written with the old key cannot be read with a new one. That is
not a bug, and test step 16 is there to show you the app survives it gracefully
rather than falling over. Generate one however you like; any long random string
is fine.

If you change it after seeding, the two example customers (C001, C002) stop
showing their phone and email and wear a badge saying why. That is the expected
result of step 16, not a failure.

### The escalation contact — who the email is addressed to

This is the one people forget. The seeded business's escalation contact is a
fake address, `PLACEHOLDER_ESCALATION_EMAIL`, so even with perfect SMTP settings
the email goes nowhere useful.

1. Panel → **Businesses** → **What it says** on Template Business.
2. **Hours & handover** tab → the **Who takes a call the agent cannot** section.
3. Replace the placeholder with a real address — your own, or the Mailtrap
   inbox. Save.

Test step 17 asks you to delete this contact entirely and check that a call
still completes and is still written up. It should say "Nobody is listed to
escalate to" in the log and carry on.

---

## 5. Where each value goes

Everything below is **Settings** in the panel unless it says otherwise. Settings
take effect on the next call — there is no restart.

| Setting on the page | What to put in it |
|---|---|
| `SMTP host` | `sandbox.smtp.mailtrap.io` / `smtp.gmail.com` / `smtp-relay.brevo.com` |
| `SMTP port` | `587` |
| `SMTP username` | from your provider |
| `SMTP password` | from your provider (Gmail: the app password) |
| `Send from address` | Gmail: your address. Brevo: a verified address. Mailtrap: anything |
| `Google credentials file` | absolute path to your JSON key if you are not using Docker |
| `Speech-to-text provider` | leave on `auto` — it picks Google when the key file is really there |
| `Text-to-speech provider` | leave on `auto` |

| Not on the Settings page | Where |
|---|---|
| `PII_ENC_KEY` | `.env` at the repository root |
| Escalation contact address | Businesses → What it says → Hours & handover |
| The JSON key file itself | `secrets/gcp-credentials.json` |

A saved secret is never shown back to you in full — the field goes empty and the
panel shows `••••a9f2`. An empty secret field on save means "keep what is
stored", so you can edit anything else on that page without retyping your keys.

---

## 6. Before you start testing — a checklist

Minimum, to run the whole of `phase_06_test.md` except step 14:

- [ ] Model API key in Settings, provider set to match *(you have this)*
- [ ] Escalation contact set to a real address you can check
- [ ] Backend and voice server both running; the Dashboard's **Voice server**
      line reads **Ready**, not **Off**
- [ ] A working microphone and headphones — use headphones, or the agent will
      hear itself

To also run step 14 (the email really arriving):

- [ ] Five SMTP settings filled in, port **587**
- [ ] Sent yourself a test by running step 12 and watching the inbox

To get real numbers out of steps 9–11:

- [ ] `secrets/gcp-credentials.json` in place
- [ ] `Google credentials file` set to an absolute path if not using Docker
- [ ] Dashboard's **Speech recognition and voice** line reads **Ready**

---

## 7. Keeping these safe

- `secrets/` and `.env` are both in `.gitignore`. Check before you push
  anyway — `git status` should never list either of them.
- The service-account JSON is a credential in itself. Anyone with the file can
  spend against your billing account. If it ever leaks, delete the key from the
  service account's **Keys** tab; it stops working immediately.
- A Gmail app password can be revoked at
  **https://myaccount.google.com/apppasswords** without changing your real
  password.
- Nothing in this app logs a secret. The escalation email is printed to the log
  in full when SMTP is unconfigured, but with the caller's ID number, phone,
  email and any amounts already replaced with `[MASKED_…]`.

---

## 8. Twilio — not yet

Phase 7, not Phase 6. Nothing in the Phase 6 test script touches it, and the
Twilio button on the Live Call page stays disabled with a tooltip until it is
configured. When you get there you will need a Twilio account
(**https://www.twilio.com/try-twilio**) and ngrok (**https://ngrok.com/download**)
so Twilio can reach your machine from the internet. Leave all seven Twilio
settings on their placeholders until then — they are supposed to be amber.

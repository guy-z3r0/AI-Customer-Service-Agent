# Phase 1 test script — Foundations

Run these in order from the repository root. Each step says what you should see.
If a step fails, stop there — the later ones depend on it.

**Before you start:** Docker Desktop is running, and nothing else is using ports
5432 or 8080.

---

## A. The stack starts

**1.** Copy the environment file.

```bash
cp .env.example .env
```

*Expected:* `.env` exists. You changed nothing in it.

**2.** Build and start.

```bash
docker compose up --build
```

*Expected:* after a few minutes the log settles on a line like
`Started Main in 4.2 seconds`. No stack trace anywhere above it. In particular
you should see Flyway report `Successfully applied 2 migrations`.

**3.** Confirm the schema exists.

```bash
docker compose exec postgres psql -U agent agent -c "\dt"
```

*Expected:* 11 tables — `ai_settings`, `app_config`, `business`, `call_message`,
`call_record`, `call_summary`, `client`, `escalation_contact`, `kb_entry` and
`mode_transition`, plus Flyway's own `flyway_schema_history`.

**4.** Confirm the seed landed.

```bash
docker compose exec postgres psql -U agent agent -c "SELECT slug, name, active FROM business;"
```

*Expected:* `template-business | Template Business | t`, plus one row per folder
under `java-backend/data/businesses/` — `test1`, `test2`, `test3` — each with
`active = f`.

**5.** Confirm the customer contact details went in encrypted.

```bash
docker compose exec postgres psql -U agent agent -c "SELECT client_code, name, length(phone_enc) FROM client;"
```

*Expected:* rows for `C001` and `C002` with a `length` around 100, not `NULL` and
nowhere near the 14 characters a phone number would be. The raw column is
unreadable, which is the point.

---

## B. The panel loads

**6.** Open http://localhost:8080 in a browser.

*Expected:* a dark panel. Down the left, six sections: Dashboard, Live call,
Businesses, Clients, Call history, Settings. Along the top, the page title and a
dropdown reading **Template Business**. Along the bottom:

```
Database: up   Voice server: arrives in Phase 2   Language model: gemini — needs a key
```

and, at the right, a count of settings still on placeholder values.

**7.** Click **Dashboard**, then **Live call**, **Clients**, **Call history**.

*Expected:* each shows one line naming the phase it arrives in and a single
button back to Businesses. No blank screens, no errors.

**8.** Click **Businesses**.

*Expected:* a table with Template Business and the three imported v1 businesses.
Template Business shows a green **Active** badge; the others show a **Make
active** button. Template Business shows 10 knowledge entries and 2 clients;
`test1` and `test3` show counts of their own; `test2` shows 0 clients.

**9.** Press **Make active** on `test1`.

*Expected:* a toast saying `test1 is now the active business`. The badge moves to
`test1`, the top-bar dropdown changes to `test1`, and Template Business now
offers a **Make active** button.

**10.** Set the top-bar dropdown back to **Template Business**.

*Expected:* the same toast and the badge moving back. The dropdown and the table
never disagree.

---

## C. Settings persist

**11.** Click **Settings**.

*Expected:* five groups — Language model, Speech, Call behaviour, Telephony
(optional), Escalation email (optional). Every credential field carries an amber
**PLACEHOLDER** badge. Labels are words ("Gemini API key"), not raw key names.

**12.** Change **Speaking rate** from `170` to `185` and press **Save settings**.

*Expected:* a toast reading `1 setting(s) saved`. The field still shows `185`.

**13.** Press **Save settings** again without changing anything.

*Expected:* a toast reading `Nothing changed`.

**14.** Type any text into **Gemini API key** — `test-key-1234` will do — and save.

*Expected:* `1 setting(s) saved`. The PLACEHOLDER badge on that row disappears,
the field goes empty, and its grey hint text becomes `••••1234`. The status line
at the bottom changes to `Language model: gemini — ready` within ten seconds,
and the placeholder count on the right drops by one.

**15.** Press **Save settings** again, leaving the Gemini field empty.

*Expected:* `Nothing changed`. Leaving a secret blank keeps it — the key you
just typed is still there.

**16.** Restart the stack.

```bash
docker compose down
docker compose up
```

**17.** Open the panel and go to Settings.

*Expected:* Speaking rate is still `185`, the Gemini key still shows its
`••••1234` mask, and the status line still says `ready`. Nothing you typed was
lost, and Flyway did not try to re-run its migrations.

---

## D. The import is safe to repeat

**18.** On the Businesses page, press **Import old JSON data**.

*Expected:* a toast reading `Imported 0, skipped 3`. The table is unchanged — no
duplicate rows appear. Running the import twice does nothing the second time.

**19.** Check the API says the same thing.

```bash
curl -s -X POST http://localhost:8080/api/import/legacy
```

*Expected:* `{"imported":[],"skipped":["test1","test2","test3"],"problems":[]}`.

---

## E. It degrades instead of breaking

**20.** Check the health endpoint directly.

```bash
curl -s http://localhost:8080/api/health
```

*Expected:* JSON with `"database":"up"`, `"voiceServer":"not_built"`, and a
`placeholders` array listing every key you have not filled in. The endpoint
answers even though most of the app is unconfigured.

**21.** Check that no secret leaves the server in readable form.

```bash
curl -s http://localhost:8080/api/config | grep -o 'test-key-1234'
```

*Expected:* no output at all. The key you typed in step 14 never leaves the
server; the panel only ever receives its `••••1234` mask.

**22.** Ask for the panel's vocabulary in Bangla.

```bash
curl -s "http://localhost:8080/api/lang?lang=bn" | head -c 200
```

*Expected:* Bangla script. Every string the panel can show exists in both
languages, which is what Phase 4's bilingual work builds on.

**23.** Ask for a business that does not exist.

```bash
curl -s -i http://localhost:8080/api/businesses/00000000-0000-4000-8000-000000000000 | head -3
```

*Expected:* `HTTP/1.1 404` and a body of `{"error":"No business with that id."}` —
a sentence, not a stack trace.

---

## Pass criteria

All 23 steps behave as described, and at no point did the app need a real
credential to start, load a page or save a change.

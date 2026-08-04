# Phase 5 log — CRM + full editability: clients, onboarding, admin pages

**Closed:** 2026-08-04
**Status:** built; the client tools, the prompt's caller block and the encrypted
record round-trip are unit-tested, and the whole project builds and packages.
Not yet run against a live stack — see "What is not verified".

---

## What ships

The agent now knows who it is talking to. A caller who reads out a customer code
or gives a number they are on record with is recognised mid-call, greeted by
name, and answered against their notes and what they have needed before. A
caller who is not on record can be written down during the call — name, number
and what they wanted — and the row appears in the panel without a refresh.

And everything a call reads is editable in the browser. The business editor has
six tabs: the four knowledge sections, the persona, and the hours with the
people a call gets handed to. Nothing is copied anywhere — the prompt for the
next call is assembled from these rows at the moment it is placed, so a sentence
changed on the page is a sentence the agent says a minute later.

The text console is gone.

---

## Files added

**Java** — `services/ClientService.java` (the one class that knows how to open
the box), `api/{ClientController, KbController, AiSettingsController}.java`,
`api/dto/{ClientDtos, EditorDtos}.java`, `utils/Prompts.java`, and
`db/migration/V3__try_decrypt.sql`.

**Panel** — `js/pages/{business_editor, clients}.js`. `components.js` grew the
dialog, confirm, tab-strip and form parts; `controls.css` the styles behind
them.

**Tests** — seven more in `ToolExecutorTest` for the customer tools, two in
`PromptBuilderTest` for the caller block, and `TestCalls.Customers`, a customer
list with no database behind it. 41 Java tests, 17 Python.

## Files changed

`ToolRegistry` and `ToolExecutor` gained `lookup_client`, `create_client` and
`log_request` — six tools now. `CallSession` carries the identified customer;
`PromptBuilder` writes them into the prompt above the knowledge base;
`CallLogService` ties the call's row to them and tells the panel. A call can be
placed as a known customer from the Live Call page, in which case it opens in
EXISTING_CUSTOMER instead of getting there part-way through.

`KbService` grew create, update, delete and move. `BusinessService` grew the AI
settings and the escalation contacts. `businesses.js` became read/write.
`app.js`'s router learned to carry an id in the hash, which is how the editor is
reached. `FileUtils` logs through SLF4J now that there is nothing else to log to.

## Files deleted

`utils/ConsoleTerminal.java` and `utils/Console.java` — Phase 5's list, exactly.
Nineteen console-only strings went with them.

---

## Decisions worth knowing

**A new migration, V3, for one function.** `pgp_sym_decrypt` does not fail
gently: handed a row written under a different key it raises, and a raise inside
Postgres aborts the whole transaction, so one unreadable row would take the
customers page down with it. `try_decrypt` wraps it in an exception block and
returns null instead. This is not hypothetical — `docs/SETUP.md` tells you to
change `PII_ENC_KEY` before storing anyone's real details, and the moment you
do, the two seeded customers stop decrypting. The page still shows them, with a
badge saying why their contacts are missing. Phase 7's demo business becomes V4.

**Blank and unreadable look identical in the data.** A null phone number is
either a field nobody filled in or a field this install can no longer read, and
those mean very different things to whoever is looking at the screen. The
`contactReadable` flag on a customer record is what tells them apart, and the
panel shows a badge rather than an empty cell for the second.

**The insert picks its own id.** The obvious way to write an encrypted row and
read it back is `INSERT … RETURNING id`, and Hibernate's handling of a native
query that is both a mutation and a select is not something to find out about in
production. Generating the UUID in Java first makes the insert an ordinary
`executeUpdate` and the read-back an ordinary select.

**Only one thing about a customer can clash.** A constraint violation on that
table is always the client code, so that is what it is reported as — but the
check looks for the constraint by name and rethrows anything else. Catching
every `PersistenceException` and calling it "code taken" would have hidden real
faults behind a polite message.

**Phone matching is done on digits, from the end.** `+8801711111111` and
`01711111111` are the same person, and a caller reads their number out however
they think of it. Comparing the digits and letting either end match handles the
country code without a phone-number library.

**Every row is decrypted to find one.** pgcrypto gives the same number a
different ciphertext every time it is written, so there is nothing to match on
but the plain text. At the size of one business's customer list that costs
nothing worth saving; if a business ever has fifty thousand customers this is
the first thing to revisit.

**`create_client` does not promote the call to EXISTING_CUSTOMER.** Writing
somebody down does not make them a known customer *on this call* — they were a
stranger when they rang, and the transcript should say so. Only `lookup_client`
promotes.

**The prompt text moved out of `Lang.java` into `Prompts.java`.** Lang was one
line under the 500 cap and carries a promise the prompt text was never part of:
every entry exists in both languages, and changing one changes what a person
hears. None of that is true of standing orders handed to a model. Splitting them
made the rule clearer rather than weaker — Lang is what people read, Prompts is
what the model reads.

**Two more dto containers.** BUILD_SPEC caps `api/dto/` at six files and six
were already used. `ClientDtos` and `EditorDtos` group by responsibility the way
`CallDtos` did in Phase 2 — one customer's shapes, one screen's shapes — rather
than pushing the directory to fourteen files.

---

## Verified

- `mvn clean package` builds and packages; **41 Java tests pass**.
- `python -m pytest` — **17 tests pass**, unchanged by this phase.
- The customer tools are tested for what they do and what they refuse: a code
  read out in the wrong case still matches, the same number written two ways
  still matches, somebody who is not on the books comes back as a plain no
  rather than an exception, a second `create_client` on one call is refused, and
  `log_request` against nobody is refused with an explanation the model can act
  on. A recognised caller stops being a stranger; one who is not found does not.
- The prompt carries a recognised caller's name, code, notes and history, and
  carries nothing about them when nobody has been identified.
- 222 panel strings in both languages — the only entry without Bengali script is
  `settings.badge_placeholder`, which is the literal word PLACEHOLDER.
- Every key the panel asks for exists in `Lang`, checked by script across all
  nine page files. Every panel JavaScript file parses as a module.

## What is not verified

No Docker, no PostgreSQL, no microphone, no Google credentials and no model API
key in this build environment. None of the following has run:

- `docker compose up --build`, and therefore **V3 has never been applied**. The
  `try_decrypt` function is plain PL/pgSQL and the syntax is ordinary, but it
  has not been parsed by a real Postgres
- Any encrypted round trip: writing a customer through `pgp_sym_encrypt` and
  reading them back through `try_decrypt`. The tests cover the tools above this
  layer with an in-memory customer list, not the SQL underneath it
- The behaviour when `PII_ENC_KEY` really has changed, which is what V3 exists
  for
- A model actually choosing to call `lookup_client` or `create_client`
- Every panel screen: the editor's six tabs, the dialogs, the tab-strip, the
  destructive confirmations, and the router carrying an id in the hash

`phase_05_test.md` walks all of it. Steps 7, 12 and 16 are the three that decide
the phase.

---

## Commit log

- Read and write customer records through pgcrypto, and add try_decrypt so a changed key costs contacts rather than the page
- Give the agent three more tools: recognise a caller, write a new one down, and note what they needed
- Open the knowledge base, the persona, the hours and the escalation contacts over REST
- Build the six-tab business editor and the customer list, on dialog, tab-strip and form parts the contract already named
- Remove the text console, and move the model's standing orders out of Lang into their own file

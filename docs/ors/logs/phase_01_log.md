# Phase 1 log — Foundations: Postgres, migrations, panel shell, placeholders

**Closed:** 2026-08-04
**Status:** built, compiled, panel verified against a stub API. Not yet run against
a live Postgres — see "What is not verified" below.

---

## What ships

`docker compose up --build` starts Postgres and the Java backend. Flyway creates
the whole schema and seeds Template Business plus every `PLACEHOLDER_*` setting.
The control panel is at `localhost:8080`: Nocturne shell (side-rail, top-bar,
status-bar), a read-only Businesses list with an active-business selector, and a
Settings page that shows every config key, badges the placeholders and saves.
The version-1 JSON tree under `java-backend/data/businesses/` is imported into
the database once at start-up and can be re-run from the panel.

---

## Files added

**Root / infra**
`docker-compose.yml` (postgres + java-backend), `.env.example`,
`java-backend/Dockerfile`, `secrets/gcp-credentials.json.PLACEHOLDER`,
`docs/SETUP.md`. `.gitignore` grew `.env` and a `secrets/*` rule that keeps only
`*.PLACEHOLDER` files.

**Schema**
`resources/application.yml`, `db/migration/V1__baseline.sql` (11 tables,
pgcrypto, cascade FKs), `db/migration/V2__seed.sql` (Template Business with 10
knowledge entries, persona, hours, escalation contact, clients C001/C002; 27
app_config keys).

**Java** — 40 files
`domain/` 10 entities + `domain/enums/` 5 enums · `repo/` 10 repositories ·
`services/` BusinessService, ConfigService, LegacyImportService ·
`api/` BusinessController, ConfigController, HealthController, ImportController,
LangController, ApiExceptionAdvice + `api/dto/` 5 records ·
`utils/Lang.java` rewritten · `Main.java` and `utils/ConsoleTerminal.java` rewritten.

**Panel** — `static/index.html`, `css/nocturne.css`, `css/parts.css`,
`css/controls.css`, `js/{app,api,components}.js`, `js/pages/{businesses,settings}.js`.

`css/controls.css` is one file beyond BUILD_SPEC's list. The parts stylesheet reached 465
lines with five phases of screens still to come, so it was split along the contract's own
group boundaries: `parts.css` holds shell, surfaces and data (293 lines), `controls.css`
holds input and feedback (177). Both stay well inside the budget as they grow.

## Files deleted

`managers/*` (5) and `models/*` (10), as Phase 1's Replaces list names.

Four v1 controllers went with them because they had no other backing:
`AISettingsController`, `CallContextController` (+`CallContextResponse`,
`CallModeRequest`), `ChatMessageController` (+`ChatMessageRequest`),
`TranscriptController` (+`TranscriptRequest`). BUILD_SPEC schedules three of
these for Phase 3, but they exist only to serve v1's `python-scripts/`, which
Phase 2 replaces wholesale, and they cannot compile without `CallManager`.
`utils/PathUtils.java` went too — it only addressed the v1 JSON tree and was
dead once the managers left.

`java-backend/target/` was untracked with `git rm --cached`. Build output had been
committed in v1 and `.gitignore` already excluded it, so every build showed up as a
source change. No files were removed from disk.

`ai/CallMode.java` is deliberately kept. It is scheduled for Phase 4 and its
per-mode instruction text is the source material for `CallModeMachine`. It is
currently unreferenced, which is the one piece of dead code in the tree.

---

## Decisions worth knowing

**`ddl-auto: none`, not `validate`.** Flyway owns the schema and Hibernate is
told to keep its hands off. `validate` is the stricter setting and would catch
entity-to-table drift at boot, but there is no Postgres in this build
environment to prove the mapping passes it, and a failed validation stops the
app from booting at all. Phase 2 should flip it to `validate` once the stack has
been run once.

**`stringtype=unspecified` on the JDBC connection.** Lets plain Java strings go
into `jsonb` columns without a cast in every query. Set as a Hikari data-source
property so it survives someone editing `DB_URL`.

**Enums are stored as their uppercase Java names.** BUILD_SPEC's data model
sketches the CHECK constraints in lowercase (`about`, `caller`, `browser`). Doing
that would need an attribute converter per enum and push each enum file past its
30-line budget, so the CHECK lists hold `ABOUT`, `CALLER`, `BROWSER` instead and
`@Enumerated(EnumType.STRING)` maps them directly.

**Only one business can be active, and the database says so.** A partial unique
index (`ON business (active) WHERE active`) makes a second active row
impossible, so activating one is a two-statement operation: stand the old one
down, then promote the new one.

**The legacy import runs at start-up as well as on demand.** Phase 1's "done
when" says the panel shows the imported v1 businesses after `compose up`, which
only holds if nobody has to press a button first. It is additive and skips any
slug already present, so later starts do nothing.

**`api/LangController.java` was added, beyond BUILD_SPEC's file list.** The
constraint that all user-facing strings live in `Lang.java` and reach the panel
through the API needs an endpoint to reach it through. `GET /api/lang?lang=en|bn`
returns the whole vocabulary; the panel holds no English or Bangla text at all.

**Compose ships two services, not three.** `python-voice/` does not exist yet —
Phase 2 creates it, renaming `python-scripts/`. A third service pointing at a
missing Dockerfile would break `docker compose up`, which is the one thing this
phase promises.

**Fonts are the contract's fallback stack.** STYLE-CONTRACT says to bundle the
Inter and JetBrains Mono faces. No font binaries were added to the repo; the
contract's own fallback chain (`Inter, "Segoe UI", "SF Pro Text", Roboto`) is
used instead. Dropping the real faces into `static/` later changes nothing else.

---

## Verified

- `mvn clean package` succeeds; `target/java-backend-2.0.0.jar` builds (46 sources).
- All five panel JavaScript files parse as ES modules.
- The panel was served against a stub API and read back: shell, rail, top-bar
  business selector, Businesses table, Settings form with translated labels and
  PLACEHOLDER badges, and the status line all render, with an empty console.
- Computed styles match STYLE-CONTRACT exactly — backdrop `#0B0E1A`, panel
  `#151A2C`, rail 220px, primary button `#4FD6EE` at 34px / radius 4px, fields
  `#1E2440` with a `#5A6491` border, gold badge on backdrop text, no horizontal
  page overflow.
- One bug found and fixed this way: `keyValueRow` stringified a built node, so
  every Settings label rendered as `[object HTMLSpanElement]`.

## What is not verified

There is no Docker and no PostgreSQL in this build environment, so nothing below
has been executed even once:

- Flyway running `V1` and `V2` against a real database
- The seed's `pgp_sym_encrypt` calls and the Flyway `${pii_enc_key}` placeholder
- Entity-to-table mapping under a live connection
- The legacy import writing rows
- `docker compose up --build` end to end

`phase_01_test.md` walks all of it in order; step 1 is the one that matters most.

---

## Commit log

- Add Postgres, Flyway baseline and seed, and a Docker Compose stack that boots with no credentials
- Replace v1 managers and models with JPA entities, repositories and the Business/Config/LegacyImport services
- Add the Nocturne control panel shell with read-only Businesses and a working Settings page
- Move every user-facing string into Lang.java in English and Bangla, served to the panel over /api/lang
- Import the v1 JSON business tree into the database at start-up, idempotently

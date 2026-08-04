# AI Customer Service Agent — Claude Code entry point

Read in this order, nothing else first:

1. `docs/ors/PROJECT_STATE.md` — where the build stands and the next action
2. `docs/ors/BUILD_SPEC.md` — the machine spec (structure, files, contracts, config, constraints)
3. `docs/ors/phases.md` — load ONLY the current phase's section
4. `STYLE-CONTRACT.md` (repo root) — before touching any panel UI file

## Rules that override defaults

- Mode is PHASED: finish the phase → write `docs/ors/logs/phase_NN_log.md` (ends with a
  ≤5-bullet commit log) + `phase_NN_test.md` (numbered steps, each with an expected
  result) → update `architecture.md` + `PROJECT_STATE.md` → STOP for user approval.
- Evolve the existing repo. Delete only what the current phase's "Deletes/Replaces" list names.
- Files ≤500 lines (target 300), methods ≤50, nesting ≤3. Split by responsibility.
- Comments explain why, not what; only above non-obvious methods. No commented-out code.
- Panel: colors/spacing/fonts only via `var()` from `css/nocturne.css` (mirrors STYLE-CONTRACT.md).
- User-facing strings (EN + BN) live in `utils/Lang.java` only.
- Credentials are `PLACEHOLDER_*` values — features degrade politely, never crash or block boot.
- Stop and ask before: destructive migrations, deleting user data, anything needing a real
  credential, anything contradicting `docs/ors/proposal.md`.

## Commands

- Full stack: `docker compose up --build`
- Backend only: `cd java-backend && mvn spring-boot:run` (panel at http://localhost:8080)
- Voice only: `cd python-voice && uvicorn server:app --port 8090`
- DB shell: `docker compose exec postgres psql -U agent agent`

-- The model a clean install starts on.
--
-- V2 seeded "gemini-2.0-flash", which Google has since shut down: a fresh
-- clone with a perfectly good API key got HTTP 404 on its first call and an
-- agent that could only apologise. The seed cannot be edited once it has run —
-- Flyway checksums it — so the correction is its own migration.
--
-- Only the untouched default is changed. An operator who has already chosen a
-- model in Settings keeps their choice, which is why this matches on the old
-- value rather than overwriting whatever is there.

UPDATE app_config
SET value_json = '"gemini-3.6-flash"'
WHERE key = 'llm_model'
  AND value_json = '"gemini-2.0-flash"';

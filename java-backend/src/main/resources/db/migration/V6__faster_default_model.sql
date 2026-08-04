-- The model a clean install starts on, chosen by measurement rather than by
-- reading a table of names.
--
-- Phase 7's tuning pass timed five real turns against the seeded knowledge
-- base, measuring the model's own share of the reply: from the caller's
-- sentence being final to the first word coming back. Same questions, same
-- machine, same key, minutes apart:
--
--     gemini-3.6-flash        median 3823 ms   fastest turn 2018 ms
--     gemini-3.1-flash-lite   median  800 ms   fastest turn  704 ms
--
-- The budget for a whole turn — recognition, model and speech — is 2000 ms, so
-- the first of those spends the entire allowance before the voice starts. The
-- lighter model was also checked for the thing that would have ruled it out:
-- it still reaches for tools on its own, and escalate_to_human fired correctly
-- on a refund it could not approve.
--
-- V5 corrected a model that no longer existed; this one corrects a model that
-- is simply too slow for a telephone call. Both leave an operator's own choice
-- alone by matching on the value they are replacing.

UPDATE app_config
SET value_json = '"gemini-3.1-flash-lite"'
WHERE key = 'llm_model'
  AND value_json = '"gemini-3.6-flash"';

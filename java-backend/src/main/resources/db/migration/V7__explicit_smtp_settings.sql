-- Two settings that were being inferred instead of asked for.
--
-- smtp_auth: whether to log in to the mail relay. This used to be worked out
-- from the username — "is it still a PLACEHOLDER_ value?" — which quietly means
-- that a real username beginning with those eleven characters turns
-- authentication off, and the relay then refuses the message with an error that
-- says nothing about why. Some relays on a local network do take mail from
-- anybody and refuse a login outright, so the choice has to exist; it just has
-- to be a choice rather than a guess.
--
-- log_unsent_email_body: already read by MailService, never seeded, so it could
-- not be turned on from the Settings page. It decides whether an escalation
-- that could not be sent goes into the log with its transcript or without it.
-- Off is the right default: application logs are not treated as customer
-- records, and the body of that email is a whole call.

INSERT INTO app_config (key, value_json)
SELECT key, value::jsonb FROM (VALUES
    ('smtp_auth',              'true'),
    ('log_unsent_email_body',  'false')
) AS seed(key, value)
ON CONFLICT (key) DO NOTHING;

-- Starter content so a clean install has something real to look at and edit.
--
-- Two things are seeded:
--   1. "Template Business" — a complete, obviously-fake example business. Every
--      sentence is meant to be replaced from the panel; nothing here is real.
--   2. app_config — every setting the app knows about. Anything that needs a
--      credential is seeded as PLACEHOLDER_* so the app boots without one.
--
-- Every statement is written so re-running the migration on an existing
-- database changes nothing.

-- ---------------------------------------------------------------- business --

INSERT INTO business (id, slug, name, phone, email, address, timezone, hours_json, active)
VALUES ('11111111-1111-4111-8111-111111111111',
        'template-business',
        'Template Business',
        '+8801000000000',
        'hello@template-business.example',
        '123 Example Road, Dhanmondi, Dhaka',
        'Asia/Dhaka',
        '{"sat":{"open":"10:00","close":"20:00"},
          "sun":{"open":"10:00","close":"20:00"},
          "mon":{"open":"10:00","close":"20:00"},
          "tue":{"open":"10:00","close":"20:00"},
          "wed":{"open":"10:00","close":"20:00"},
          "thu":{"open":"10:00","close":"20:00"},
          "fri":null}'::jsonb,
        true)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO ai_settings (business_id, persona_name, role_description, reply_style,
                         greeting_en, greeting_bn, temperature, max_history_turns)
VALUES ('11111111-1111-4111-8111-111111111111',
        'Ayesha',
        'You answer the phone for Template Business. Help callers with questions about services, prices and opening hours, using only the knowledge given to you.',
        'Warm, short and clear. One or two sentences per reply. Ask a question back when something is unclear. Never invent a price or a policy.',
        'Hello, you have reached Template Business. This call is answered by an AI assistant. How can I help you today?',
        'হ্যালো, আপনি টেমপ্লেট বিজনেসে কল করেছেন। এই কলটি একজন এআই সহকারী গ্রহণ করছে। আমি কীভাবে সাহায্য করতে পারি?',
        0.7, 20)
ON CONFLICT (business_id) DO NOTHING;

-- ---------------------------------------------------------- knowledge base --

INSERT INTO kb_entry (business_id, kind, question, content, sort_order)
SELECT '11111111-1111-4111-8111-111111111111', kind, question, content, sort_order
FROM (VALUES
    ('ABOUT', NULL,
     'Template Business is an example shop used to demonstrate this agent. Replace this text with what your business actually does, where it is, and who it serves. It sits in Dhanmondi, Dhaka and is open Saturday to Thursday, closed Friday.', 0),

    ('SERVICE', NULL, 'Example service one - describe it here, and say what it costs. 500 BDT.', 0),
    ('SERVICE', NULL, 'Example service two - a slower job that needs a quote first. From 2000 BDT.', 1),
    ('SERVICE', NULL, 'Example home visit - anything done at the customer''s address. 300 BDT extra.', 2),

    ('POLICY', NULL, 'Example warranty - work done here is covered for 30 days.', 0),
    ('POLICY', NULL, 'Example refund rule - unused items can be returned within 7 days with the receipt.', 1),
    ('POLICY', NULL, 'Example deposit rule - parts that must be ordered need 50% paid up front.', 2),

    ('FAQ', 'What are your opening hours?',
     'We are open Saturday to Thursday, 10am to 8pm. We are closed on Friday.', 0),
    ('FAQ', 'Do I need an appointment?',
     'No, you can walk in during opening hours. Call ahead if you want a guaranteed slot.', 1),
    ('FAQ', 'How long does a job take?',
     'Most jobs are finished within two to three working days. Ordering a part can add a week.', 2)
) AS seed(kind, question, content, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM kb_entry WHERE business_id = '11111111-1111-4111-8111-111111111111'
);

-- ------------------------------------------------------------------ people --

INSERT INTO escalation_contact (business_id, name, email, priority)
SELECT '11111111-1111-4111-8111-111111111111', 'Example Manager', 'PLACEHOLDER_ESCALATION_EMAIL', 1
WHERE NOT EXISTS (
    SELECT 1 FROM escalation_contact WHERE business_id = '11111111-1111-4111-8111-111111111111'
);

-- Contact details go in encrypted. The key comes from the PII_ENC_KEY
-- environment variable, injected here by Flyway as a placeholder.
INSERT INTO client (business_id, client_code, name, phone_enc, email_enc, notes, past_issues_json)
SELECT '11111111-1111-4111-8111-111111111111', code, name,
       pgp_sym_encrypt(phone, '${pii_enc_key}'),
       pgp_sym_encrypt(email, '${pii_enc_key}'),
       notes, issues::jsonb
FROM (VALUES
    ('C001', 'Example Customer One', '+8801711111111', 'customer.one@example.com',
     'Example note - a long-standing customer who prefers phone calls and speaks both Bangla and English.',
     '["March 2026: example past issue, resolved.", "May 2026: example follow-up, resolved."]'),
    ('C002', 'Example Customer Two', '+8801722222222', 'customer.two@example.com',
     'Example note - a newer customer who has asked about pricing before.',
     '["June 2026: example enquiry, answered."]')
) AS seed(code, name, phone, email, notes, issues)
WHERE NOT EXISTS (
    SELECT 1 FROM client WHERE business_id = '11111111-1111-4111-8111-111111111111'
);

-- ---------------------------------------------------------------- settings --

INSERT INTO app_config (key, value_json)
SELECT key, value::jsonb FROM (VALUES
    -- language model
    ('llm_provider',           '"gemini"'),
    ('llm_model',              '"gemini-2.0-flash"'),
    ('gemini_api_key',         '"PLACEHOLDER_GEMINI_API_KEY"'),
    ('openai_api_key',         '"PLACEHOLDER_OPENAI_API_KEY"'),
    ('openai_model_default',   '"gpt-4o-mini"'),

    -- speech
    ('gcp_credentials_path',   '"./secrets/gcp-credentials.json"'),
    ('stt_provider',           '"auto"'),
    ('tts_provider',           '"auto"'),
    ('tts_voice_en',           '"en-US-Neural2-C"'),
    ('tts_voice_bn',           '"bn-IN-Standard-A"'),
    ('tts_rate',               '170'),
    ('tts_volume',             '1.0'),
    ('default_language',       '"en"'),

    -- call behaviour
    ('inactivity_warn_s',      '20'),
    ('inactivity_hangup_s',    '40'),

    -- telephony (optional, Phase 7)
    ('twilio_account_sid',     '"PLACEHOLDER_TWILIO_ACCOUNT_SID"'),
    ('twilio_auth_token',      '"PLACEHOLDER_TWILIO_AUTH_TOKEN"'),
    ('twilio_api_key_sid',     '"PLACEHOLDER_TWILIO_API_KEY_SID"'),
    ('twilio_api_key_secret',  '"PLACEHOLDER_TWILIO_API_KEY_SECRET"'),
    ('twilio_twiml_app_sid',   '"PLACEHOLDER_TWILIO_TWIML_APP_SID"'),
    ('twilio_caller_number',   '"PLACEHOLDER_TWILIO_CALLER_NUMBER"'),
    ('public_media_url',       '"PLACEHOLDER_PUBLIC_MEDIA_URL"'),

    -- escalation email (optional, Phase 6)
    ('smtp_host',              '"PLACEHOLDER_SMTP_HOST"'),
    ('smtp_port',              '587'),
    ('smtp_username',          '"PLACEHOLDER_SMTP_USERNAME"'),
    ('smtp_password',          '"PLACEHOLDER_SMTP_PASSWORD"'),
    ('smtp_from',              '"PLACEHOLDER_SMTP_FROM"')
) AS seed(key, value)
ON CONFLICT (key) DO NOTHING;

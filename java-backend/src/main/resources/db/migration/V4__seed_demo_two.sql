-- A second business, in a different trade from the first.
--
-- This one exists to prove a claim rather than to be useful: that onboarding a
-- business is configuration and nothing else. Nothing in Java, Python or the
-- panel knows this business exists. Everything that makes a call to Demo
-- Courier sound different from a call to Template Business — the greeting, the
-- persona, the prices, the policies, the opening hours, the customers — is in
-- the rows below and can be edited from the panel afterwards.
--
-- It is seeded inactive. Switching the active business in the top bar is the
-- demonstration; nothing else changes.
--
-- Every statement is written so re-running the migration changes nothing.

-- ---------------------------------------------------------------- business --

INSERT INTO business (id, slug, name, phone, email, address, timezone, hours_json, active)
VALUES ('22222222-2222-4222-8222-222222222222',
        'demo-two',
        'Demo Courier',
        '+8801000000001',
        'hello@demo-courier.example',
        '45 Example Avenue, Gulshan, Dhaka',
        'Asia/Dhaka',
        -- Open every day, unlike Template Business, which closes on Friday. The
        -- agent reads its hours from here, so this alone changes what it says.
        '{"sat":{"open":"08:00","close":"22:00"},
          "sun":{"open":"08:00","close":"22:00"},
          "mon":{"open":"08:00","close":"22:00"},
          "tue":{"open":"08:00","close":"22:00"},
          "wed":{"open":"08:00","close":"22:00"},
          "thu":{"open":"08:00","close":"22:00"},
          "fri":{"open":"08:00","close":"22:00"}}'::jsonb,
        false)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO ai_settings (business_id, persona_name, role_description, reply_style,
                         greeting_en, greeting_bn, temperature, max_history_turns)
VALUES ('22222222-2222-4222-8222-222222222222',
        'Rafi',
        'You answer the phone for Demo Courier, a parcel delivery service. Callers want delivery charges, coverage areas, where a parcel has got to, and pickups booked. Quote charges exactly as written and never guess where a parcel is.',
        'Brisk and practical. One or two short sentences. Give the number they asked for first, then the condition on it. Never promise a delivery time the policies below do not state.',
        'Demo Courier, this is Rafi speaking. You are talking to an AI assistant. What can I do for you?',
        'ডেমো কুরিয়ার, আমি রাফি বলছি। আপনি একজন এআই সহকারীর সাথে কথা বলছেন। আপনার জন্য কী করতে পারি?',
        0.6, 20)
ON CONFLICT (business_id) DO NOTHING;

-- ---------------------------------------------------------- knowledge base --

INSERT INTO kb_entry (business_id, kind, question, content, sort_order)
SELECT '22222222-2222-4222-8222-222222222222', kind, question, content, sort_order
FROM (VALUES
    ('ABOUT', NULL,
     'Demo Courier is an example parcel delivery service, used to show that onboarding a business here is configuration and not code. It collects and delivers parcels across Bangladesh, with a depot in Gulshan, Dhaka, and it runs every day of the week.', 0),

    ('SERVICE', NULL, 'Same-day delivery inside Dhaka city, booked before 2pm. 80 BDT for parcels up to 1 kg.', 0),
    ('SERVICE', NULL, 'Next-day delivery to any district headquarters. 130 BDT for parcels up to 1 kg.', 1),
    ('SERVICE', NULL, 'Anything over 1 kg costs 20 BDT extra for each additional kilogram.', 2),
    ('SERVICE', NULL, 'Home or shop pickup, booked by phone. 40 BDT, waived on five or more parcels.', 3),
    ('SERVICE', NULL, 'Cash on delivery: we collect the price from the customer for a fee of 1% of the amount collected.', 4),

    ('POLICY', NULL, 'A lost or damaged parcel is compensated up to 2000 BDT, or the declared value if that is lower. Declare the value at booking or the cap applies.', 0),
    ('POLICY', NULL, 'Cash on delivery money is paid back to the sender within three working days of collection.', 1),
    ('POLICY', NULL, 'We do not carry cash, jewellery, documents that cannot be replaced, liquids, or anything perishable.', 2),
    ('POLICY', NULL, 'If nobody is at the address we try twice more on the following days, then return the parcel to the sender. The return trip is charged at the original rate.', 3),

    ('FAQ', 'How long does a parcel to Chattogram take?',
     'Next day, if it is booked before 6pm. Districts outside a headquarters town can take one day longer.', 0),
    ('FAQ', 'Can you collect from my house?',
     'Yes. Home pickup is 40 BDT, and it is free if you are sending five parcels or more. Tell us the address and a time.', 1),
    ('FAQ', 'How do I find out where my parcel is?',
     'Read out your customer code and we can see what has been booked against it. For a specific parcel we need the booking number as well.', 2),
    ('FAQ', 'Do you deliver on Friday?',
     'Yes. Demo Courier runs every day, 8am to 10pm, including Friday.', 3)
) AS seed(kind, question, content, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM kb_entry WHERE business_id = '22222222-2222-4222-8222-222222222222'
);

-- ------------------------------------------------------------------ people --

INSERT INTO escalation_contact (business_id, name, email, priority)
SELECT '22222222-2222-4222-8222-222222222222', 'Example Operations Lead',
       'PLACEHOLDER_ESCALATION_EMAIL', 1
WHERE NOT EXISTS (
    SELECT 1 FROM escalation_contact WHERE business_id = '22222222-2222-4222-8222-222222222222'
);

-- Contact details go in encrypted, the same way Template Business's did. The
-- key comes from the PII_ENC_KEY environment variable via Flyway.
INSERT INTO client (business_id, client_code, name, phone_enc, email_enc, notes, past_issues_json)
SELECT '22222222-2222-4222-8222-222222222222', code, name,
       pgp_sym_encrypt(phone, '${pii_enc_key}'),
       pgp_sym_encrypt(email, '${pii_enc_key}'),
       notes, issues::jsonb
FROM (VALUES
    ('D001', 'Example Sender One', '+8801733333333', 'sender.one@example.com',
     'Example note - an online shop that sends twenty or so parcels a week, so pickup is free for them. Usually calls about cash-on-delivery money.',
     '["April 2026: example COD payment queried, settled the same week.", "June 2026: example parcel returned after three attempts."]'),
    ('D002', 'Example Sender Two', '+8801744444444', 'sender.two@example.com',
     'Example note - sends one or two parcels a month, usually to Sylhet, and always asks for the charge before booking.',
     '["May 2026: example damaged parcel, compensated at the declared value."]')
) AS seed(code, name, phone, email, notes, issues)
WHERE NOT EXISTS (
    SELECT 1 FROM client WHERE business_id = '22222222-2222-4222-8222-222222222222'
);

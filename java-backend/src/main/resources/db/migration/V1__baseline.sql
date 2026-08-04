-- Baseline schema for the AI Customer Service Agent.
-- pgcrypto gives us gen_random_uuid() for primary keys and pgp_sym_encrypt()
-- for the two client columns that hold personal contact details.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- One row per business the agent answers calls for.
CREATE TABLE business (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       text NOT NULL UNIQUE,
    name       text NOT NULL,
    phone      text,
    email      text,
    address    text,
    timezone   text NOT NULL DEFAULT 'Asia/Dhaka',
    hours_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    active     boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Exactly one business can be the active one. A partial unique index is the
-- cheapest way to make the database refuse a second active row.
CREATE UNIQUE INDEX uq_business_single_active ON business (active) WHERE active;

-- The persona and model preferences the agent uses for this business.
CREATE TABLE ai_settings (
    business_id       uuid PRIMARY KEY REFERENCES business (id) ON DELETE CASCADE,
    persona_name      text NOT NULL,
    role_description  text NOT NULL,
    reply_style       text NOT NULL,
    greeting_en       text NOT NULL,
    greeting_bn       text NOT NULL,
    provider_override text,
    model_override    text,
    temperature       numeric(3, 2) NOT NULL DEFAULT 0.7,
    max_history_turns int NOT NULL DEFAULT 20
);

-- Knowledge base: what the business is, what it sells, its rules, its FAQs.
CREATE TABLE kb_entry (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    kind        text NOT NULL CHECK (kind IN ('ABOUT', 'SERVICE', 'POLICY', 'FAQ')),
    question    text,
    content     text NOT NULL,
    sort_order  int NOT NULL DEFAULT 0
);
CREATE INDEX idx_kb_entry_business ON kb_entry (business_id, kind, sort_order);

-- Customers of a business. phone_enc / email_enc are pgp_sym_encrypt() blobs.
CREATE TABLE client (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id      uuid NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    client_code      text NOT NULL,
    name             text NOT NULL,
    phone_enc        bytea,
    email_enc        bytea,
    notes            text,
    past_issues_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_client_code_per_business UNIQUE (business_id, client_code)
);

-- Humans who receive the summary when a call is too complex for the agent.
CREATE TABLE escalation_contact (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id uuid NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    name        text NOT NULL,
    email       text NOT NULL,
    priority    int NOT NULL DEFAULT 1
);
CREATE INDEX idx_escalation_contact_business ON escalation_contact (business_id, priority);

-- One row per call.
CREATE TABLE call_record (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id        uuid NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    client_id          uuid REFERENCES client (id) ON DELETE SET NULL,
    started_at         timestamptz NOT NULL DEFAULT now(),
    ended_at           timestamptz,
    final_mode         text,
    final_language     text,
    telephony          text NOT NULL CHECK (telephony IN ('BROWSER', 'TWILIO')),
    termination_reason text
);
CREATE INDEX idx_call_record_business_started ON call_record (business_id, started_at DESC);

-- One row per spoken line. The three timestamps are what the latency readout
-- is calculated from, so they are stored per turn rather than aggregated.
CREATE TABLE call_message (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    call_id      uuid NOT NULL REFERENCES call_record (id) ON DELETE CASCADE,
    seq          int NOT NULL,
    role         text NOT NULL CHECK (role IN ('CALLER', 'AGENT', 'SYSTEM')),
    text         text NOT NULL,
    language     text,
    mode_at_time text,
    t_stt_final  timestamptz,
    t_llm_first  timestamptz,
    t_tts_first  timestamptz,
    CONSTRAINT uq_call_message_seq UNIQUE (call_id, seq)
);

-- Every screening decision, so a call can be replayed and counted later.
CREATE TABLE mode_transition (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    call_id   uuid NOT NULL REFERENCES call_record (id) ON DELETE CASCADE,
    from_mode text,
    to_mode   text NOT NULL,
    reason    text,
    at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_mode_transition_call ON mode_transition (call_id, at);

-- The post-call summary written by the language model.
CREATE TABLE call_summary (
    call_id           uuid PRIMARY KEY REFERENCES call_record (id) ON DELETE CASCADE,
    summary_text      text NOT NULL,
    structured_json   jsonb NOT NULL DEFAULT '{}'::jsonb,
    action_items_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    generated_at      timestamptz NOT NULL DEFAULT now()
);

-- Global settings, edited from the panel's Settings page. Values are stored as
-- JSON so a key can hold a string, a number or a boolean without extra columns.
CREATE TABLE app_config (
    key        text PRIMARY KEY,
    value_json jsonb NOT NULL
);

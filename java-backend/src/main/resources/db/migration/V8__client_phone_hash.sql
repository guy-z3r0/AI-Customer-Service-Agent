-- A way of finding a customer by their number without decrypting everybody's.
--
-- phone_enc is a pgcrypto blob, and pgcrypto gives the same number a different
-- ciphertext every time it is written, so there has never been anything to
-- match on but the plain text. Finding an inbound caller meant decrypting the
-- whole customer list and comparing — and V3's try_decrypt opens a Postgres
-- subtransaction per row, which is the documented slow path. At one business's
-- size that is free; at ten thousand customers it is ten thousand decryptions
-- and ten thousand subtransactions, on an inbound call, while somebody waits.
--
-- The hash is over the last nine digits of the number, which is what makes
-- +8801711111111 and 01711111111 the same person. Nine because that is the
-- shortest form the application will treat as a full match: below it the
-- suffix comparison in ClientService is looser than a hash can be, so a miss
-- here falls back to the old scan rather than answering "nobody".
--
-- It is a hash of a phone number, which is a small enough space to walk
-- through, so this is not a way of hiding the number — phone_enc still is. It
-- is an index.

ALTER TABLE client ADD COLUMN IF NOT EXISTS phone_hash bytea;

CREATE INDEX IF NOT EXISTS idx_client_phone_hash ON client (business_id, phone_hash);

-- Fill it in for rows that already exist. Rows written under a different PII
-- key decrypt to null and stay null, which is the same "cannot be read" state
-- the panel already shows for them.
UPDATE client
SET phone_hash = digest(
        right(regexp_replace(try_decrypt(phone_enc, '${pii_enc_key}'), '\D', '', 'g'), 9),
        'sha256')
WHERE phone_hash IS NULL
  AND length(regexp_replace(
        coalesce(try_decrypt(phone_enc, '${pii_enc_key}'), ''), '\D', '', 'g')) >= 9;

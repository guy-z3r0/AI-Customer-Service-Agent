-- Reading a customer's contact details without betting the page on the key.
--
-- Customer phone numbers and email addresses are encrypted by Postgres with
-- PII_ENC_KEY. If that key is ever changed — and docs/SETUP.md tells you to
-- change it before storing anyone's real details — every row written under the
-- old one stops decrypting, and pgp_sym_decrypt does not fail gently: it raises,
-- which aborts the whole transaction and takes the rest of the query with it.
--
-- This wrapper turns that into a null. A record whose contact details cannot be
-- read is still a record: the name, the notes and the history are all readable,
-- and the panel says plainly that the contacts are not.

CREATE OR REPLACE FUNCTION try_decrypt(payload bytea, encryption_key text)
    RETURNS text
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF payload IS NULL THEN
        RETURN NULL;
    END IF;
    RETURN pgp_sym_decrypt(payload, encryption_key);
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$;

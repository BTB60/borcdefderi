-- HesabPro 2.0: customer codes (HP-000001), last contact, internal notes and the customer portal link.
-- Additive only. Rollback script: db/rollback/V9__undo.sql.

-- 1) Unique customer code, never reused --------------------------------------------------------------
CREATE SEQUENCE customer_code_seq START 1;
ALTER TABLE customers ADD COLUMN code VARCHAR(20);
-- existing customers get codes in the order they were created
UPDATE customers c SET code = 'HP-' || LPAD(o.rn::text, 6, '0')
FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS rn FROM customers) o
WHERE o.id = c.id;
SELECT setval('customer_code_seq', GREATEST((SELECT COUNT(*) FROM customers), 1), (SELECT COUNT(*) FROM customers) > 0);
ALTER TABLE customers ALTER COLUMN code SET DEFAULT ('HP-' || LPAD(nextval('customer_code_seq')::text, 6, '0'));
ALTER TABLE customers ALTER COLUMN code SET NOT NULL;
CREATE UNIQUE INDEX ux_customers_code ON customers(code);

-- 2) Last time the store contacted the customer (WhatsApp / call button) --------------------------
ALTER TABLE customers ADD COLUMN last_contact_at TIMESTAMPTZ;

-- 3) Internal notes about a customer (store staff only, append-only like the ledger) -----------------
CREATE TABLE customer_notes (
    id          UUID PRIMARY KEY,
    store_id    UUID          NOT NULL REFERENCES stores(id),
    customer_id UUID          NOT NULL,
    body        VARCHAR(1000) NOT NULL,
    author_id   UUID          NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    FOREIGN KEY (customer_id, store_id) REFERENCES customers(id, store_id)
);
CREATE INDEX ix_customer_notes_customer ON customer_notes(customer_id, created_at DESC);
CREATE TRIGGER trg_customer_notes_immutable BEFORE UPDATE OR DELETE ON customer_notes
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- 4) Customer portal links: only the SHA-256 of the random token is stored -----------------------------
CREATE TABLE customer_portal_tokens (
    id          UUID PRIMARY KEY,
    store_id    UUID         NOT NULL REFERENCES stores(id),
    customer_id UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_by  UUID         NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    FOREIGN KEY (customer_id, store_id) REFERENCES customers(id, store_id)
);
CREATE INDEX ix_portal_tokens_customer ON customer_portal_tokens(customer_id);

-- 5) Search by receipt (çek) number on ledger entries --------------------------------------------------
CREATE INDEX ix_fin_tx_receipt ON financial_transactions(store_id, receipt_number) WHERE receipt_number IS NOT NULL;

-- Nisyə hissə-hissə (taksit): a customer's debt split into dated parts. Payments recorded after the plan starts
-- pay the parts in order; nothing else in the debt / payment model changes.
CREATE TABLE installment_plans (
    id           UUID PRIMARY KEY,
    store_id     UUID          NOT NULL REFERENCES stores(id),
    customer_id  UUID          NOT NULL,
    total        NUMERIC(14,2) NOT NULL CHECK (total > 0),
    started_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    note         VARCHAR(300),
    created_by   UUID          NOT NULL REFERENCES users(id),
    closed_at    TIMESTAMPTZ,
    FOREIGN KEY (customer_id, store_id) REFERENCES customers(id, store_id)
);
CREATE UNIQUE INDEX ux_installment_plan_open ON installment_plans(customer_id) WHERE closed_at IS NULL;
CREATE INDEX ix_installment_plans_store ON installment_plans(store_id) WHERE closed_at IS NULL;

CREATE TABLE installment_items (
    plan_id   UUID          NOT NULL REFERENCES installment_plans(id) ON DELETE CASCADE,
    seq       INT           NOT NULL CHECK (seq > 0),
    due_date  DATE          NOT NULL,
    amount    NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (plan_id, seq)
);

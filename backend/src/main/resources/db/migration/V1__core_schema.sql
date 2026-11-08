-- BORCDEFTERI core schema (PostgreSQL). Money: NUMERIC(14,2). Times: TIMESTAMPTZ.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(190) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    phone         VARCHAR(30),
    platform_role VARCHAR(20)  NOT NULL DEFAULT 'USER' CHECK (platform_role IN ('SUPER_ADMIN', 'USER')),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE stores (
    id         UUID PRIMARY KEY,
    name       VARCHAR(150) NOT NULL,
    phone      VARCHAR(30),
    address    VARCHAR(255),
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A user belongs to exactly one store (owner or employee).
CREATE TABLE store_members (
    id         UUID PRIMARY KEY,
    store_id   UUID        NOT NULL REFERENCES stores(id),
    user_id    UUID        NOT NULL UNIQUE REFERENCES users(id),
    role       VARCHAR(20) NOT NULL CHECK (role IN ('STORE_OWNER', 'EMPLOYEE')),
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, store_id)
);
CREATE INDEX ix_store_members_store ON store_members(store_id);

CREATE TABLE member_permissions (
    member_id  UUID        NOT NULL REFERENCES store_members(id) ON DELETE CASCADE,
    permission VARCHAR(40) NOT NULL CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS')),
    PRIMARY KEY (member_id, permission)
);

CREATE TABLE subscription_plans (
    id              UUID PRIMARY KEY,
    code            VARCHAR(30)   NOT NULL UNIQUE CHECK (code IN ('START', 'STANDARD', 'PREMIUM', 'PREMIUM_YEARLY')),
    name            VARCHAR(100)  NOT NULL,
    price           NUMERIC(12,2) NOT NULL CHECK (price >= 0),
    duration_months INT           NOT NULL CHECK (duration_months > 0),
    active          BOOLEAN       NOT NULL DEFAULT TRUE
);

INSERT INTO subscription_plans (id, code, name, price, duration_months, active) VALUES
    ('00000000-0000-0000-0000-000000000001', 'START',          'Başlanğıc',      9.00,  1,  TRUE),
    ('00000000-0000-0000-0000-000000000002', 'STANDARD',       'Standart',       19.00, 1,  TRUE),
    ('00000000-0000-0000-0000-000000000003', 'PREMIUM',        'Premium',        29.00, 1,  TRUE),
    ('00000000-0000-0000-0000-000000000004', 'PREMIUM_YEARLY', 'Premium İllik',  290.00, 12, TRUE);

CREATE TABLE subscriptions (
    id            UUID PRIMARY KEY,
    store_id      UUID         NOT NULL REFERENCES stores(id),
    plan_id       UUID         NOT NULL REFERENCES subscription_plans(id),
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'REJECTED')),
    amount        NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    start_date    DATE,
    end_date      DATE,
    payer_reference VARCHAR(120),
    reject_reason VARCHAR(500),
    requested_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_at   TIMESTAMPTZ,
    reviewed_by   UUID REFERENCES users(id),
    CHECK (status <> 'ACTIVE' OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date >= start_date))
);
CREATE INDEX ix_subscriptions_store ON subscriptions(store_id, status);
CREATE INDEX ix_subscriptions_status_end ON subscriptions(status, end_date);

CREATE TABLE subscription_receipts (
    id            UUID PRIMARY KEY,
    subscription_id UUID NOT NULL UNIQUE REFERENCES subscriptions(id),
    store_id      UUID         NOT NULL REFERENCES stores(id),
    storage_name  VARCHAR(120) NOT NULL,
    original_name VARCHAR(255),
    content_type  VARCHAR(60)  NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0),
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE customers (
    id         UUID PRIMARY KEY,
    store_id   UUID         NOT NULL REFERENCES stores(id),
    name       VARCHAR(150) NOT NULL,
    phone      VARCHAR(30),
    note       VARCHAR(1000),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (id, store_id)
);
CREATE INDEX ix_customers_store_name ON customers(store_id, name);

CREATE TABLE companies (
    id         UUID PRIMARY KEY,
    store_id   UUID         NOT NULL REFERENCES stores(id),
    name       VARCHAR(150) NOT NULL,
    phone      VARCHAR(30),
    note       VARCHAR(1000),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (id, store_id)
);
CREATE INDEX ix_companies_store_name ON companies(store_id, name);

CREATE TABLE debts (
    id               UUID PRIMARY KEY,
    store_id         UUID          NOT NULL REFERENCES stores(id),
    debt_type        VARCHAR(20)   NOT NULL CHECK (debt_type IN ('CUSTOMER_DEBT', 'COMPANY_RECEIVABLE', 'COMPANY_PAYABLE')),
    customer_id      UUID,
    company_id       UUID,
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    paid_amount      NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    remaining_amount NUMERIC(14,2) NOT NULL CHECK (remaining_amount >= 0),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'PARTIAL', 'PAID')),
    debt_date        DATE          NOT NULL,
    due_date         DATE,
    description      VARCHAR(255),
    note             VARCHAR(1000),
    created_by       UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    UNIQUE (id, store_id),
    CHECK (paid_amount + remaining_amount = amount),
    CHECK (
        (debt_type = 'CUSTOMER_DEBT' AND customer_id IS NOT NULL AND company_id IS NULL)
        OR (debt_type <> 'CUSTOMER_DEBT' AND company_id IS NOT NULL AND customer_id IS NULL)
    ),
    FOREIGN KEY (customer_id, store_id) REFERENCES customers(id, store_id),
    FOREIGN KEY (company_id, store_id)  REFERENCES companies(id, store_id)
);
CREATE INDEX ix_debts_store_type ON debts(store_id, debt_type, status);
CREATE INDEX ix_debts_customer ON debts(customer_id);
CREATE INDEX ix_debts_company ON debts(company_id);

CREATE TABLE payments (
    id           UUID PRIMARY KEY,
    store_id     UUID          NOT NULL REFERENCES stores(id),
    debt_id      UUID          NOT NULL,
    amount       NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    payment_method VARCHAR(10) NOT NULL CHECK (payment_method IN ('CASH', 'CARD')),
    paid_at      TIMESTAMPTZ   NOT NULL,
    description  VARCHAR(255),
    note         VARCHAR(1000),
    recorded_by  UUID          NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    FOREIGN KEY (debt_id, store_id) REFERENCES debts(id, store_id)
);
CREATE INDEX ix_payments_store_paid ON payments(store_id, paid_at);
CREATE INDEX ix_payments_debt ON payments(debt_id);

-- Immutable financial ledger: every debt creation, adjustment and payment.
CREATE TABLE financial_transactions (
    id               UUID PRIMARY KEY,
    store_id         UUID          NOT NULL REFERENCES stores(id),
    debt_id          UUID          NOT NULL,
    payment_id       UUID          REFERENCES payments(id),
    transaction_type VARCHAR(30)   NOT NULL CHECK (transaction_type IN ('DEBT_CREATED', 'DEBT_ADJUSTED', 'PAYMENT_CASH', 'PAYMENT_CARD')),
    amount           NUMERIC(14,2) NOT NULL,
    balance_after    NUMERIC(14,2) NOT NULL CHECK (balance_after >= 0),
    actor_id         UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    FOREIGN KEY (debt_id, store_id) REFERENCES debts(id, store_id)
);
CREATE INDEX ix_fin_tx_store_time ON financial_transactions(store_id, created_at);
CREATE INDEX ix_fin_tx_debt ON financial_transactions(debt_id, created_at);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    store_id    UUID,
    actor_id    UUID,
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(40),
    entity_id   VARCHAR(60),
    details     VARCHAR(2000),
    ip_address  VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_store_time ON audit_logs(store_id, created_at);

-- Append-only guarantees for financial history and audit trail.
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: UPDATE/DELETE are not allowed', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_immutable BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_fin_tx_immutable BEFORE UPDATE OR DELETE ON financial_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_audit_immutable BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

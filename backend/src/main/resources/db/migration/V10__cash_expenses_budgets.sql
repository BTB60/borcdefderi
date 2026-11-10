-- HesabPro Cloud ERP, stage 2: cash / bank accounts, money movements, expenses, recurring expenses, budgets.
-- Additive only: no existing row or column is changed or removed. Rollback: db/rollback/V10__undo.sql.

-- 0) New employee permissions: cash desk and expenses ---------------------------------------------------
ALTER TABLE member_permissions DROP CONSTRAINT IF EXISTS member_permissions_permission_check;
ALTER TABLE member_permissions ADD CONSTRAINT member_permissions_permission_check
    CHECK (permission IN ('CUSTOMER_DEBTS', 'COMPANY_RECEIVABLES', 'COMPANY_PAYABLES', 'REPORTS', 'CASH', 'EXPENSES'));

-- 1) Cash desks, bank and card accounts -----------------------------------------------------------------
CREATE TABLE cash_accounts (
    id          UUID PRIMARY KEY,
    store_id    UUID         NOT NULL REFERENCES stores(id),
    name        VARCHAR(100) NOT NULL,
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('CASH', 'BANK', 'CARD')),
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (id, store_id)
);
CREATE UNIQUE INDEX ux_cash_accounts_name ON cash_accounts(store_id, LOWER(name));
-- one default account per kind and store (the one debt payments land in)
CREATE UNIQUE INDEX ux_cash_accounts_default ON cash_accounts(store_id, kind) WHERE is_default;

-- 2) Every money movement, append-only. Balance = SUM(IN) - SUM(OUT); never stored. ----------------------
CREATE TABLE cash_transactions (
    id                UUID PRIMARY KEY,
    store_id          UUID          NOT NULL REFERENCES stores(id),
    account_id        UUID          NOT NULL,
    direction         VARCHAR(3)    NOT NULL CHECK (direction IN ('IN', 'OUT')),
    amount            NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    kind              VARCHAR(20)   NOT NULL CHECK (kind IN (
                          'OPENING', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN', 'TRANSFER_OUT',
                          'DEBT_COLLECTION', 'SUPPLIER_PAYMENT', 'EXPENSE', 'EXPENSE_REVERSAL',
                          'SALE', 'SALE_REFUND', 'COUNT_ADJUSTMENT')),
    occurred_on       DATE          NOT NULL,
    transfer_id       UUID,
    payment_id        UUID REFERENCES payments(id),
    expense_id        UUID,
    note              VARCHAR(500),
    actor_id          UUID          NOT NULL REFERENCES users(id),
    client_request_id UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    FOREIGN KEY (account_id, store_id) REFERENCES cash_accounts(id, store_id)
);
CREATE INDEX ix_cash_tx_account ON cash_transactions(account_id, occurred_on);
CREATE INDEX ix_cash_tx_store_day ON cash_transactions(store_id, occurred_on);
CREATE UNIQUE INDEX ux_cash_tx_payment ON cash_transactions(payment_id) WHERE payment_id IS NOT NULL;
CREATE UNIQUE INDEX ux_cash_tx_request ON cash_transactions(store_id, client_request_id, direction)
    WHERE client_request_id IS NOT NULL;
CREATE TRIGGER trg_cash_tx_immutable BEFORE UPDATE OR DELETE ON cash_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- 3) Expense categories: 15 shared defaults (store_id NULL) + each store's own ------------------------------
CREATE TABLE expense_categories (
    id         UUID PRIMARY KEY,
    store_id   UUID REFERENCES stores(id),
    code       VARCHAR(30),
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_expense_categories_name ON expense_categories(COALESCE(store_id, '00000000-0000-0000-0000-000000000000'), LOWER(name));
INSERT INTO expense_categories (id, store_id, code, name) VALUES
    ('10000000-0000-0000-0000-000000000001', NULL, 'RENT',        'İcarə'),
    ('10000000-0000-0000-0000-000000000002', NULL, 'SALARY',      'İşçi maaşı'),
    ('10000000-0000-0000-0000-000000000003', NULL, 'ELECTRICITY', 'Elektrik'),
    ('10000000-0000-0000-0000-000000000004', NULL, 'WATER',       'Su'),
    ('10000000-0000-0000-0000-000000000005', NULL, 'GAS',         'Qaz'),
    ('10000000-0000-0000-0000-000000000006', NULL, 'INTERNET',    'İnternet'),
    ('10000000-0000-0000-0000-000000000007', NULL, 'PHONE',       'Telefon'),
    ('10000000-0000-0000-0000-000000000008', NULL, 'FUEL',        'Yanacaq'),
    ('10000000-0000-0000-0000-000000000009', NULL, 'TRANSPORT',   'Nəqliyyat'),
    ('10000000-0000-0000-0000-000000000010', NULL, 'ADVERTISING', 'Reklam'),
    ('10000000-0000-0000-0000-000000000011', NULL, 'OFFICE',      'Ofis xərcləri'),
    ('10000000-0000-0000-0000-000000000012', NULL, 'REPAIR',      'Təmir'),
    ('10000000-0000-0000-0000-000000000013', NULL, 'EQUIPMENT',   'Avadanlıq'),
    ('10000000-0000-0000-0000-000000000014', NULL, 'TAXES',       'Vergilər'),
    ('10000000-0000-0000-0000-000000000015', NULL, 'OTHER',       'Digər');

-- 4) Recurring expenses: a planned obligation until it is actually paid --------------------------------
CREATE TABLE recurring_expenses (
    id            UUID PRIMARY KEY,
    store_id      UUID          NOT NULL REFERENCES stores(id),
    title         VARCHAR(150)  NOT NULL,
    category_id   UUID          NOT NULL REFERENCES expense_categories(id),
    amount        NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    frequency     VARCHAR(10)   NOT NULL CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    start_date    DATE          NOT NULL,
    end_date      DATE,
    next_due_date DATE          NOT NULL,
    account_id    UUID,
    payee         VARCHAR(150),
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by    UUID          NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CHECK (end_date IS NULL OR end_date >= start_date),
    UNIQUE (id, store_id),
    FOREIGN KEY (account_id, store_id) REFERENCES cash_accounts(id, store_id)
);
CREATE INDEX ix_recurring_due ON recurring_expenses(store_id, active, next_due_date);

-- 5) Expenses (never mixed with debts or debt payments) ---------------------------------------------------
CREATE TABLE expenses (
    id              UUID PRIMARY KEY,
    store_id        UUID          NOT NULL REFERENCES stores(id),
    title           VARCHAR(150)  NOT NULL,
    category_id     UUID          NOT NULL REFERENCES expense_categories(id),
    amount          NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    expense_date    DATE          NOT NULL,
    payment_method  VARCHAR(10)   NOT NULL CHECK (payment_method IN ('CASH', 'CARD', 'TRANSFER', 'OTHER')),
    account_id      UUID          NOT NULL,
    payee           VARCHAR(150),
    note            VARCHAR(1000),
    recurring_id    UUID,
    period_date     DATE,
    deleted_at      TIMESTAMPTZ,
    deleted_by      UUID REFERENCES users(id),
    created_by      UUID          NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    client_request_id UUID,
    UNIQUE (id, store_id),
    FOREIGN KEY (account_id, store_id) REFERENCES cash_accounts(id, store_id),
    FOREIGN KEY (recurring_id, store_id) REFERENCES recurring_expenses(id, store_id)
);
CREATE INDEX ix_expenses_store_date ON expenses(store_id, expense_date) WHERE deleted_at IS NULL;
-- one actual expense per recurring period, even with double taps or two users
CREATE UNIQUE INDEX ux_expenses_recurring_period ON expenses(recurring_id, period_date)
    WHERE recurring_id IS NOT NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX ux_expenses_request ON expenses(store_id, client_request_id) WHERE client_request_id IS NOT NULL;

ALTER TABLE cash_transactions ADD CONSTRAINT fk_cash_tx_expense FOREIGN KEY (expense_id, store_id) REFERENCES expenses(id, store_id);

-- Receipt photo / document of an expense, stored in the database like subscription receipts.
CREATE TABLE expense_attachments (
    id            UUID PRIMARY KEY,
    store_id      UUID         NOT NULL REFERENCES stores(id),
    expense_id    UUID         NOT NULL,
    original_name VARCHAR(255),
    content_type  VARCHAR(60)  NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0),
    content       BYTEA        NOT NULL,
    uploaded_by   UUID         NOT NULL REFERENCES users(id),
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (expense_id, store_id) REFERENCES expenses(id, store_id)
);
CREATE INDEX ix_expense_attachments ON expense_attachments(expense_id);

-- 6) Monthly budget per category ---------------------------------------------------------------------------
CREATE TABLE budgets (
    id           UUID PRIMARY KEY,
    store_id     UUID          NOT NULL REFERENCES stores(id),
    category_id  UUID          NOT NULL REFERENCES expense_categories(id),
    month        DATE          NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    limit_amount NUMERIC(14,2) NOT NULL CHECK (limit_amount > 0),
    created_by   UUID          NOT NULL REFERENCES users(id),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (store_id, category_id, month)
);

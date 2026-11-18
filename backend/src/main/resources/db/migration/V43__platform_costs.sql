-- Platforma xərcləri: what running HesabPro costs the platform owner (server, database, domain, e-mail…),
-- when each bill is due and what was paid. Amounts are typed by the admin; 0 = free plan.
CREATE TABLE platform_costs (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    provider    VARCHAR(100),
    url         VARCHAR(300),
    amount      NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    currency    VARCHAR(3)    NOT NULL DEFAULT 'USD' CHECK (currency IN ('USD', 'AZN', 'EUR')),
    period      VARCHAR(10)   NOT NULL DEFAULT 'MONTHLY' CHECK (period IN ('MONTHLY', 'YEARLY', 'ONCE')),
    next_due    DATE,
    note        VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order  INT           NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE platform_cost_payments (
    id          UUID PRIMARY KEY,
    cost_id     UUID          NOT NULL REFERENCES platform_costs(id),
    paid_on     DATE          NOT NULL,
    amount      NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
    currency    VARCHAR(3)    NOT NULL,
    note        VARCHAR(300),
    created_by  UUID          NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_platform_cost_payments ON platform_cost_payments(cost_id, paid_on DESC);

-- what HesabPro runs on today; the admin fills in the amounts and dates
INSERT INTO platform_costs (id, name, provider, url, amount, currency, period, note, sort_order) VALUES
    ('00000000-0000-0000-0000-00000000c001', 'Server (backend API)', 'Render', 'https://dashboard.render.com/billing', 0, 'USD', 'MONTHLY', 'Free plan: 15 dəq. istifadə olmayanda yatır. Starter — həmişə işləyir', 1),
    ('00000000-0000-0000-0000-00000000c002', 'Verilənlər bazası (PostgreSQL)', 'Neon', 'https://console.neon.tech/app/billing', 0, 'USD', 'MONTHLY', NULL, 2),
    ('00000000-0000-0000-0000-00000000c003', 'Domen hesabpro.cloud', 'Hostinger', 'https://hpanel.hostinger.com/domains', 0, 'USD', 'YEARLY', 'Yenilənmə tarixini Hostinger-dən yazın', 3),
    ('00000000-0000-0000-0000-00000000c004', 'Veb sayt (frontend)', 'Vercel', 'https://vercel.com/account/billing', 0, 'USD', 'MONTHLY', NULL, 4),
    ('00000000-0000-0000-0000-00000000c005', 'E-poçt göndərmə (SMTP)', 'Brevo', 'https://app.brevo.com/billing', 0, 'USD', 'MONTHLY', NULL, 5);

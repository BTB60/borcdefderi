-- Çatdırılma və kuryer: an order to deliver (optionally from a sale) goes to a courier — an employee of the store
-- (job title COURIER) or an outside courier by name and phone — and moves NEW -> ASSIGNED -> ON_THE_WAY -> DELIVERED
-- (or CANCELLED). The customer follows it by a public link without signing in.
ALTER TABLE store_members DROP CONSTRAINT IF EXISTS store_members_job_title_check;
ALTER TABLE store_members ADD CONSTRAINT store_members_job_title_check
    CHECK (job_title IS NULL OR job_title IN ('CASHIER', 'STOREKEEPER', 'ACCOUNTANT', 'SELLER', 'COURIER', 'OTHER'));

CREATE SEQUENCE delivery_number_seq START 1;

CREATE TABLE deliveries (
    id               UUID PRIMARY KEY,
    store_id         UUID          NOT NULL REFERENCES stores(id),
    number           VARCHAR(20)   NOT NULL UNIQUE,
    sale_id          UUID,
    customer_id      UUID,
    recipient_name   VARCHAR(150)  NOT NULL,
    recipient_phone  VARCHAR(30)   NOT NULL,
    address          VARCHAR(500)  NOT NULL,
    note             VARCHAR(500),
    courier_user_id  UUID          REFERENCES users(id),
    courier_name     VARCHAR(150),
    courier_phone    VARCHAR(30),
    fee              NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    collect_amount   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (collect_amount >= 0),
    collected_amount NUMERIC(14,2) CHECK (collected_amount >= 0),
    status           VARCHAR(12)   NOT NULL DEFAULT 'NEW'
                     CHECK (status IN ('NEW', 'ASSIGNED', 'ON_THE_WAY', 'DELIVERED', 'CANCELLED')),
    scheduled_for    TIMESTAMPTZ,
    assigned_at      TIMESTAMPTZ,
    picked_up_at     TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    cancel_reason    VARCHAR(300),
    track_token      VARCHAR(40)   NOT NULL UNIQUE,
    created_by       UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    FOREIGN KEY (sale_id, store_id) REFERENCES sales(id, store_id),
    FOREIGN KEY (customer_id, store_id) REFERENCES customers(id, store_id)
);
CREATE INDEX ix_deliveries_store ON deliveries(store_id, status, created_at DESC);
CREATE INDEX ix_deliveries_courier ON deliveries(courier_user_id, status) WHERE courier_user_id IS NOT NULL;

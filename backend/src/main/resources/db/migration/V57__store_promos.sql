-- Mağaza reklamları: each store's own promo posts (a product, a discount, a picture). The customers' app (HesabPro Kart)
-- shows the live posts of the stores whose bonus card the customer holds. Additive only.
CREATE TABLE store_promos (
    id            UUID PRIMARY KEY,
    store_id      UUID          NOT NULL REFERENCES stores(id),
    title         VARCHAR(80)   NOT NULL,
    subtitle      VARCHAR(200),
    badge         VARCHAR(20),
    old_price     NUMERIC(14,2) CHECK (old_price IS NULL OR old_price >= 0),
    new_price     NUMERIC(14,2) CHECK (new_price IS NULL OR new_price >= 0),
    color         VARCHAR(9),
    image         BYTEA,
    image_type    VARCHAR(30),
    image_version BIGINT        NOT NULL DEFAULT 0,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    starts_on     DATE,
    ends_on       DATE,
    sort_order    INT           NOT NULL DEFAULT 0,
    views         INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    UUID REFERENCES users(id),
    CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_store_promos_store ON store_promos(store_id, sort_order, created_at) WHERE active;

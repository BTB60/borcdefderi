-- Bonus Kart Design Studio: each store's saved card designs (the whole look as one JSON document) and how many
-- designs the AI made for the store per day (cost guard). Additive only.
CREATE TABLE card_studio_designs (
    id          UUID PRIMARY KEY,
    store_id    UUID         NOT NULL REFERENCES stores(id),
    name        VARCHAR(80)  NOT NULL,
    category    VARCHAR(30),
    spec        JSONB        NOT NULL,
    created_by  UUID REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_card_studio_designs_store ON card_studio_designs(store_id, updated_at DESC);

CREATE TABLE card_studio_ai_usage (
    store_id      UUID    NOT NULL REFERENCES stores(id),
    day           DATE    NOT NULL,
    designs       INT     NOT NULL DEFAULT 0,
    input_tokens  BIGINT  NOT NULL DEFAULT 0,
    output_tokens BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, day)
);

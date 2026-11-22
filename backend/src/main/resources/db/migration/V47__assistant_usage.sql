-- Köməkçi (AI): questions and tokens per store and day, for the daily limit and to see what the assistant costs.
-- Rollback: db/rollback/V47__undo.sql.
CREATE TABLE assistant_usage (
    store_id      UUID    NOT NULL REFERENCES stores(id),
    day           DATE    NOT NULL,
    questions     INT     NOT NULL DEFAULT 0,
    input_tokens  BIGINT  NOT NULL DEFAULT 0,
    output_tokens BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id, day)
);

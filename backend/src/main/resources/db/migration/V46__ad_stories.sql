-- Reklam hekayələri: picture stories from the platform admin, shown as circles on top of the phone's home screen.
-- A story is shown only while it is active, inside its dates and has a picture. Additive only.
-- Rollback: db/rollback/V46__undo.sql.
CREATE TABLE ad_stories (
    id            UUID PRIMARY KEY,
    title         VARCHAR(80)  NOT NULL,
    subtitle      VARCHAR(160),
    button_label  VARCHAR(30),
    link_url      VARCHAR(500),
    whatsapp      VARCHAR(30),
    image         BYTEA,
    image_type    VARCHAR(30),
    image_version BIGINT       NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    starts_on     DATE,
    ends_on       DATE,
    sort_order    INT          NOT NULL DEFAULT 0,
    views         INT          NOT NULL DEFAULT 0,
    clicks        INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    UUID REFERENCES users(id),
    CHECK (ends_on IS NULL OR starts_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_ad_stories_live ON ad_stories(sort_order, created_at) WHERE active;

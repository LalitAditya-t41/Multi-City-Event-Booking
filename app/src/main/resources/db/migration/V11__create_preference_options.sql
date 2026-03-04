-- owner: identity module
CREATE TABLE preference_options (
    id         BIGSERIAL    PRIMARY KEY,
    type       VARCHAR(50)  NOT NULL,
    value      VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_preference_type CHECK (type IN ('CITY', 'GENRE')),
    CONSTRAINT uk_preference_type_value UNIQUE (type, value)
);

CREATE INDEX idx_preference_type ON preference_options (type);
CREATE INDEX idx_preference_active ON preference_options (active);

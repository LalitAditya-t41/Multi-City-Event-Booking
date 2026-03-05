CREATE TABLE moderation_records (
    id                  BIGSERIAL PRIMARY KEY,
    review_id           BIGINT      NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    method              VARCHAR(20) NOT NULL,
    input_text          TEXT        NOT NULL,
    decision            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    flags               VARCHAR(500),
    scores_json         TEXT,
    moderator_id        BIGINT,
    reason              VARCHAR(500),
    auto_retry_count    INT         NOT NULL DEFAULT 0,
    retry_after         TIMESTAMP,
    decided_at          TIMESTAMP,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_moderation_method CHECK (method IN ('AUTO', 'MANUAL')),
    CONSTRAINT chk_moderation_decision CHECK (decision IN ('APPROVED', 'REJECTED', 'PENDING'))
);

CREATE INDEX idx_moderation_review_id ON moderation_records (review_id);
CREATE INDEX idx_moderation_retry_after ON moderation_records (retry_after)
    WHERE decision = 'PENDING' AND retry_after IS NOT NULL;
CREATE INDEX idx_moderation_method_decision ON moderation_records (method, decision);

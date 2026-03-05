CREATE TABLE review_eligibility (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    event_id            BIGINT      NOT NULL,
    slot_id             BIGINT,
    booking_id          BIGINT,
    status              VARCHAR(30) NOT NULL DEFAULT 'UNLOCKED',
    eligible_until      TIMESTAMP,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_review_eligibility_user_event UNIQUE (user_id, event_id),
    CONSTRAINT chk_review_eligibility_status CHECK (status IN ('UNLOCKED', 'REVOKED', 'EXPIRED'))
);

CREATE INDEX idx_review_eligibility_status ON review_eligibility (status);
CREATE INDEX idx_review_eligibility_booking ON review_eligibility (booking_id);
CREATE INDEX idx_review_eligibility_user ON review_eligibility (user_id);

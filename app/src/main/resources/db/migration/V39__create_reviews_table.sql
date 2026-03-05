CREATE TABLE reviews (
    id                              BIGSERIAL PRIMARY KEY,
    user_id                         BIGINT        NOT NULL,
    event_id                        BIGINT        NOT NULL,
    rating                          SMALLINT      NOT NULL,
    title                           VARCHAR(100)  NOT NULL,
    body                            VARCHAR(2000) NOT NULL,
    status                          VARCHAR(30)   NOT NULL DEFAULT 'SUBMITTED',
    attendance_verification_status  VARCHAR(40)   NOT NULL DEFAULT 'ATTENDANCE_SELF_REPORTED',
    rejection_reason                VARCHAR(255),
    published_at                    TIMESTAMP,
    submitted_at                    TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_at                      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_reviews_user_event UNIQUE (user_id, event_id),
    CONSTRAINT chk_reviews_rating CHECK (rating >= 1 AND rating <= 5),
    CONSTRAINT chk_reviews_status CHECK (status IN ('SUBMITTED', 'PENDING_MODERATION', 'APPROVED', 'PUBLISHED', 'REJECTED')),
    CONSTRAINT chk_reviews_attendance_status CHECK (
        attendance_verification_status IN ('EB_VERIFIED', 'ATTENDANCE_SELF_REPORTED', 'ATTENDANCE_EB_UNAVAILABLE')
    )
);

CREATE INDEX idx_reviews_event_status ON reviews (event_id, status);
CREATE INDEX idx_reviews_user_id ON reviews (user_id);
CREATE INDEX idx_reviews_status ON reviews (status);
CREATE INDEX idx_reviews_event_rating ON reviews (event_id, rating) WHERE status = 'PUBLISHED';

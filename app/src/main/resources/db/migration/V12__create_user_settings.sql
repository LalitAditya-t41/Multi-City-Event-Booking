-- owner: identity module
CREATE TABLE user_settings (
    id                       BIGSERIAL    PRIMARY KEY,
    user_id                  BIGINT       NOT NULL,
    full_name                VARCHAR(255),
    phone                    VARCHAR(20),
    dob                      DATE,
    address                  TEXT,
    preferred_city_option_id BIGINT,
    notification_opt_in      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_settings_city_option FOREIGN KEY (preferred_city_option_id) REFERENCES preference_options(id)
);

CREATE UNIQUE INDEX uk_user_settings_user_id ON user_settings (user_id);
CREATE INDEX idx_user_settings_city_option ON user_settings (preferred_city_option_id);

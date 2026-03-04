-- owner: identity module
CREATE TABLE user_genre_preferences (
    id                   BIGSERIAL   PRIMARY KEY,
    user_settings_id     BIGINT      NOT NULL,
    preference_option_id BIGINT      NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_genre_settings FOREIGN KEY (user_settings_id) REFERENCES user_settings(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_genre_option FOREIGN KEY (preference_option_id) REFERENCES preference_options(id),
    CONSTRAINT uk_user_genre_preference UNIQUE (user_settings_id, preference_option_id)
);

CREATE INDEX idx_genre_pref_option_id ON user_genre_preferences (preference_option_id);

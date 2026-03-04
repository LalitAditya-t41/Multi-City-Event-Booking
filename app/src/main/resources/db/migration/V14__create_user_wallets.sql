-- owner: identity module
CREATE TABLE user_wallets (
    id             BIGSERIAL      PRIMARY KEY,
    user_id        BIGINT         NOT NULL,
    balance_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency       VARCHAR(10)    NOT NULL DEFAULT 'INR',
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_wallet_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_user_wallet_user_id ON user_wallets (user_id);

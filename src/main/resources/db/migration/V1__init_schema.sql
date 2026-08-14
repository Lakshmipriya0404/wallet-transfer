CREATE TABLE wallets (
    user_id VARCHAR(255) PRIMARY KEY,
    balance_paise BIGINT NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    from_user_id VARCHAR(255) NOT NULL REFERENCES wallets(user_id),
    to_user_id VARCHAR(255) NOT NULL REFERENCES wallets(user_id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT uk_transfers_idempotency UNIQUE (from_user_id, idempotency_key)
);

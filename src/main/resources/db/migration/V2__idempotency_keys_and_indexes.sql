CREATE TABLE idempotency_keys (
    user_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL, -- PENDING, SUCCESS, FAILED
    transfer_id UUID,
    response_balance BIGINT,
    error_code VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, idempotency_key)
);

-- Remove the old uniqueness constraint on transfers
ALTER TABLE transfers DROP CONSTRAINT uk_transfers_idempotency;

-- We don't strictly need idempotency_key on transfers anymore, but we can leave it for auditing.

-- Add indexes for performance
CREATE INDEX idx_transfers_from_user ON transfers(from_user_id);
CREATE INDEX idx_transfers_to_user ON transfers(to_user_id);
CREATE INDEX idx_transfers_created_at ON transfers(created_at);

ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);
CREATE TABLE auth_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    generation BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    last_active_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    revoked_at TIMESTAMP(3) NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions(expires_at);
CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);
CREATE TABLE auth_refresh_receipts (
    session_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    consumed_hash CHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    result_ciphertext TEXT NOT NULL,
    valid_until TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (session_id, request_id),
    FOREIGN KEY (session_id) REFERENCES auth_sessions(id) ON DELETE CASCADE
);
CREATE INDEX idx_auth_receipts_expiry ON auth_refresh_receipts(valid_until);

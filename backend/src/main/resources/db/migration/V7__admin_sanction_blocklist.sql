-- Intentionally stores neither the original provider identity nor a user reference.
CREATE TABLE sanction_blocklist (
    identity_hash CHAR(64) PRIMARY KEY CHECK (identity_hash ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ON sanction_blocklist (expires_at);
CREATE INDEX ON sanctions (user_id, starts_at, ends_at) WHERE type IN ('SUSPEND', 'BAN');

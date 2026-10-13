-- Design DDL for MySQL 8.0 / InnoDB. NOT applied to production.
-- Use the dedicated database selected by the administrator.
-- No CREATE DATABASE, credentials, destructive cleanup, or automatic migration.
-- DDL implicitly commits: record version/checksum ONLY after all definitions verify.

CREATE TABLE oauth_schema_versions (
    version INT UNSIGNED NOT NULL PRIMARY KEY,
    checksum_sha256 BINARY(32) NOT NULL,
    applied_at BIGINT UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE oauth_links (
    link_id BINARY(16) NOT NULL PRIMARY KEY,
    device_secret_hash BINARY(32) NULL,
    status ENUM('pending','exchanging','ready','active','refreshing',
                'reauth_required','revoke_pending','revoking','revoked') NOT NULL,
    disconnect_requested TINYINT UNSIGNED NOT NULL DEFAULT 0,
    revocation_uncertain TINYINT UNSIGNED NOT NULL DEFAULT 0,
    generation BIGINT UNSIGNED NOT NULL DEFAULT 0,
    token_key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    token_bundle MEDIUMBLOB NULL,
    access_expires_at BIGINT UNSIGNED NULL,
    granted_scope VARCHAR(1024) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operation_id BINARY(16) NULL,
    unresolved_operation_id BINARY(16) NULL,
    operation_deadline BIGINT UNSIGNED NULL,
    revoke_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_revoke_at BIGINT UNSIGNED NULL,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at BIGINT UNSIGNED NOT NULL,
    updated_at BIGINT UNSIGNED NOT NULL,
    disconnected_at BIGINT UNSIGNED NULL,
    revoked_at BIGINT UNSIGNED NULL,
    KEY ix_links_revoke (status, next_revoke_at),
    KEY ix_links_operation (status, operation_deadline),
    KEY ix_links_retention (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE oauth_link_sessions (
    link_id BINARY(16) NOT NULL PRIMARY KEY,
    state_hash BINARY(32) NULL,
    launch_token_hash BINARY(32) NULL,
    verifier_challenge BINARY(32) NOT NULL,
    browser_nonce_hash BINARY(32) NULL,
    outcome ENUM('pending','browser_started','exchanging','ready',
                 'complete','denied','failed','expired') NOT NULL DEFAULT 'pending',
    created_at BIGINT UNSIGNED NOT NULL,
    expires_at BIGINT UNSIGNED NOT NULL,
    state_consumed_at BIGINT UNSIGNED NULL,
    completed_at BIGINT UNSIGNED NULL,
    UNIQUE KEY uq_sessions_state (state_hash),
    KEY ix_sessions_expiry (expires_at),
    CONSTRAINT fk_sessions_link FOREIGN KEY (link_id)
        REFERENCES oauth_links (link_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE oauth_rate_buckets (
    bucket_key BINARY(32) NOT NULL,
    window_start BIGINT UNSIGNED NOT NULL,
    hits INT UNSIGNED NOT NULL DEFAULT 0,
    expires_at BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (bucket_key, window_start),
    KEY ix_rate_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Enforce cross-column state invariants in the repository transaction layer.
-- Do not rely on CHECK constraints: older MySQL 8.0 minors did not enforce them.
-- Hashes/IDs must be length-validated before binding; BINARY may pad short values.
-- last_error_code contains allowlisted machine codes only, never provider messages.

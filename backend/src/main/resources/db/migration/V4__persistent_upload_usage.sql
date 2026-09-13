CREATE TABLE upload_usage (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    window_started_at TIMESTAMPTZ NOT NULL,
    charged_bytes BIGINT NOT NULL CHECK (charged_bytes >= 0)
);

-- Run once against the live Neon database to add thread management.
CREATE TABLE IF NOT EXISTS creatoros.chat_threads (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    BIGINT      REFERENCES creatoros.users(id) ON DELETE CASCADE,
    title      TEXT        NOT NULL DEFAULT 'New Chat',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_threads_user ON creatoros.chat_threads (user_id, updated_at DESC);

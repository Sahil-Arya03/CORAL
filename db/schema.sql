-- CreatorOS / Coral — federated source schema
-- Catalog names in SchemaCatalog are dotted (e.g. github.commits), which map
-- directly onto Postgres schema.table, so validated SQL runs unchanged.
-- Safe to re-run: drops and recreates the source schemas.

DROP SCHEMA IF EXISTS github   CASCADE;
DROP SCHEMA IF EXISTS notion   CASCADE;
DROP SCHEMA IF EXISTS calendar CASCADE;
DROP SCHEMA IF EXISTS gmail    CASCADE;

CREATE SCHEMA github;
CREATE SCHEMA notion;
CREATE SCHEMA calendar;
CREATE SCHEMA gmail;

-- github.commits
CREATE TABLE github.commits (
    sha          TEXT PRIMARY KEY,
    repo         TEXT        NOT NULL,
    author       TEXT        NOT NULL,
    message      TEXT        NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL
);

-- github.pull_requests
CREATE TABLE github.pull_requests (
    id         TEXT PRIMARY KEY,
    repo       TEXT        NOT NULL,
    title      TEXT        NOT NULL,
    state      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    merged_at  TIMESTAMPTZ
);

-- notion.tasks  (mutable: status, due_date, priority)
CREATE TABLE notion.tasks (
    id         TEXT PRIMARY KEY,
    title      TEXT        NOT NULL,
    status     TEXT        NOT NULL,
    priority   TEXT        NOT NULL,
    due_date   TIMESTAMPTZ,
    project    TEXT,
    updated_at TIMESTAMPTZ NOT NULL
);

-- calendar.events
CREATE TABLE calendar.events (
    id              TEXT PRIMARY KEY,
    title           TEXT        NOT NULL,
    start_at        TIMESTAMPTZ NOT NULL,
    end_at          TIMESTAMPTZ NOT NULL,
    attendees_count INTEGER     NOT NULL DEFAULT 0,
    is_meeting      BOOLEAN     NOT NULL DEFAULT FALSE
);

-- gmail.emails  (mutable: is_archived, is_unread)
CREATE TABLE gmail.emails (
    id          TEXT PRIMARY KEY,
    subject     TEXT        NOT NULL,
    sender      TEXT        NOT NULL,
    snippet     TEXT,
    is_unread   BOOLEAN     NOT NULL DEFAULT TRUE,
    received_at TIMESTAMPTZ NOT NULL,
    importance  TEXT        NOT NULL DEFAULT 'normal',
    is_archived BOOLEAN     NOT NULL DEFAULT FALSE
);

-- Helpful indexes for the timeline's ORDER BY <time> DESC reads.
CREATE INDEX idx_commits_time ON github.commits (committed_at DESC);
CREATE INDEX idx_prs_time     ON github.pull_requests (created_at DESC);
CREATE INDEX idx_tasks_due    ON notion.tasks (due_date);
CREATE INDEX idx_events_start ON calendar.events (start_at);
CREATE INDEX idx_emails_recv  ON gmail.emails (received_at DESC);


-- ════════════════════════════════════════════════════════════════════
--  CreatorOS-owned application schema (plan §9)
--  Memory, behavioral patterns, metrics, reflections, conversations, logs.
-- ════════════════════════════════════════════════════════════════════

DROP SCHEMA IF EXISTS creatoros CASCADE;
CREATE SCHEMA creatoros;

CREATE TABLE creatoros.users (
    id           BIGSERIAL    PRIMARY KEY,
    clerk_id     TEXT         UNIQUE,                      -- Clerk's user_* id; NULL for the seed/demo row
    email        TEXT         UNIQUE NOT NULL,
    username     TEXT,
    display_name TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_users_clerk_id
    ON creatoros.users (clerk_id) WHERE clerk_id IS NOT NULL;

-- 1. AI memory
CREATE TABLE creatoros.user_memory (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT REFERENCES creatoros.users(id) ON DELETE CASCADE,
    category           TEXT,        -- goal | preference | weakness | habit | observation
    content            TEXT NOT NULL,
    tags               TEXT[],
    importance         SMALLINT,    -- 1..5
    confidence         REAL,
    last_referenced_at TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_memory_tags ON creatoros.user_memory USING GIN (tags);
CREATE INDEX idx_user_memory_rank ON creatoros.user_memory (user_id, importance DESC, last_referenced_at DESC);

-- 2. Behavioral patterns (derived, recomputed)
CREATE TABLE creatoros.behavioral_patterns (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT REFERENCES creatoros.users(id) ON DELETE CASCADE,
    pattern_type   TEXT,            -- e.g. "peak_hours", "procrastination_subject"
    payload        JSONB,
    observed_count INTEGER,
    last_seen_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. Productivity metrics (one row per user per day)
CREATE TABLE creatoros.productivity_metrics (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT REFERENCES creatoros.users(id) ON DELETE CASCADE,
    metric_date          DATE NOT NULL,
    coding_consistency   REAL,
    task_completion_rate REAL,
    meeting_load_minutes INTEGER,
    deep_work_minutes    INTEGER,
    productivity_score   REAL,
    procrastination_flag BOOLEAN,
    raw                  JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, metric_date)
);
CREATE INDEX idx_metrics_user_date ON creatoros.productivity_metrics (user_id, metric_date);

-- 4. Daily reflections
CREATE TABLE creatoros.daily_reflections (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES creatoros.users(id) ON DELETE CASCADE,
    reflection_date DATE NOT NULL,
    summary         TEXT,
    mood            TEXT,
    ai_generated    BOOLEAN,
    source_refs     JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, reflection_date)
);
CREATE INDEX idx_reflections_user_date ON creatoros.daily_reflections (user_id, reflection_date);

-- 5. Conversations
CREATE TABLE creatoros.ai_conversations (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES creatoros.users(id) ON DELETE CASCADE,
    session_id  UUID,
    role        TEXT,            -- user | assistant
    content     TEXT,
    intent      JSONB,
    token_usage INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conv_user_session ON creatoros.ai_conversations (user_id, session_id, created_at);

-- 6. Action logs (audit + before-snapshot)
CREATE TABLE creatoros.action_logs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES creatoros.users(id) ON DELETE CASCADE,
    action_type     TEXT,
    target_schema   TEXT,
    validated_sql   TEXT,
    bindings        JSONB,
    before_snapshot JSONB,
    rows_affected   INTEGER,
    policy_verdict  TEXT,
    status          TEXT,        -- executed | denied | failed
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_action_logs_user ON creatoros.action_logs (user_id, created_at DESC);

-- 7. Query logs (prompt-tuning gold)
CREATE TABLE creatoros.query_logs (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT REFERENCES creatoros.users(id) ON DELETE CASCADE,
    session_id        UUID,
    user_prompt       TEXT,
    planned_sql       TEXT,
    validation_result TEXT,
    rejection_reason  TEXT,
    coral_latency_ms  INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_query_logs_user ON creatoros.query_logs (user_id, created_at DESC);

-- 8. Sync state — one row per integration key (e.g. "github:owner/repo")
CREATE TABLE creatoros.sync_state (
    integration    TEXT        PRIMARY KEY,
    last_synced_at TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 9. Chat threads — named conversation sessions (max 5 per user)
CREATE TABLE creatoros.chat_threads (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    BIGINT      REFERENCES creatoros.users(id) ON DELETE CASCADE,
    title      TEXT        NOT NULL DEFAULT 'New Chat',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_threads_user ON creatoros.chat_threads (user_id, updated_at DESC);

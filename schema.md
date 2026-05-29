# Coral / CreatorOS — Database Schema Reference

## Overview

The application uses a single **Neon PostgreSQL** instance split into two logical zones separated by Postgres schema prefixes:

| Zone | Schema prefix | Purpose | Who touches it |
|---|---|---|---|
| Federated source data | `github.`, `notion.`, `calendar.`, `gmail.` | Read-only replicas of external integrations | `CoralClient` only (package-private) |
| Application state | `creatoros.` | User data, AI memory, conversations, audit logs | Repository classes in `persistence/`, `MemoryInjectionService`, `LongTermMemoryService` |

No JPA / Hibernate — all queries use `JdbcTemplate` and `NamedParameterJdbcTemplate` directly.

---

## Schema Diagram (Entity Relationships)

```
creatoros.users (id)
    │
    ├──< creatoros.user_memory        (user_id FK)
    ├──< creatoros.behavioral_patterns (user_id FK)
    ├──< creatoros.productivity_metrics (user_id FK)
    ├──< creatoros.daily_reflections   (user_id FK)
    ├──< creatoros.ai_conversations    (user_id FK)
    ├──< creatoros.action_logs         (user_id FK)
    ├──< creatoros.query_logs          (user_id FK)
    └──< creatoros.chat_threads        (user_id FK)
              │
              └──< creatoros.ai_conversations (session_id = chat_threads.id)

── Federated tables (no FK relationships — logically linked in Java) ──

notion.tasks     ─── (implicit: title/project keywords appear in gmail.emails subjects)
github.commits   ─── (implicit: repo name correlates with notion.tasks.project)
github.pull_requests ─ (implicit: repo name correlates with notion.tasks.project)
calendar.events  ─── (implicit: event titles correlate with notion.tasks.title)
gmail.emails     ─── (implicit: subject correlates with notion.tasks.title)
```

> **Important:** The federated tables have no foreign keys between them or into `creatoros.*`. Cross-source correlation (e.g. "task + related email + commit in same project") is done entirely in Java inside `ContextAggregationService`, not via SQL JOINs.

---

## Zone 1 — Federated Source Data

### `github.commits`

Populated by `GitHubSyncAdapter` via incremental GitHub API sync. Immutable once inserted (`ON CONFLICT DO NOTHING`).

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `sha` | TEXT | NOT NULL | Primary key — commit SHA |
| `repo` | TEXT | NOT NULL | `"owner/repo"` string, e.g. `"arya/os-lab"` |
| `author` | TEXT | NOT NULL | GitHub login or commit author name |
| `message` | TEXT | NOT NULL | Commit message (full) |
| `committed_at` | TIMESTAMPTZ | NOT NULL | Commit timestamp |

**Constraints:** `PRIMARY KEY (sha)`

**Indexes:**
- `idx_commits_time` — `(committed_at DESC)` — used by timeline ORDER BY

**SchemaCatalog columns exposed to AI:** `sha`, `repo`, `author`, `message`, `committed_at`

**Allowed SQL operations:** `SELECT` only

**Seed data:**
```sql
('a1b2', 'os-lab', 'arya', 'wip: scheduler',            now() - 2 days)
('c3d4', 'dsa',    'arya', 'add two-pointer solutions', now() - 6 hours)
```

---

### `github.pull_requests`

Populated by `GitHubSyncAdapter`. State and `merged_at` are updated on re-sync (`ON CONFLICT DO UPDATE`).

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | TEXT | NOT NULL | Primary key — formatted as `"owner/repo#number"` |
| `repo` | TEXT | NOT NULL | `"owner/repo"` string |
| `title` | TEXT | NOT NULL | PR title |
| `state` | TEXT | NOT NULL | `open` \| `closed` \| `merged` |
| `created_at` | TIMESTAMPTZ | NOT NULL | PR creation timestamp |
| `merged_at` | TIMESTAMPTZ | NULL | Set when the PR is merged |

**Constraints:** `PRIMARY KEY (id)`

**Indexes:**
- `idx_prs_time` — `(created_at DESC)` — used by timeline ORDER BY

**SchemaCatalog columns exposed to AI:** `id`, `repo`, `title`, `state`, `created_at`, `merged_at`

**Allowed SQL operations:** `SELECT` only

**Upsert conflict behavior:**
```sql
ON CONFLICT (id) DO UPDATE
    SET state     = EXCLUDED.state,
        merged_at = EXCLUDED.merged_at
```

**Seed data:**
```sql
('pr1', 'os-lab', 'Scheduler v1', 'open', now() - 3 days, NULL)
```

---

### `notion.tasks`

Writable — the AI can UPDATE `status`, `due_date`, `priority` via validated mutations.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | TEXT | NOT NULL | Primary key |
| `title` | TEXT | NOT NULL | Task name |
| `status` | TEXT | NOT NULL | e.g. `todo`, `in_progress`, `done` |
| `priority` | TEXT | NOT NULL | `low` \| `medium` \| `high` |
| `due_date` | TIMESTAMPTZ | NULL | Deadline |
| `project` | TEXT | NULL | Project bucket name |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Last modification timestamp |

**Constraints:** `PRIMARY KEY (id)`

**Indexes:**
- `idx_tasks_due` — `(due_date)` — used for deadline range queries

**SchemaCatalog columns exposed to AI:** `id`, `title`, `status`, `priority`, `due_date`, `project`, `updated_at`

**Allowed SQL operations:** `SELECT`, `UPDATE`, `DELETE`

**Mutable fields (UPDATE SET targets):** `status`, `due_date`, `priority`

**Policy rules governing mutations:**

| Action type | Operation | Field(s) | Max rows | Needs confirm |
|---|---|---|---|---|
| `task.update_status` | UPDATE | `status` | 1 | No |
| `task.update_deadline` | UPDATE | `due_date` | 1 | **Yes** |
| `task.update_priority` | UPDATE | `priority` | 1 | No |

**Seed data:**
```sql
('t1', 'Finish DBMS assignment', 'todo',        'high',   now() - 1 day,  'DBMS')
('t2', 'OS lab report',          'in_progress', 'medium', now() + 2 days, 'OS')
('t3', 'DSA practice set',       'todo',        'high',   now() + 1 day,  'DSA')
```

---

### `calendar.events`

Read-only. No sync adapter yet — data is inserted manually or via seed.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | TEXT | NOT NULL | Primary key |
| `title` | TEXT | NOT NULL | Event name |
| `start_at` | TIMESTAMPTZ | NOT NULL | Event start |
| `end_at` | TIMESTAMPTZ | NOT NULL | Event end |
| `attendees_count` | INTEGER | NOT NULL | Defaults to 0 |
| `is_meeting` | BOOLEAN | NOT NULL | Defaults to FALSE |

**Constraints:** `PRIMARY KEY (id)`

**Indexes:**
- `idx_events_start` — `(start_at)` — used for time-range queries

**SchemaCatalog columns exposed to AI:** `id`, `title`, `start_at`, `end_at`, `attendees_count`, `is_meeting`

**Allowed SQL operations:** `SELECT` only

**Seed data:**
```sql
('e1', 'DBMS lecture', now() + 4 hours, now() + 5 hours, 40, TRUE)
('e2', 'Project sync', now() + 1 day,   now() + 1 day + 1 hour, 5, TRUE)
```

---

### `gmail.emails`

Writable — the AI can archive emails and delete duplicates.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | TEXT | NOT NULL | Primary key |
| `subject` | TEXT | NOT NULL | Email subject line |
| `sender` | TEXT | NOT NULL | Sender email address |
| `snippet` | TEXT | NULL | Short preview text |
| `is_unread` | BOOLEAN | NOT NULL | Defaults to TRUE |
| `received_at` | TIMESTAMPTZ | NOT NULL | Inbound timestamp |
| `importance` | TEXT | NOT NULL | `low` \| `normal` \| `high` — defaults to `'normal'` |
| `is_archived` | BOOLEAN | NOT NULL | Defaults to FALSE |

**Constraints:** `PRIMARY KEY (id)`

**Indexes:**
- `idx_emails_recv` — `(received_at DESC)` — used by timeline ORDER BY

**SchemaCatalog columns exposed to AI:** `id`, `subject`, `sender`, `snippet`, `is_unread`, `received_at`, `importance`, `is_archived`

**Allowed SQL operations:** `SELECT`, `UPDATE`, `DELETE`

**Mutable fields (UPDATE SET targets):** `is_archived`, `is_unread`

**Policy rules governing mutations:**

| Action type | Operation | Field(s) | Max rows | Needs confirm |
|---|---|---|---|---|
| `reminder.archive` | UPDATE | `is_archived` | 10 | No |
| `reminder.delete_dup` | DELETE | — | 5 | **Yes** |

**Seed data:**
```sql
('m1', 'DBMS assignment deadline moved to tomorrow', 'prof@univ.edu', ..., TRUE, now() - 4 hours, 'high',  FALSE)
('m2', 'Weekly newsletter',                          'news@list.com', ..., TRUE, now() - 1 day,   'low',   FALSE)
```

---

## Zone 2 — Application State (`creatoros.*`)

### `creatoros.users`

The single user table. Currently contains one demo user (`id = 1`), hardcoded throughout the application as `DEMO_USER_ID = 1L`.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key — auto-incremented |
| `email` | TEXT | NOT NULL | Unique |
| `display_name` | TEXT | NULL | — |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `UNIQUE (email)`

**Demo user (inserted by `StartupMigrationRunner` on every startup):**
```sql
INSERT INTO creatoros.users (id, email, display_name)
VALUES (1, 'arya@creatoros.dev', 'Alex')
ON CONFLICT (id) DO NOTHING
```

**Referenced by (FK `user_id`):** all other `creatoros.*` tables

---

### `creatoros.user_memory`

Long-term AI memory. Populated asynchronously after each READ/REFLECT exchange by `LongTermMemoryService`. Read by `MemoryInjectionService` to build the memory block injected into reasoning prompts.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `category` | TEXT | NULL | `goal` \| `preference` \| `weakness` \| `habit` \| `observation` |
| `content` | TEXT | NOT NULL | The memory statement |
| `tags` | TEXT[] | NULL | Array of short tags for relevance matching |
| `importance` | SMALLINT | NULL | 1–5 (5 = critical life/career goal) |
| `confidence` | REAL | NULL | 0.0–1.0 |
| `last_referenced_at` | TIMESTAMPTZ | NULL | Updated each time this memory is injected into a prompt |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

**Indexes:**
- `idx_user_memory_tags` — GIN index on `tags` array — used for tag-overlap relevance queries
- `idx_user_memory_rank` — `(user_id, importance DESC, last_referenced_at DESC)` — used by injection selection ORDER BY

**Selection logic (`MemoryInjectionService`):** A memory is injected if `importance >= 4` OR its `tags` array overlaps with the current intent's tags. Up to 12 memories per request. After selection, `last_referenced_at` is bumped to `now()`.

**Duplicate detection:** Before inserting, `LongTermMemoryService` checks for an existing row where `content ILIKE '%<first 80 chars>%'`.

**Min importance threshold for insertion:** 3 (facts below importance 3 are discarded immediately).

**Seed data:**
```sql
('goal',       'User wants DSA consistency',                   ['coding','dsa','tasks'], importance=5, confidence=0.9)
('habit',      'User is most productive after 8 PM',           ['overview','focus'],     importance=4, confidence=0.8)
('weakness',   'User frequently delays OS assignments',        ['tasks','os'],           importance=4, confidence=0.85)
('preference', 'User prefers concise, action-oriented answers',['overview'],             importance=3, confidence=0.95)
```

---

### `creatoros.behavioral_patterns`

Stores derived behavioral pattern data (e.g. peak productivity hours, procrastination subjects). Currently computed/stored externally — no application code writes to this table at runtime yet.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `pattern_type` | TEXT | NULL | e.g. `peak_hours`, `procrastination_subject` |
| `payload` | JSONB | NULL | Arbitrary structured data for this pattern |
| `observed_count` | INTEGER | NULL | How many times this pattern was observed |
| `last_seen_at` | TIMESTAMPTZ | NULL | — |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

---

### `creatoros.productivity_metrics`

One row per user per calendar day. Currently not written by runtime code — reserved for a future analytics compute job.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `metric_date` | DATE | NOT NULL | Calendar date |
| `coding_consistency` | REAL | NULL | 0.0–1.0 |
| `task_completion_rate` | REAL | NULL | 0.0–1.0 |
| `meeting_load_minutes` | INTEGER | NULL | Total calendar time in meetings |
| `deep_work_minutes` | INTEGER | NULL | Estimated focused work time |
| `productivity_score` | REAL | NULL | Composite score |
| `procrastination_flag` | BOOLEAN | NULL | Whether the day showed a delay pattern |
| `raw` | JSONB | NULL | Full raw metric payload |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `UNIQUE (user_id, metric_date)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

**Indexes:**
- `idx_metrics_user_date` — `(user_id, metric_date)` — used for date-range lookups

---

### `creatoros.daily_reflections`

One row per user per calendar day. Currently not written by runtime code — reserved for a scheduled reflection generation job.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `reflection_date` | DATE | NOT NULL | Calendar date |
| `summary` | TEXT | NULL | AI-generated or user-written reflection text |
| `mood` | TEXT | NULL | User-reported or inferred mood |
| `ai_generated` | BOOLEAN | NULL | Whether this was generated by the AI |
| `source_refs` | JSONB | NULL | References to source events (tasks, commits, emails) that informed this reflection |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `UNIQUE (user_id, reflection_date)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

**Indexes:**
- `idx_reflections_user_date` — `(user_id, reflection_date)` — used for date-range lookups

---

### `creatoros.ai_conversations`

Stores every chat turn (user + assistant) for conversation history and thread display. `session_id` ties turns to a `chat_threads` row.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `session_id` | UUID | NULL | Ties to `creatoros.chat_threads.id` (logical FK, no constraint) |
| `role` | TEXT | NULL | `user` \| `assistant` |
| `content` | TEXT | NULL | Message text |
| `intent` | JSONB | NULL | Serialized `IntentResult` — stored for the assistant turn only |
| `token_usage` | INTEGER | NULL | Reserved — not currently populated |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

**Indexes:**
- `idx_conv_user_session` — `(user_id, session_id, created_at)` — used for history retrieval and recent-turns window

**Key queries:**
```sql
-- Recent N turns for context injection (fetches last N, then re-orders ASC)
SELECT role, content FROM (
    SELECT role, content, created_at
    FROM creatoros.ai_conversations
    WHERE user_id = :uid AND session_id = :sid
    ORDER BY created_at DESC LIMIT :lim
) sub ORDER BY created_at ASC

-- Full thread history for display
SELECT role, content, created_at::text AS ts
FROM creatoros.ai_conversations
WHERE user_id = :uid AND session_id = :sid
ORDER BY created_at ASC
```

---

### `creatoros.action_logs`

Immutable audit trail for every mutation attempt — including denied and failed attempts.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `action_type` | TEXT | NULL | e.g. `task.update_status` — matches `PolicyEngine` rule key |
| `target_schema` | TEXT | NULL | e.g. `notion.tasks` |
| `validated_sql` | TEXT | NULL | The normalized SQL that was (or would have been) executed |
| `bindings` | JSONB | NULL | Named parameter map |
| `before_snapshot` | JSONB | NULL | Pre-mutation row state (reserved — not populated yet) |
| `rows_affected` | INTEGER | NULL | Actual rows changed (0 for denied/failed) |
| `policy_verdict` | TEXT | NULL | `ALLOW` \| `DENY` \| `CONFIRM` |
| `status` | TEXT | NULL | `executed` \| `denied` \| `failed` |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

**Indexes:**
- `idx_action_logs_user` — `(user_id, created_at DESC)` — used for audit queries

---

### `creatoros.query_logs`

Records every AI-planned SQL query and its validation outcome. Used for prompt-tuning and debugging.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | BIGSERIAL | NOT NULL | Primary key |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `session_id` | UUID | NULL | Session this query belonged to |
| `user_prompt` | TEXT | NULL | The raw user message that triggered this query |
| `planned_sql` | TEXT | NULL | The SQL the AI generated |
| `validation_result` | TEXT | NULL | `ok` \| `rejected` |
| `rejection_reason` | TEXT | NULL | `ValidationException` message if rejected |
| `coral_latency_ms` | INTEGER | NULL | Round-trip execution time for accepted queries |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (id)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

**Indexes:**
- `idx_query_logs_user` — `(user_id, created_at DESC)`

---

### `creatoros.sync_state`

One row per integration key. Tracks the watermark for incremental syncs so the GitHub adapter only fetches new data on each run.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `integration` | TEXT | NOT NULL | Primary key — e.g. `"github:arya/os-lab"` |
| `last_synced_at` | TIMESTAMPTZ | NULL | Watermark: only commits/PRs after this time are fetched |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |

**Constraints:** `PRIMARY KEY (integration)`

**No FK to users** — keys encode the integration identifier directly.

**Read/write pattern (`GitHubSyncAdapter`):**
```
1. SELECT last_synced_at WHERE integration = 'github:owner/repo'
   → if missing: defaults to now() - 30 days
2. Sync from GitHub API using ?since=<last_synced_at>
3. UPSERT last_synced_at = <sync start time>
```

---

### `creatoros.chat_threads`

Named conversation sessions. Each thread maps 1:N to `ai_conversations` via `session_id = chat_threads.id`. Maximum 5 threads per user (enforced in `ThreadRepository`).

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | UUID | NOT NULL | Primary key — `gen_random_uuid()` |
| `user_id` | BIGINT | NOT NULL | FK → `creatoros.users(id)` ON DELETE CASCADE |
| `title` | TEXT | NOT NULL | Defaults to `'New Chat'`; auto-set from intent summary on first turn |
| `created_at` | TIMESTAMPTZ | NOT NULL | Defaults to `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Bumped by `ThreadRepository.touch()` after every chat turn |

**Constraints:** `PRIMARY KEY (id)`, `FOREIGN KEY (user_id) → creatoros.users(id) ON DELETE CASCADE`

**Indexes:**
- `idx_threads_user` — `(user_id, updated_at DESC)` — used for thread list ORDER BY recency

**Title auto-generation:** On the first turn of a new thread, `OrchestrationService` calls `deriveTitle(intent.summary())` — capitalises and trims to 60 characters — and writes it via `ThreadRepository.updateTitle()`.

**Thread + messages join (used by `ThreadController.list()`):**
```sql
SELECT t.id, t.title, t.updated_at::text,
       COUNT(c.id) AS message_count
FROM creatoros.chat_threads t
LEFT JOIN creatoros.ai_conversations c
       ON c.session_id = t.id AND c.user_id = t.user_id
WHERE t.user_id = :uid
GROUP BY t.id, t.title, t.updated_at
ORDER BY t.updated_at DESC
```

---

## Cross-Source Logical Joins

The federated tables have no SQL foreign keys between them. Correlation is performed in `ContextAggregationService` and by the AI reasoning layer. The following logical relationships are exploited:

| Left | Right | Join key | How it's used |
|---|---|---|---|
| `notion.tasks.project` | `github.commits.repo` | project name ≈ repo name | Identify commits that may relate to an overdue task |
| `notion.tasks.project` | `github.pull_requests.repo` | project name ≈ repo name | Surface stalled PRs alongside incomplete tasks |
| `notion.tasks.title` | `gmail.emails.subject` | keyword overlap | Spot emails about a task deadline |
| `notion.tasks.due_date` | `calendar.events.start_at` | temporal proximity | Detect events with no associated task preparation |
| `notion.tasks.title` | `calendar.events.title` | keyword overlap | Connect a lecture/meeting to the relevant task |

These joins are implemented as AI prompt context — all rows from relevant tables are fetched separately, merged into a `TimelineEvent` list sorted by `occurredAt`, and the AI reasoning model identifies the connections in natural language.

---

## Mutation Safety Gates

Every write goes through three sequential gates before touching the database:

```
1. SqlValidationService (AST-level)
   ├─ Exactly 1 SQL statement (no ; chaining)
   ├─ Type is SELECT | UPDATE | DELETE only
   ├─ Every table is in SchemaCatalog AND in the allowed set for this intent
   ├─ Every column is in SchemaCatalog
   ├─ No SELECT *
   ├─ UPDATE SET targets must be in the table's mutableFields
   ├─ UPDATE / DELETE must have a bounded WHERE (column reference required — rejects WHERE 1=1)
   └─ LIMIT 200 injected on SELECT if absent

2. PolicyEngine (action whitelist)
   ├─ actionType must match a known rule key
   ├─ Rule's target table must match the query's primary table
   ├─ Rule's operation type must match (UPDATE vs DELETE)
   ├─ Every write field must be in the rule's allowed fields
   └─ Estimated affected rows must not exceed the rule's maxRows ceiling
      → DENY if any check fails
      → CONFIRM if rule.confirm == true (creates a PendingActionStore entry)
      → ALLOW otherwise

3. Re-validation on CONFIRM (ActionExecutionService path)
   └─ Full SqlValidationService re-run on the exact stored SQL before final execution
```

---

## Index Summary

| Index name | Table | Columns | Purpose |
|---|---|---|---|
| `idx_commits_time` | `github.commits` | `committed_at DESC` | Timeline ORDER BY |
| `idx_prs_time` | `github.pull_requests` | `created_at DESC` | Timeline ORDER BY |
| `idx_tasks_due` | `notion.tasks` | `due_date` | Deadline range queries |
| `idx_events_start` | `calendar.events` | `start_at` | Time-range queries |
| `idx_emails_recv` | `gmail.emails` | `received_at DESC` | Timeline ORDER BY |
| `idx_user_memory_tags` | `creatoros.user_memory` | `tags` (GIN) | Tag-overlap relevance matching |
| `idx_user_memory_rank` | `creatoros.user_memory` | `user_id, importance DESC, last_referenced_at DESC` | Memory injection selection |
| `idx_conv_user_session` | `creatoros.ai_conversations` | `user_id, session_id, created_at` | History retrieval |
| `idx_action_logs_user` | `creatoros.action_logs` | `user_id, created_at DESC` | Audit queries |
| `idx_query_logs_user` | `creatoros.query_logs` | `user_id, created_at DESC` | Log queries |
| `idx_metrics_user_date` | `creatoros.productivity_metrics` | `user_id, metric_date` | Date-range analytics |
| `idx_reflections_user_date` | `creatoros.daily_reflections` | `user_id, reflection_date` | Date-range analytics |
| `idx_threads_user` | `creatoros.chat_threads` | `user_id, updated_at DESC` | Thread list by recency |

---

## DB Connection Configuration

| Setting | Value | Notes |
|---|---|---|
| Provider | Neon PostgreSQL (serverless) | — |
| Pool | HikariCP | — |
| `max-lifetime` | 300 000 ms (5 min) | Must be shorter than Neon's server-side idle timeout |
| `keepalive-time` | 60 000 ms | Keeps the connection alive on Neon |
| `connection-test-query` | `SELECT 1` | Validates connections before handing out |
| `minimum-idle` | 1 | — |
| `maximum-pool-size` | 5 | — |
| `idle-timeout` | 120 000 ms | — |
| Credentials location | `application-local.properties` (git-ignored) | `spring.datasource.url`, `username`, `password` |
| Schema init mode | `spring.sql.init.mode=never` (default) | Set to `always` to re-run `db/schema.sql` + `db/seed.sql` |

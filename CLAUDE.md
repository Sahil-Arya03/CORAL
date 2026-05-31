# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**Backend (Spring Boot — project root)**
```bash
mvn spring-boot:run          # start on :8080 (use system mvn; .\mvnw.cmd requires powershell on PATH)
mvn test                     # run all tests
mvn -Dtest=SqlValidationServiceTest test   # single test class
mvn -Dtest=PolicyEngineTest test
mvn -Dtest=OrchestrationServiceTest test
```

**Frontend (React + Vite — run from `frontend/`)**
```bash
npm run dev      # dev server on :5173 (port fixed in vite.config.js)
npm run build    # production build to frontend/dist/
npm run preview  # serve the production build locally
```

Vite proxies `/api/*` → `http://localhost:8080`, so CORS only matters if you bypass the proxy.
`WebConfig.java` allows origins `:5173`–`:3000`, methods `GET POST PUT PATCH DELETE OPTIONS`, headers `Content-Type Authorization`.

**Database re-seed** (Neon PostgreSQL — only when needed)
```
# In application.properties, temporarily set:
spring.sql.init.mode=always
# Reverts to: spring.sql.init.mode=never
# This drops and re-applies db/schema.sql + db/seed.sql
```

The seed now includes all five federated tables with `user_id = 'dev_user'` so the timeline works
out of the box in dev mode. Calendar and Gmail rows are deleted/re-inserted (not truncated) so real
synced data for other user IDs is preserved.

**Manual sync triggers** (useful during development)
```bash
# Per-user sync — all endpoints use the calling user's stored credentials
# (In dev mode with blank clerk.jwks-url, all requests are treated as "dev_user")
curl -X POST http://localhost:8080/api/sync/github      # syncs repos from user_integrations PAT
curl -X POST http://localhost:8080/api/sync/gmail       # uses user's stored Google token
curl -X POST http://localhost:8080/api/sync/calendar    # uses user's stored Google token
curl -X POST http://localhost:8080/api/sync/notion      # uses user's stored Notion token
curl http://localhost:8080/api/sync/status

# Explicit per-user sync (requires Authorization: Bearer <clerk-jwt>)
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/integrations/status
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/integrations/github/sync
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/integrations/google/sync
curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/integrations/notion/sync
```

**Schema reference** — see `schema.md` at the project root for full table definitions, indexes,
FK relationships, mutation policy rules, and cross-source logical join mappings.

---

## Architecture

### Prime Invariant
The AI produces *text only*. It never calls Coral/Postgres directly. Every side-effect between "AI said so" and "database did it" is deterministic Java. This is enforced structurally: `CoralClient` is **package-private** inside `coral/` — nothing outside that package can call it.

### Orchestration Pipeline (`OrchestrationService`)
A single `OrchestrationContext` object flows through these stages per chat request:

```
IntentExtractionService    (AI)  → IntentResult          (what does the user want?)
ActionClassificationService(det) → ActionClass           (READ | MUTATE | REFLECT | SMALLTALK)
SchemaContextService       (det) → Set<String>           (full catalog — AI picks relevant tables)
QueryPlanningService       (AI)  → CoralQueryPlan        (candidate SQL + named bindings)
SqlValidationService       (det) → ValidatedQuery        (AST validation, reject-by-default)
PolicyEngine               (det) → PolicyDecision        (ALLOW | DENY | CONFIRM)
CoralExecutionService      (det) → CoralResultSet        (reads scoped to user_id via JSqlParser)
ContextAggregationService  (det) → List<TimelineEvent>   (cross-source normalization)
MemoryInjectionService     (det) → String memoryBlock    (ranked user memory from Postgres)
AIReasoningService         (AI)  → streamed prose        (final answer)
LongTermMemoryService      (AI)  → (async) extracts and stores personal facts
```

`OrchestrationContext` carries both `long userId` (internal BIGINT, used for `creatoros.*` tables) and `String clerkUserId` (Clerk ID, used for federated table scoping and `user_integrations`).

Short-circuits:
- `SMALLTALK` → skips straight to `AIReasoningService.chat()`
- `MUTATE` → skips reads, hits `PolicyEngine`, may pause for a confirmation handshake (token stored in `PendingActionStore`, in-memory, 5-min TTL)

### Two Databases, One Connection
Both the source-federated data and app state live in the same Neon Postgres instance, separated by schema:

| Schema prefix | Purpose | Accessed by |
|---|---|---|
| `github.`, `notion.`, `calendar.`, `gmail.` | Federated source data — each row has a `user_id TEXT` (Clerk ID) scoping column | `CoralClient` only (for AI queries); `NamedParameterJdbcTemplate` directly (for sync adapters and timeline) |
| `creatoros.` | App state: `users`, `user_integrations`, `user_memory`, `ai_conversations`, `query_logs`, `action_logs`, `chat_threads` | `UserIntegrationRepository`, `MemoryInjectionService`, repository classes in `persistence/` |

No JPA/Hibernate — uses `JdbcTemplate` and `NamedParameterJdbcTemplate` directly.

### User ID model (hybrid)
- **`creatoros.*` tables** use `user_id BIGINT` → the internal auto-increment PK from `creatoros.users`
- **Federated tables** (`github.*`, `notion.*`, `calendar.*`, `gmail.*`) use `user_id TEXT` → the Clerk user ID directly
- **`creatoros.user_integrations`** uses `user_id TEXT` → Clerk ID
- `ClerkAuthFilter` resolves both IDs and sets them as request attributes; `SecurityUtils` reads them

### Timeline (`TimelineService`)
`TimelineService` builds the unified cross-source activity stream. It uses `NamedParameterJdbcTemplate`
**directly** — bypassing `SqlValidationService` and `CoralExecutionService` — because:

1. Timeline queries are trusted/internal SQL, not AI-generated, so they don't need the AI safety pipeline.
2. JSqlParser 4.9 treats `events` as a reserved keyword (MySQL `CREATE EVENT` / `SHOW EVENTS`
   grammar), which causes it to silently fail to parse `SELECT ... FROM calendar.events`, dropping
   calendar events from every timeline response.

The service iterates all catalog tables, generates `SELECT <cols> FROM <table> WHERE user_id = :uid LIMIT 200`,
runs it directly via JDBC, normalizes temporal types to ISO-8601 strings, and passes results to
`ContextAggregationService` for sorting and normalization.

**Do NOT route timeline queries back through `SqlValidationService`.** The JSqlParser keyword
conflict cannot be fixed by quoting alone because JSqlParser may drop quotes in serialization,
which would break the downstream `CoralExecutionService.injectUserScope()` call.

### SQL Validation (`SqlValidationService`)
Reject-by-default gate using JSqlParser (real AST, not regex). **Only used for AI-generated SQL** (chat pipeline). Checks in order:
1. Parse → exactly one statement (no `;` chaining)
2. Type → SELECT | UPDATE | DELETE only (DROP/ALTER/etc. are hard-rejected)
3. Tables → every table in `SchemaCatalog` AND in the intent's allowed set
4. Operation → table's `allowedOps` must include the statement type
5. Columns → every referenced column in the catalog; no `SELECT *`; `user_id` is in every federated table's column set
6. Mutation safety → UPDATE/DELETE must have a bounded WHERE referencing a real column (rejects tautologies like `WHERE 1=1`)
7. Mutable fields → UPDATE SET targets must be in the table's `mutableFields`
8. LIMIT → injected at `200` if absent on SELECT

After validation passes, `CoralExecutionService` injects `AND user_id = :_userId` into the WHERE clause via JSqlParser before execution. **The AI never scopes its own queries** — this is enforced in Java.

### Policy Engine (`PolicyEngine`)
A hardcoded whitelist of the five permitted mutation action types:

- `task.update_status`   — `notion.tasks`, UPDATE `status`, maxRows=1, no confirm
- `task.update_deadline` — `notion.tasks`, UPDATE `due_date`, maxRows=1, **confirm required**
- `task.update_priority` — `notion.tasks`, UPDATE `priority`, maxRows=1, no confirm
- `reminder.archive`     — `gmail.emails`, UPDATE `is_archived`, maxRows=10, no confirm
- `reminder.delete_dup`  — `gmail.emails`, DELETE, maxRows=5, **confirm required**

To add a new mutation capability, add a `Rule` here and a corresponding entry in `SchemaCatalog`.

### Schema Catalog (`SchemaCatalog`)
Single source of truth for all data the AI can reference. All five federated tables include `user_id` in their column sets. The full per-table column lists:

| Table | Columns |
|---|---|
| `github.commits` | `sha`, `repo`, `author`, `message`, `committed_at`, `user_id` |
| `github.pull_requests` | `id`, `repo`, `title`, `state`, `created_at`, `merged_at`, `user_id` |
| `notion.tasks` | `id`, `title`, `status`, `priority`, `due_date`, `project`, `updated_at`, `user_id` |
| `calendar.events` | `id`, `title`, `start_at`, `end_at`, `attendees_count`, `is_meeting`, `description`, `location`, `user_id` |
| `gmail.emails` | `id`, `subject`, `sender`, `snippet`, `is_unread`, `received_at`, `importance`, `is_archived`, `user_id` |

`calendar.events` also has a `google_event_id` column (added by `StartupMigrationRunner`) used for
write-back from `CalendarController`, but it is NOT in `SchemaCatalog` because the AI should never
reference it.

### AI Layer
Three AI calls max per request (intent, planning, reasoning). All go through `AiGateway`, which wraps the Spring AI `ChatModel` and returns `Optional.empty()` on any failure. The model is Groq (`llama-3.3-70b-versatile`) via OpenAI-compatible API. All prompt assembly is centralized in `PromptBuilderService`.

A fourth optional async call (`LongTermMemoryService`) extracts personal facts after each READ/REFLECT exchange. It never blocks the response.

---

## Authentication (`ClerkAuthFilter`)

Every `/api/**` request (except `OPTIONS` and `/api/users/sync`) is verified by `ClerkAuthFilter extends OncePerRequestFilter`:

1. Reads `Authorization: Bearer <jwt>` header — returns 401 if missing
2. Verifies the JWT signature via Clerk's JWKS endpoint (`clerk.jwks-url` from properties), with a cached `JwkProvider` (10 keys, 24h TTL)
3. Extracts the `sub` claim (Clerk user ID, e.g. `user_xxxxxxxxxx`)
4. Looks up or auto-creates a `creatoros.users` row for that Clerk ID — sets both `clerkUserId` (String) and `internalUserId` (Long) as request attributes
5. Returns 401 on any verification failure

**When `clerk.jwks-url` is blank** (dev / offline mode): filter injects synthetic attributes `clerkUserId = "dev_user"` and `internalUserId = 1L` so every endpoint stays reachable without Clerk configured. Never deploy with a blank JWKS URL.

`SecurityUtils` provides `getClerkUserId(request)` and `getInternalUserId(request)` — both throw `ResponseStatusException(401)` if the attribute is absent.

**Required in `application.properties`:**
```properties
clerk.jwks-url=https://<your-clerk-domain>/.well-known/jwks.json
```

---

## Integration Sync Layer

Per-user integrations: each user connects their own accounts; credentials are stored in `creatoros.user_integrations`. All three providers (GitHub, Google, Notion) now have full per-user sync.

### `creatoros.user_integrations` table

| Column | Type | Notes |
|---|---|---|
| `user_id` | TEXT | Clerk user ID — NOT the BIGINT internal ID |
| `provider` | TEXT | `github` \| `google` \| `notion` |
| `access_token` | TEXT | OAuth access token (Google/Notion), or PAT (GitHub) |
| `refresh_token` | TEXT | Long-lived refresh token (Google only) or null |
| `token_expiry` | TIMESTAMPTZ | When `access_token` expires |
| `extra` | JSONB | Provider-specific — see below |
| `connected_at` | TIMESTAMPTZ | — |
| `last_synced_at` | TIMESTAMPTZ | Updated after each successful sync |

Extra JSONB per provider:
- **GitHub**: `{"repos": ["owner/repo", ...]}`
- **Google**: `{}`
- **Notion**: `{"database_id": "<uuid>", "workspace_id": "<uuid>", "workspace_name": "My Workspace"}`

Unique constraint on `(user_id, provider)`.

### Sync pattern — per-user

Each adapter has `syncForUser(String clerkUserId)`:
1. Reads credentials from `UserIntegrationRepository`
2. Gets an access token (refresh via `GoogleOAuthClient.getUserAccessToken(refreshToken)` for Google; direct use of stored token for GitHub PAT and Notion)
3. Fetches data from the external API
4. Writes rows scoped with `user_id = clerkUserId`
5. Calls `integrationRepo.updateLastSynced(uid, provider)` on success

`SyncScheduler` scheduled jobs sweep all connected users via `integrationRepo.findUsersByProvider(provider)`.

### Sync schedules (fixed-delay, starts after 10s startup delay)

| Integration | Interval config key | Default |
|---|---|---|
| GitHub | `coral.github.sync-interval-ms` | 30 min |
| Gmail | `coral.google.gmail.sync-interval-ms` | 15 min |
| Google Calendar | `coral.google.calendar.sync-interval-ms` | 30 min |
| Notion | `coral.notion.sync-interval-ms` | 10 min |

### GitHub sync

GitHub uses a **Personal Access Token (PAT)** stored in `user_integrations.access_token`. The repos list is in `extra.repos[]`.

`GitHubSyncAdapter.syncForUser(clerkUserId)`:
1. Reads PAT + repos from `user_integrations`
2. Calls `GitHubApiClient.fetchCommitsWithToken(...)` and `fetchPullsWithToken(...)` per repo
3. Upserts commits/PRs with `user_id = clerkUserId`
4. State key: `github:{ownerRepo}:{clerkUserId}` per repo

The legacy global sync (`GitHubSyncAdapter.syncRepo(ownerRepo)`) uses the PAT from `coral.github.token` in `application.properties` and stores rows with `user_id = ''`. It runs alongside the per-user sweep only when `GitHubProperties.isConfigured()` is true. Prefer the per-user flow.

Connecting GitHub triggers an immediate `syncForUser` in a background thread.

### Google OAuth flow (frontend-driven)

1. Frontend calls `GET /api/integrations/google/auth-url` → gets Google consent URL
2. Frontend redirects user to Google → user approves
3. Google redirects back to frontend at `coral.google.redirect-uri` (e.g. `http://localhost:5173/integrations`) with `?code=...`
4. Frontend detects `?code=...` in `window.location.search`, POSTs it to `POST /api/integrations/google/callback` with Clerk JWT
5. Backend exchanges code for tokens, saves to `user_integrations`, triggers initial Gmail + Calendar sync

**Required in `application.properties`:**
```properties
coral.google.client-id=<OAuth 2.0 client ID from Google Cloud Console>
coral.google.client-secret=<client secret>
# coral.google.redirect-uri defaults to http://localhost:5173/integrations
# Must also be registered as an Authorized Redirect URI in Google Cloud Console
```

**`coral.google.refresh-token` is NOT required** for the per-user OAuth flow — leave it blank. It is only needed for the legacy global scheduled sync (one shared Google account).

### `GoogleProperties` — two readiness checks

| Method | Requires | Used by |
|---|---|---|
| `isConfigured()` | `clientId` + `clientSecret` + `refreshToken` | Legacy global sync (`GmailSyncAdapter.sync()`, `CalendarSyncAdapter.sync()`) |
| `canOAuth()` | `clientId` + `clientSecret` only | Per-user OAuth flow — `googleAuthUrl`, `exchangeCode`, `getUserAccessToken` |

### `GoogleOAuthClient` — shared + per-user methods

| Method | Guard | Use |
|---|---|---|
| `getAccessToken()` | `isConfigured()` | Cached shared token (global scheduled sync) |
| `getUserAccessToken(refreshToken)` | `canOAuth()` | Per-user token — uses global client credentials + user's own refresh token |
| `exchangeCode(code, redirectUri)` | `canOAuth()` | One-time OAuth code → tokens exchange (called by `/api/integrations/google/callback`) |

### Sync strategies

| Adapter | Strategy | State key pattern |
|---|---|---|
| `GitHubSyncAdapter` | Incremental — commits/PRs since `last_synced_at`; `ON CONFLICT DO UPDATE` (PRs) / DO NOTHING (commits) | `github:{ownerRepo}:{clerkUserId}` |
| `GmailSyncAdapter` | Incremental — messages after `last_synced_at`; `ON CONFLICT DO UPDATE` | `gmail:{clerkUserId}` |
| `CalendarSyncAdapter` | Rolling window replace — DELETE then INSERT in −7/+60 day window per user | `calendar:{clerkUserId}` |
| `NotionSyncAdapter` | Incremental — pages edited after `last_synced_at`; normalises status/priority | `notion:{clerkUserId}` |

### Notion OAuth flow (frontend-driven)

Notion uses a proper OAuth 2.0 flow identical in structure to the Google flow.

1. Frontend calls `GET /api/integrations/notion/auth-url` → gets Notion consent URL
2. Frontend redirects user to Notion → user approves the OAuth app
3. Notion redirects back to `coral.notion.redirect-uri` (default `http://localhost:5173/integrations`) with `?code=...&state=notion`
4. Frontend detects `?code=...&state=notion` on mount — stores the code in `pendingNotionCode` state and strips the URL params
5. User is shown an inline form to paste their Tasks **database ID** (Notion doesn't provide it in the OAuth response; the user must supply it)
6. Frontend POSTs `{ code, databaseId }` to `POST /api/integrations/notion/callback`
7. Backend (`NotionOAuthClient.exchangeCode`) calls `https://api.notion.com/v1/oauth/token` with **HTTP Basic auth** (Base64 `clientId:clientSecret`), receives `access_token + workspace_id + workspace_name`
8. Saves `access_token`, `extra = {"database_id", "workspace_id", "workspace_name"}` to `user_integrations`; triggers initial `notionSync.syncForUser(uid)` in background

**`state=notion` is how the frontend distinguishes Notion's `?code=` from Google's `?code=`** — both providers redirect to the same `/integrations` URL. Google's callback has no `state` param.

**Required in `application.properties`:**
```properties
coral.notion.client-id=<OAuth client ID from notion.so/my-integrations>
coral.notion.client-secret=<OAuth client secret>
# coral.notion.redirect-uri defaults to http://localhost:5173/integrations
# Must be registered as a redirect URI in your Notion integration's OAuth settings
```

**`NotionProperties`** has `canOAuth()` (requires `clientId` + `clientSecret`) and `isConfigured()` (requires legacy `token` + `databaseId` for optional global sync). `GET /api/integrations/notion/auth-url` returns 400 if `canOAuth()` is false — the frontend surfaces this as an error message on the Connect button.

**`NotionOAuthClient`** — mirrors `GoogleOAuthClient`:
- `getAuthorizationUrl(redirectUri)` — builds consent URL with `state=notion` appended
- `exchangeCode(code, redirectUri)` — HTTP Basic auth POST to Notion token endpoint (unlike Google's form-encoded body, Notion requires JSON body)

**`NotionSyncAdapter.syncForUser()`** reads `access_token` from `user_integrations` directly — the Notion OAuth access token is used identically to an integration token for API calls. Guards against blank token or `database_id` with a logged warning.

**`normalizeNotionDatabaseId()`** in `IntegrationController` — called on the user-supplied database ID before saving. Extracts the UUID from plain UUIDs, UUIDs with hyphens, or full Notion URLs (`https://notion.so/workspace/Title-abc123...`).

**Legacy manual connect** — `POST /api/integrations/notion` (direct token entry) is still wired on the backend for API compatibility but no longer exposed in the frontend UI.

### Notion write-back (not yet implemented)
AI mutations to `notion.tasks` write to the local DB only and are overwritten on the next sync from Notion.

### Calendar write-back (`CalendarController`)
`POST /api/calendar/events`, `PATCH /api/calendar/events/{id}`, `DELETE /api/calendar/events/{id}` — write directly to Google Calendar API then re-sync the rolling window. The local DB ID stored by `CalendarSyncAdapter` is `{googleEventId}:{clerkUserId}`; `CalendarController` resolves the raw Google event ID via `google_event_id` column before calling the API.

---

## Frontend

### Stack
- **React 18** + **react-router-dom v7** (not hash routing — real URL paths)
- **Vite 6** — dev server fixed on `:5173`, proxies `/api/*` to `:8080`
- **Clerk** — `useAuth()` for JWT tokens, `useUser()` for profile data

### Routing

| Path | Component | Notes |
|---|---|---|
| `/` | → redirect to `/dashboard` | |
| `/dashboard` | `DashboardPage` | Static fixture data |
| `/chat` | `ChatPage` | SSE stream, thread management |
| `/timeline` | `TimelinePage` | Fetches live data via `GET /api/timeline`; week/day/month grid view |
| `/analytics` | `AnalyticsPage` | Static fixture data |
| `/focus` | `FocusPage` | Auto-collapses sidebar, hides Topbar |
| `/integrations` | `IntegrationsPage` | Per-user connect/disconnect UI; handles `?code=&state=notion` (Notion) and `?code=` (Google) OAuth callbacks |
| `*` | → redirect to `/dashboard` | |

### Key Behaviors
- **Sidebar collapse state** persisted to `localStorage` as `coros.sidebar`
- **Focus mode** (`/focus`) auto-collapses sidebar on entry and restores on exit; `Topbar` is hidden
- **Session ID** persisted to `localStorage` as `coral.sessionId` — passed on every chat request
- **OAuth callbacks** — `IntegrationsPage` detects both providers on mount via `?code=` + `?state=`:
  - `state=notion` → Notion callback: store code in `pendingNotionCode`, show database ID form, submit via `submitNotionCode`
  - no `state` → Google callback: auto-submit code via `submitGoogleCode`
  - Both strip the query params from the URL after handling
- **Calendar events** — `TimelinePage` supports create/edit/delete via `EventModal`, which calls `CalendarController` endpoints. All-day events (midnight UTC) are detected by `isAllDay()` and placed in the all-day strip; timed events are placed by local hour in the 07:00–21:00 grid.

### API Client (`src/api.js`)
All backend calls are in one module. Never call `fetch` directly from page components.

**Chat & core**

| Function | Endpoint | Notes |
|---|---|---|
| `streamChat(...)` | `POST /api/chat` | SSE over fetch ReadableStream |
| `confirmAction(token)` | `POST /api/actions/confirm` | Returns `ActionResult` |
| `fetchTimeline()` | `GET /api/timeline` | Returns `TimelineEvent[]` |
| `fetchThreads()` | `GET /api/threads` | Returns `ThreadDto[]` |
| `createThread(title)` | `POST /api/threads` | Throws `{ code: 'max_threads' }` on 409 |
| `deleteThread(id)` | `DELETE /api/threads/:id` | — |
| `renameThread(id, title)` | `PATCH /api/threads/:id/title` | — |
| `fetchMessages(threadId)` | `GET /api/threads/:id/messages` | — |
| `fetchMemories()` | `GET /api/memory` | — |
| `deleteMemory(id)` | `DELETE /api/memory/:id` | — |

**Calendar CRUD**

| Function | Endpoint | Notes |
|---|---|---|
| `createCalendarEvent(event, token)` | `POST /api/calendar/events` | Writes to Google then re-syncs |
| `updateCalendarEvent(id, event, token)` | `PATCH /api/calendar/events/:id` | Writes to Google then re-syncs |
| `deleteCalendarEvent(id, token)` | `DELETE /api/calendar/events/:id` | Deletes from Google then re-syncs |

**Per-user integrations** (all require `token` = Clerk JWT from `useAuth().getToken()`)

| Function | Endpoint | Notes |
|---|---|---|
| `fetchIntegrations(token)` | `GET /api/integrations` | Returns `IntegrationDto[]` |
| `fetchIntegrationStatus(token)` | `GET /api/integrations/status` | `{ github, google, notion }` each with `connected`, `lastSyncedAt`; notion also has `workspaceName` |
| `fetchGoogleAuthUrl(token)` | `GET /api/integrations/google/auth-url` | Returns `{ url }` for redirect |
| `submitGoogleCode(code, token)` | `POST /api/integrations/google/callback` | Exchanges Google OAuth code for tokens |
| `fetchNotionAuthUrl(token)` | `GET /api/integrations/notion/auth-url` | Returns `{ url }` for redirect; surfaces server error message on 400 |
| `submitNotionCode(code, databaseId, token)` | `POST /api/integrations/notion/callback` | Exchanges Notion OAuth code + database ID for tokens |
| `connectGitHub(pat, repos, token)` | `POST /api/integrations/github` | Saves PAT + repos list; triggers immediate sync |
| `disconnectIntegration(provider, token)` | `DELETE /api/integrations/:provider` | Removes credentials |
| `syncIntegration(provider, token)` | `POST /api/integrations/:provider/sync` | Manual per-user sync trigger (github, google, notion) |

**Sync shortcuts** (dev convenience)

| Function | Endpoint |
|---|---|
| `fetchSyncStatus()` | `GET /api/sync/status` |
| `triggerSync(source)` | `POST /api/sync/:source` (gmail \| calendar \| notion \| github) |

### Components
| File | Exports | Role |
|---|---|---|
| `Sidebar.jsx` | `Sidebar`, `CollapseToggle` | Nav with `NavLink`s |
| `Topbar.jsx` | `Topbar` | Top bar (hidden in Focus mode) |
| `icons.jsx` | `I` | SVG icon map |
| `atoms.jsx` | Various | Reusable UI primitives |
| `doodles.jsx` | Various | Decorative SVG doodles |

### CSS utilities added
- `.form-input` — styled text/password input for connect forms (border, padding, border-radius, focus ring matching `--coral`)

---

## Key Constraints

- **Multi-user**: each Clerk-authenticated user gets their own isolated data. `ClerkAuthFilter` is the auth gate; `CoralExecutionService` injects `user_id` scoping on every AI-generated federated query; sync adapters write with `user_id = clerkUserId`; `TimelineService` queries with `WHERE user_id = :uid` directly via JDBC.
- **Dual user ID**: internal `BIGINT userId` for `creatoros.*` tables; Clerk ID `String clerkUserId` for `user_integrations` and federated table `user_id` columns. Both are set as request attributes by `ClerkAuthFilter`.
- **Dev mode**: when `clerk.jwks-url` is blank, `ClerkAuthFilter` injects `clerkUserId = "dev_user"` and `internalUserId = 1L` — all endpoints work without Clerk; Google Connect still works as long as `coral.google.client-id` + `coral.google.client-secret` are set. Dev seed data uses `user_id = 'dev_user'` so the timeline shows fixture rows immediately.
- **JSqlParser keyword limitation**: `events` is a reserved word in JSqlParser's MySQL grammar. Never route queries against `calendar.events` through `SqlValidationService` — use JDBC directly. `TimelineService` already does this. For AI-chat SQL, `calendar.events` is safe because JSqlParser only fails on the table name in a `FROM` clause when it appears unquoted; the chat pipeline never touches timeline-style full-table reads.
- **`PendingActionStore` is in-memory** — pending confirmations are lost on restart
- **Thread limit**: 5 threads per user (enforced in `ThreadRepository.MAX_THREADS`)
- **`CoralClient` is package-private** — all external callers go through `CoralExecutionService` (reads) or `ActionExecutionService` (mutations)
- **`application.properties` is git-ignored** — contains Neon credentials, Google OAuth credentials, Groq API key, Clerk JWKS URL, and `spring.sql.init.mode`. Copy `application.properties.example` and fill in real values.
- **Notion sync is one-way** — AI mutations to `notion.tasks` write to the local DB only; they do not propagate back to Notion. Calendar write-back IS implemented via `CalendarController`.
- **Notion OAuth requires `coral.notion.client-id` + `coral.notion.client-secret`** — if either is blank, `GET /api/integrations/notion/auth-url` returns 400 and the Connect button shows the server error. Create a public integration at `notion.so/my-integrations`, enable the Authorization capability, and register `http://localhost:5173/integrations` as a redirect URI.
- **All migrations are idempotent** and applied by `StartupMigrationRunner` on every startup — no manual migration steps needed. Key migrations: `user_id` column on all federated tables (`DEFAULT ''`), `description`/`location`/`google_event_id` on `calendar.events`, `creatoros.user_integrations` table, purge of seed rows with `user_id = ''` for calendar and gmail.

---

## File Layout

```
src/main/java/org/example/coral/
├── ai/                    # AI stages: gateway, intent, planning, reasoning, prompts
├── analytics/             # ContextAggregationService, TimelineService (direct JDBC, no JSqlParser)
├── config/                # WebConfig (CORS), StartupMigrationRunner,
│                          #   ClerkAuthFilter, SecurityUtils
├── controller/            # ChatController, ThreadController, TimelineController,
│                          #   MemoryController, SyncController, UserController,
│                          #   IntegrationController, CalendarController
├── coral/                 # CoralClient (pkg-private), CoralExecutionService (user scoping),
│                          #   ActionExecutionService, SchemaCatalog, TableSpec
├── dto/                   # ChatDtos (all request/response records)
├── memory/                # MemoryInjectionService, LongTermMemoryService
├── model/                 # OrchestrationContext (userId + clerkUserId), IntentResult,
│                          #   CoralQueryPlan, ValidatedQuery, PolicyDecision, TimelineEvent
├── persistence/           # ConversationRepository, ThreadRepository,
│                          #   ActionLogRepository, QueryLogRepository,
│                          #   UserRepository, UserIntegrationRepository
├── security/              # SqlValidationService, PolicyEngine, ValidationException
├── service/               # OrchestrationService, ActionClassificationService,
│                          #   SchemaContextService, PendingActionStore, ChatCleanupScheduler
├── sync/                  # GitHubApiClient, GitHubSyncAdapter (per-user + global), GitHubProperties,
│                          #   GoogleProperties, GoogleOAuthClient,
│                          #   GmailApiClient, GmailSyncAdapter,
│                          #   CalendarApiClient, CalendarSyncAdapter,
│                          #   NotionProperties, NotionOAuthClient, NotionApiClient, NotionSyncAdapter,
│                          #   SyncScheduler, SyncStateRepository
└── util/                  # Json (LLM-output JSON parser)

frontend/src/
├── api.js                 # All backend fetch calls (chat, threads, memory, integrations, sync, calendar CRUD)
├── App.jsx                # BrowserRouter shell, route table, sidebar collapse logic
├── main.jsx               # React root mount
├── styles.css             # Global styles
├── components/            # Sidebar, Topbar, icons, atoms, doodles
└── pages/                 # Dashboard, Chat, Timeline (week/day/month grid + EventModal),
                           #   Analytics, Focus, Integrations (connect/disconnect + OAuth handler)

db/
├── schema.sql             # Full DDL for all schemas + tables + indexes (base schema, pre-migrations)
├── seed.sql               # Demo data — all rows scoped to user_id='dev_user'; calendar/gmail
│                          #   use DELETE+INSERT (not TRUNCATE) to preserve real synced data
└── migrations/
    └── add_chat_threads.sql

schema.md                  # Complete DB reference
```
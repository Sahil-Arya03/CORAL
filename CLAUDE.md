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
The CORS allowlist in `WebConfig.java` covers `:5173`, `:5174`, `:5175`, and `:3000`.

**Database re-seed** (Neon PostgreSQL — only when needed)
```
# In application-local.properties, temporarily set:
spring.sql.init.mode=always
# Reverts to: spring.sql.init.mode=never
# This drops and re-applies db/schema.sql + db/seed.sql
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
CoralExecutionService      (det) → CoralResultSet        (reads only, via CoralClient)
ContextAggregationService  (det) → List<TimelineEvent>   (cross-source normalization)
MemoryInjectionService     (det) → String memoryBlock    (ranked user memory from Postgres)
AIReasoningService         (AI)  → streamed prose        (final answer)
LongTermMemoryService      (AI)  → (async) extracts and stores personal facts
```

Short-circuits:
- `SMALLTALK` → skips straight to `AIReasoningService.chat()`
- `MUTATE` → skips reads, hits `PolicyEngine`, may pause for a confirmation handshake (token stored in `PendingActionStore`, in-memory, 5-min TTL)

### Two Databases, One Connection
Both the source-federated data and app state live in the same Neon Postgres instance, separated by schema:

| Schema prefix | Purpose | Accessed by |
|---|---|---|
| `github.`, `notion.`, `calendar.`, `gmail.` | Federated source data (read by Coral queries) | `CoralClient` only |
| `creatoros.` | App state: `user_memory`, `ai_conversations`, `query_logs`, `action_logs`, `chat_threads` | `MemoryInjectionService`, repository classes in `persistence/` |

No JPA/Hibernate — uses `JdbcTemplate` and `NamedParameterJdbcTemplate` directly.

### SQL Validation (`SqlValidationService`)
Reject-by-default gate using JSqlParser (real AST, not regex). Checks in order:
1. Parse → exactly one statement (no `;` chaining)
2. Type → SELECT | UPDATE | DELETE only (DROP/ALTER/etc. are hard-rejected)
3. Tables → every table in `SchemaCatalog` AND in the intent's allowed set
4. Operation → table's `allowedOps` must include the statement type
5. Columns → every referenced column in the catalog; no `SELECT *`
6. Mutation safety → UPDATE/DELETE must have a bounded WHERE referencing a real column (rejects tautologies like `WHERE 1=1`)
7. Mutable fields → UPDATE SET targets must be in the table's `mutableFields`
8. LIMIT → injected at `200` if absent on SELECT

`CoralClient.executeRead()` does partial WHERE pruning at runtime: if the AI-planned bindings are missing some named parameters, it drops only the unresolvable AND branches and runs what remains. OR branches containing any missing param are dropped entirely to avoid widening the result set.

### Policy Engine (`PolicyEngine`)
A hardcoded whitelist of the five permitted mutation action types. For each action, it declares the target table, statement type, allowed fields, max affected rows, and whether confirmation is needed:

- `task.update_status`   — `notion.tasks`, UPDATE `status`, maxRows=1, no confirm
- `task.update_deadline` — `notion.tasks`, UPDATE `due_date`, maxRows=1, **confirm required**
- `task.update_priority` — `notion.tasks`, UPDATE `priority`, maxRows=1, no confirm
- `reminder.archive`     — `gmail.emails`, UPDATE `is_archived`, maxRows=10, no confirm
- `reminder.delete_dup`  — `gmail.emails`, DELETE, maxRows=5, **confirm required**

To add a new mutation capability, add a `Rule` here and a corresponding entry in `SchemaCatalog`.

### Schema Catalog (`SchemaCatalog`)
Single source of truth for all data the AI can reference. Adding a new table or column requires updating this file — the AI only learns a field exists because it appears here, and validation rejects anything outside the catalog. `SchemaContextService` always exposes the full catalog to the planner; source selection is the AI's responsibility.

### AI Layer
Three AI calls max per request (intent, planning, reasoning). All go through `AiGateway`, which wraps the Spring AI `ChatModel` and returns `Optional.empty()` on any failure — every AI stage has a deterministic fallback so the app works offline/without an API key. The model is Groq (`llama-3.3-70b-versatile`) via OpenAI-compatible API. All prompt assembly is centralized in `PromptBuilderService`.

A fourth optional async call runs after each READ/REFLECT exchange (`LongTermMemoryService`) to extract and persist personal facts. It never blocks the response.

---

## Frontend

### Stack
- **React 18** + **react-router-dom v7** (not hash routing — real URL paths)
- **Vite 6** — dev server fixed on `:5173`, proxies `/api/*` to `:8080`
- **Playwright** installed (`@playwright/test`) for E2E tests

### Routing
Navigation uses `BrowserRouter` + `Routes` + `Route` (not hash `#page` anchors — the old approach is gone).

| Path | Component | Notes |
|---|---|---|
| `/` | → redirect to `/dashboard` | |
| `/dashboard` | `DashboardPage` | Static fixture data |
| `/chat` | `ChatPage` | SSE stream, thread management |
| `/timeline` | `TimelinePage` | Fetches live data on mount via `GET /api/timeline` |
| `/analytics` | `AnalyticsPage` | Static fixture data |
| `/focus` | `FocusPage` | Auto-collapses sidebar, hides Topbar |
| `/integrations` | `IntegrationsPage` | Static fixture data |
| `*` | → redirect to `/dashboard` | |

Sidebar uses `NavLink` from react-router-dom for active-state highlighting.

### Key Behaviors
- **Sidebar collapse state** persisted to `localStorage` as `coros.sidebar`
- **Focus mode** (`/focus`) auto-collapses the sidebar on entry and restores state on exit; `Topbar` is hidden
- **Session ID** persisted to `localStorage` as `coral.sessionId` — created once per browser, passed on every chat request so the backend scopes memory and conversation history correctly

### API Client (`src/api.js`)
All backend calls are in one module. Never call `fetch` directly from page components — go through `api.js`.

| Function | Endpoint | Notes |
|---|---|---|
| `streamChat({ prompt, sessionId, onStatus, onToken, onFinal, signal })` | `POST /api/chat` | Manually parses SSE framing off `fetch` `ReadableStream`; EventSource is GET-only |
| `confirmAction(token)` | `POST /api/actions/confirm` | Returns `ActionResult { executed, message, rowsAffected }` |
| `fetchTimeline()` | `GET /api/timeline` | Returns `TimelineEvent[]` |
| `fetchThreads()` | `GET /api/threads` | Returns `ThreadDto[]` sorted by recency |
| `createThread(title)` | `POST /api/threads` | Throws `{ code: 'max_threads' }` on 409 (5-thread limit) |
| `deleteThread(id)` | `DELETE /api/threads/:id` | Also deletes all messages in the thread |
| `renameThread(id, title)` | `PATCH /api/threads/:id/title` | — |
| `fetchMessages(threadId)` | `GET /api/threads/:id/messages` | Returns `MessageDto[]` for history display |
| `fetchMemories()` | `GET /api/memory` | Returns `user_memory` rows |
| `deleteMemory(id)` | `DELETE /api/memory/:id` | — |

### Components
| File | Exports | Role |
|---|---|---|
| `Sidebar.jsx` | `Sidebar`, `CollapseToggle` | Nav with `NavLink`s; collapse toggle button |
| `Topbar.jsx` | `Topbar` | Top bar (hidden in Focus mode) |
| `icons.jsx` | `I` (icon map) | SVG icon components |
| `atoms.jsx` | Various | Reusable UI primitives |
| `doodles.jsx` | Various | Decorative SVG doodles |

---

## Key Constraints

- **Single hardcoded user**: `DEMO_USER_ID = 1L` in `OrchestrationService`, `ThreadController`, `MemoryController` — inserted on every startup by `StartupMigrationRunner`
- **`PendingActionStore` is in-memory** — pending confirmations are lost on restart
- **Thread limit**: 5 threads per user (enforced in `ThreadRepository.MAX_THREADS`)
- **DB schema lives in `creatoros.*`**; source data in `github.*`, `notion.*`, `calendar.*`, `gmail.*`
- **`application-local.properties` is git-ignored** — contains Neon credentials (`spring.datasource.*`) and `spring.sql.init.mode`
- **`CoralClient` is package-private** — it cannot be imported outside the `coral` package; all external callers go through `CoralExecutionService` (reads) or `ActionExecutionService` (mutations)
- **GitHub sync runs on a 30-minute fixed-delay schedule** (`SyncScheduler`); can be triggered manually via `POST /api/sync/github`; only runs if `coral.github.token` and `coral.github.repos` are set

---

## File Layout

```
src/main/java/org/example/coral/
├── ai/                    # AI stages: gateway, intent, planning, reasoning, prompts
├── analytics/             # ContextAggregationService, TimelineService
├── config/                # WebConfig (CORS), StartupMigrationRunner
├── controller/            # ChatController, ThreadController, TimelineController,
│                          #   MemoryController, SyncController
├── coral/                 # CoralClient (pkg-private), CoralExecutionService,
│                          #   ActionExecutionService, SchemaCatalog, TableSpec
├── dto/                   # ChatDtos (all request/response records)
├── memory/                # MemoryInjectionService, LongTermMemoryService
├── model/                 # OrchestrationContext, IntentResult, CoralQueryPlan,
│                          #   ValidatedQuery, PolicyDecision, TimelineEvent, etc.
├── persistence/           # ConversationRepository, ThreadRepository,
│                          #   ActionLogRepository, QueryLogRepository
├── security/              # SqlValidationService, PolicyEngine, ValidationException
├── service/               # OrchestrationService, ActionClassificationService,
│                          #   SchemaContextService, PendingActionStore,
│                          #   ChatCleanupScheduler
├── sync/                  # GitHubApiClient, GitHubSyncAdapter, SyncScheduler,
│                          #   GitHubProperties, SyncStateRepository
└── util/                  # Json (LLM-output JSON parser)

frontend/src/
├── api.js                 # All backend fetch calls
├── App.jsx                # BrowserRouter shell, route table, sidebar collapse logic
├── main.jsx               # React root mount, BrowserRouter provider
├── styles.css             # Global styles
├── components/            # Sidebar, Topbar, icons, atoms, doodles
└── pages/                 # Dashboard, Chat, Timeline, Analytics, Focus, Integrations

db/
├── schema.sql             # Full DDL for all schemas + tables + indexes
├── seed.sql               # Demo data (truncates + re-inserts)
└── migrations/
    └── add_chat_threads.sql   # Idempotent — also applied by StartupMigrationRunner

schema.md                  # Complete DB reference (tables, indexes, joins, policy rules)
```
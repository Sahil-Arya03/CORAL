/* Client for the Coral Spring Boot backend (/api/*). In dev, Vite proxies
   /api to http://localhost:8080 (see vite.config.js). */

/** Stable per-browser session id the backend uses to scope memory/context. */
export function getSessionId() {
  let id = localStorage.getItem('coral.sessionId');
  if (!id) {
    id = (crypto.randomUUID && crypto.randomUUID()) ||
      '00000000-0000-4000-8000-000000000000';
    localStorage.setItem('coral.sessionId', id);
  }
  return id;
}

/**
 * POST /api/chat — Server-Sent Events over a streamed POST response.
 * EventSource only supports GET, so we parse the SSE framing off fetch's
 * ReadableStream ourselves. Frames look like:  event:<name>\n data:<payload>\n\n
 *
 * Calls the provided handlers as frames arrive:
 *  - onStatus(obj)   for the initial {stage:"thinking"} frame
 *  - onToken(str)    for each streamed token (word + trailing space)
 *  - onFinal(obj)    for the final ChatResponse JSON
 */
export async function streamChat({ prompt, sessionId, onStatus, onToken, onFinal, signal }) {
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ prompt, sessionId }),
    signal,
  });
  if (!res.ok || !res.body) {
    throw new Error(`chat failed: ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const dispatch = (raw) => {
    let event = 'message';
    const dataLines = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5));
    }
    const data = dataLines.join('\n');
    if (event === 'status') {
      try { onStatus && onStatus(JSON.parse(data)); } catch { /* ignore */ }
    } else if (event === 'token') {
      onToken && onToken(data);
    } else if (event === 'final') {
      try { onFinal && onFinal(JSON.parse(data)); } catch { /* ignore */ }
    }
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      if (frame.trim()) dispatch(frame);
    }
  }
  if (buffer.trim()) dispatch(buffer);
}

/** POST /api/actions/confirm — execute a pending mutation by token. */
export async function confirmAction(token) {
  const res = await fetch('/api/actions/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token }),
  });
  if (!res.ok) throw new Error(`confirm failed: ${res.status}`);
  return res.json(); // ActionResult { executed, message, rowsAffected }
}

/** GET /api/timeline — unified cross-source activity stream, scoped to current user. */
export async function fetchTimeline(token) {
  const res = await fetch('/api/timeline', {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok) throw new Error(`timeline failed: ${res.status}`);
  return res.json(); // TimelineEvent[]
}

// ── Thread management ────────────────────────────────────────────────────────

export async function fetchThreads() {
  const res = await fetch('/api/threads');
  if (!res.ok) throw new Error(`threads failed: ${res.status}`);
  return res.json(); // ThreadDto[]
}

export async function createThread(title = 'New Chat') {
  const res = await fetch('/api/threads', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
  if (res.status === 409) throw Object.assign(new Error('max_threads'), { code: 'max_threads' });
  if (!res.ok) throw new Error(`create thread failed: ${res.status}`);
  return res.json(); // ThreadDto
}

export async function deleteThread(id) {
  const res = await fetch(`/api/threads/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`delete thread failed: ${res.status}`);
}

export async function renameThread(id, title) {
  await fetch(`/api/threads/${id}/title`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
}

export async function fetchMessages(threadId) {
  const res = await fetch(`/api/threads/${threadId}/messages`);
  if (!res.ok) throw new Error(`messages failed: ${res.status}`);
  return res.json(); // MessageDto[]  { role, content, createdAt }
}

// ── Long-term memory ─────────────────────────────────────────────────────────

export async function fetchMemories() {
  const res = await fetch('/api/memory');
  if (!res.ok) throw new Error(`memory failed: ${res.status}`);
  return res.json();
}

export async function deleteMemory(id) {
  const res = await fetch(`/api/memory/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`delete memory failed: ${res.status}`);
}

// ── Calendar event CRUD (write-back to Google Calendar) ─────────────────────

async function calendarFetch(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try {
      const body = await res.json();
      if (body?.error) msg = body.error;
    } catch { /* ignore parse error */ }
    const err = Object.assign(new Error(msg), { status: res.status });
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

export async function createCalendarEvent(event, token) {
  return calendarFetch('/api/calendar/events', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(event),
  });
}

export async function updateCalendarEvent(id, event, token) {
  return calendarFetch(`/api/calendar/events/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(event),
  });
}

export async function deleteCalendarEvent(id, token) {
  return calendarFetch(`/api/calendar/events/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

// ── Integration sync (legacy global) ────────────────────────────────────────

/** GET /api/sync/status — last_synced_at per integration (global). */
export async function fetchSyncStatus() {
  const res = await fetch('/api/sync/status');
  if (!res.ok) throw new Error(`sync status failed: ${res.status}`);
  return res.json();
}

/** POST /api/sync/:source — trigger an immediate global sync. */
export async function triggerSync(source) {
  const res = await fetch(`/api/sync/${source}`, { method: 'POST' });
  if (!res.ok) throw new Error(`sync ${source} failed: ${res.status}`);
  return res.json();
}

// ── Per-user integrations ─────────────────────────────────────────────────────

function authHeaders(token) {
  return token ? { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
               : { 'Content-Type': 'application/json' };
}

/** GET /api/integrations — list connected integrations for current user. */
export async function fetchIntegrations(token) {
  const res = await fetch('/api/integrations', { headers: authHeaders(token) });
  if (!res.ok) throw new Error(`integrations failed: ${res.status}`);
  return res.json(); // IntegrationDto[]
}

/** GET /api/integrations/status — per-user connection status. */
export async function fetchIntegrationStatus(token) {
  const res = await fetch('/api/integrations/status', { headers: authHeaders(token) });
  if (!res.ok) throw new Error(`integration status failed: ${res.status}`);
  return res.json(); // { github:{connected,lastSyncedAt}, google:{...}, notion:{...} }
}

/** GET /api/integrations/google/auth-url — returns Google OAuth URL. */
export async function fetchGoogleAuthUrl(token) {
  const res = await fetch('/api/integrations/google/auth-url', { headers: authHeaders(token) });
  if (!res.ok) throw new Error(`auth url failed: ${res.status}`);
  return res.json(); // { url }
}

/** GET /api/integrations/notion/auth-url — returns Notion OAuth consent URL. */
export async function fetchNotionAuthUrl(token) {
  const res = await fetch('/api/integrations/notion/auth-url', { headers: authHeaders(token) });
  if (!res.ok) {
    let msg = `notion auth url failed: ${res.status}`;
    try { const b = await res.json(); if (b?.error) msg = b.error; } catch { /* ignore */ }
    throw new Error(msg);
  }
  return res.json(); // { url }
}

/** POST /api/integrations/notion/callback — exchange OAuth code + database ID for tokens. */
export async function submitNotionCode(code, databaseId, token) {
  const res = await fetch('/api/integrations/notion/callback', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ code, databaseId }),
  });
  if (!res.ok) throw new Error(`notion callback failed: ${res.status}`);
  return res.json(); // { connected, workspaceId, workspaceName }
}

/** POST /api/integrations/google/callback — exchange OAuth code for tokens. */
export async function submitGoogleCode(code, token) {
  const res = await fetch('/api/integrations/google/callback', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ code }),
  });
  if (!res.ok) throw new Error(`google callback failed: ${res.status}`);
  return res.json();
}

/** POST /api/integrations/github — save PAT token + repos. */
export async function connectGitHub(pat, repos, token) {
  const res = await fetch('/api/integrations/github', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ token: pat, repos }),
  });
  if (!res.ok) throw new Error(`github connect failed: ${res.status}`);
  return res.json();
}

/** POST /api/integrations/notion — save integration token + database ID. */
export async function connectNotion(notionToken, databaseId, clerkToken) {
  const res = await fetch('/api/integrations/notion', {
    method: 'POST',
    headers: authHeaders(clerkToken),
    body: JSON.stringify({ token: notionToken, databaseId }),
  });
  if (!res.ok) throw new Error(`notion connect failed: ${res.status}`);
  return res.json();
}

/** DELETE /api/integrations/:provider — disconnect an integration. */
export async function disconnectIntegration(provider, token) {
  const res = await fetch(`/api/integrations/${provider}`, {
    method: 'DELETE',
    headers: authHeaders(token),
  });
  if (!res.ok) throw new Error(`disconnect failed: ${res.status}`);
}

/** POST /api/integrations/:provider/sync — trigger manual sync for current user. */
export async function syncIntegration(provider, token) {
  const res = await fetch(`/api/integrations/${provider}/sync`, {
    method: 'POST',
    headers: authHeaders(token),
  });
  if (!res.ok) throw new Error(`sync failed: ${res.status}`);
  return res.json();
}

// ── Auth / user sync ─────────────────────────────────────────────────────────

/**
 * POST /api/users/sync — upsert the Clerk user into our DB.
 * Returns { id, email, username } where id is our BIGSERIAL primary key,
 * used as a foreign key in all other creatoros.* tables.
 * Passwords are never sent here — Clerk holds them encrypted on their servers.
 */
export async function syncUser({ clerkId, email, username, displayName }) {
  const res = await fetch('/api/users/sync', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clerkId, email, username, displayName }),
  });
  if (!res.ok) throw new Error(`user sync failed: ${res.status}`);
  return res.json(); // { id, email, username }
}

/** Retrieve the DB user id stored after the last successful sync. */
export function getStoredUserId() {
  try { return localStorage.getItem('coral.userId'); } catch { return null; }
}

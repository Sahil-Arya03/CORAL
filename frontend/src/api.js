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

/** GET /api/timeline — unified cross-source activity stream. */
export async function fetchTimeline() {
  const res = await fetch('/api/timeline');
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

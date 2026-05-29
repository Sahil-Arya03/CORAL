import React, { useState, useRef, useEffect } from 'react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';
import { Avatar } from '../components/atoms.jsx';
import { streamChat, confirmAction, fetchThreads, createThread, fetchMessages } from '../api.js';

/* ─────────────────────────────────────────────────
   AI CHAT — live against /api/chat (SSE)
   Cursor / Claude desktop style — thread list + thread + context panel
   ───────────────────────────────────────────────── */

const WELCOME = {
  role: 'assistant',
  text: 'I read your calendar, email, code and notes. Ask me what to focus on, ' +
    'or to summarize your day — I answer with full context.',
};

const TAG_COLORS = ['pink', 'blue', 'lilac', 'yellow', 'green'];

function relTime(iso) {
  if (!iso) return 'now';
  try {
    const m = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
    if (m < 1) return 'now';
    if (m < 60) return m + 'm';
    const h = Math.floor(m / 60);
    if (h < 24) return h + 'h';
    return Math.floor(h / 24) + 'd';
  } catch { return 'now'; }
}

export function ChatPage() {
  const [threads, setThreads] = useState([]);
  const [active, setActive] = useState(null);           // UUID of active thread
  const [threadMsgs, setThreadMsgs] = useState({});     // { [id]: Message[] }
  const [composer, setComposer] = useState('');
  const [thinking, setThinking] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState(null);
  const scrollRef = useRef(null);

  const messages = active ? (threadMsgs[active] || [WELCOME]) : [WELCOME];
  const activeIndex = threads.findIndex(t => t.id === active);
  const activeTag = TAG_COLORS[activeIndex >= 0 ? activeIndex % TAG_COLORS.length : 0];

  // ── Bootstrap ─────────────────────────────────────────────────────────────

  useEffect(() => {
    fetchThreads()
      .then(list => {
        if (list.length === 0) return createThread('New Chat').then(t => [t]);
        return list;
      })
      .catch(() => [])
      .then(list => {
        setThreads(list);
        if (list.length > 0) {
          setActive(list[0].id);
        } else {
          // Backend unavailable — use a local session so the textarea stays enabled.
          setActive(crypto.randomUUID());
        }
      });
  }, []);

  // ── Load history when switching threads ───────────────────────────────────

  useEffect(() => {
    if (!active) return;
    if (threadMsgs[active]) { scrollBottom(); return; }
    fetchMessages(active)
      .then(rows => {
        const msgs = rows.length > 0 ? rows.map(r => ({ role: r.role, text: r.content })) : [WELCOME];
        setThreadMsgs(c => ({ ...c, [active]: msgs }));
      })
      .catch(() => setThreadMsgs(c => ({ ...c, [active]: [WELCOME] })));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  useEffect(() => { scrollBottom(); }, [threadMsgs, active, thinking]);

  function scrollBottom() {
    setTimeout(() => {
      if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }, 0);
  }

  // ── Thread helpers ────────────────────────────────────────────────────────

  function setMessages(updater) {
    setThreadMsgs(c => ({ ...c, [active]: updater(c[active] || []) }));
  }

  const setLastAssistant = (updater) =>
    setThreadMsgs(c => {
      const cur = [...(c[active] || [])];
      for (let i = cur.length - 1; i >= 0; i--) {
        if (cur[i].role === 'assistant') { cur[i] = updater(cur[i]); break; }
      }
      return { ...c, [active]: cur };
    });

  async function handleNewThread() {
    if (sending) return;
    setError(null);
    try {
      const t = await createThread('New Chat');
      setThreads(prev => [t, ...prev.filter(x => x.id !== t.id)]);
      setActive(t.id);
      setThreadMsgs(c => ({ ...c, [t.id]: [WELCOME] }));
    } catch (e) {
      setError(e.code === 'max_threads' ? 'Maximum 5 threads reached.' : 'Could not create thread.');
    }
  }

  // ── Send ──────────────────────────────────────────────────────────────────

  async function handleSend() {
    const prompt = composer.trim();
    if (!prompt || sending || !active) return;
    setComposer('');
    setError(null);
    setSending(true);
    setThinking(true);

    setMessages(m => [
      ...m.filter(x => !(x.role === 'assistant' && x.text === WELCOME.text)),
      { role: 'user', text: prompt },
      { role: 'assistant', text: '', streaming: true },
    ]);

    try {
      await streamChat({
        prompt,
        sessionId: active,
        onStatus: () => setThinking(true),
        onToken: (tok) => {
          setThinking(false);
          setLastAssistant(a => ({ ...a, text: a.text + tok }));
        },
        onFinal: (resp) => {
          setThinking(false);
          setLastAssistant(a => ({
            ...a,
            text: resp.text || a.text,
            insights: resp.insights || [],
            actions: resp.actions || [],
            pending: resp.pending || null,
            streaming: false,
          }));
        },
      });

      // Re-fetch the thread list so messageCount, title and updatedAt are accurate.
      fetchThreads().then(fresh => {
        if (fresh.length > 0) {
          setThreads(fresh);
          // If active was a local fallback UUID not yet in DB, keep it selected.
          if (!fresh.find(t => t.id === active) && fresh.length > 0) {
            setActive(fresh[0].id);
          }
        }
      }).catch(() => {});
    } catch (e) {
      setError(e.message || 'Request failed');
      setLastAssistant(a => ({ ...a, streaming: false, text: a.text || '' }));
    } finally {
      setThinking(false);
      setSending(false);
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  const activeThread = threads.find(t => t.id === active);

  return (
    <div className="page" data-screen-label="AI Chat" style={{ paddingBottom: 16 }}>
      <div style={{ display:'flex', alignItems:'flex-start', justifyContent:'space-between', marginBottom: 18 }}>
        <div>
          <div className="tiny" style={{ marginBottom: 8 }}>Conversation</div>
          <h1 className="h-page" style={{ margin: 0, fontSize: 36 }}>
            Ask <span className="serif" style={{ fontWeight: 400 }}>anything</span>, with full memory
          </h1>
          <p className="muted" style={{ margin:'8px 0 0', fontSize: 13.5, maxWidth: 580 }}>
            CreatorOS reads your projects, notes, code and calendar. Ask. It answers with context.
          </p>
        </div>
        <div style={{ display:'flex', gap: 8 }}>
          <button className="btn light" onClick={handleNewThread} disabled={sending}>
            <I.plus width="14" height="14"/> New thread
          </button>
          <button className="btn ghost"><I.more width="14" height="14"/></button>
        </div>
      </div>

      <div className="chat-shell">
        {/* Thread list */}
        <div style={{
          background:'#fff', border:'1px solid var(--hair)',
          borderRadius: 22, padding: 14,
          display:'flex', flexDirection:'column', minHeight: 0,
        }}>
          <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', padding:'4px 4px 12px' }}>
            <div className="h-card" style={{ fontSize: 14 }}>Threads</div>
            <span className="pill">{threads.length}</span>
          </div>
          <div className="scroll chat-thread-list" style={{ display:'flex', flexDirection:'column', gap: 4 }}>
            {threads.map((th, idx) => (
              <div key={th.id}
                className={'item ' + (active === th.id ? 'active' : '')}
                onClick={() => { setActive(th.id); setError(null); }}
              >
                <div style={{ display:'flex', alignItems:'center', gap: 8, marginBottom: 4 }}>
                  <span className={'pill ' + TAG_COLORS[idx % TAG_COLORS.length]} style={{ padding: '2px 8px', fontSize: 10 }}>thread</span>
                  <span className="tiny" style={{ marginLeft:'auto' }}>{relTime(th.updatedAt)}</span>
                </div>
                <div style={{ fontSize: 13, fontWeight: 600, letterSpacing:'-0.005em',
                  whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{th.title}</div>
                <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
                  {th.messageCount > 0 ? `${th.messageCount} msgs · memory on` : 'new · memory on'}
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Main thread */}
        <div style={{ display:'flex', flexDirection:'column', minHeight: 0, gap: 12 }}>
          <div ref={scrollRef} style={{
            flex: 1, minHeight: 0, overflowY:'auto',
            padding: '4px 4px 16px', display:'flex', flexDirection:'column', gap: 14,
            maxWidth: 920, width: '100%', margin: '0 auto',
          }}>
            {activeThread && <ThreadHeader thread={activeThread} tag={activeTag}/>}

            {messages.map((m, i) => (
              <Bubble key={i} role={m.role}>
                {m.role === 'assistant'
                  ? <MarkdownText text={m.text}/>
                  : m.text}
                {m.streaming && <span className="caret"/>}
                {m.role === 'assistant' && !m.streaming && (
                  <AssistantExtras insights={m.insights} actions={m.actions} pending={m.pending}/>
                )}
              </Bubble>
            ))}

            {thinking && <ThinkingTrace/>}
            {error && (
              <div className="pill warn" style={{ alignSelf:'flex-start' }}>
                Couldn't reach Coral — {error}
              </div>
            )}
          </div>

          {/* Composer */}
          <div className="composer" style={{ maxWidth: 920, width: '100%', margin: '0 auto' }}>
            <textarea
              placeholder="Reply, or @memory, /command, #file"
              value={composer}
              onChange={(e) => setComposer(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSend(); }}
              disabled={sending}
            />
            <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
              <span className="pill"><Doodles.Brain size={11}/> Memory: ON</span>
              <span className="pill"><I.github width="11" height="11"/> 2 repos</span>
              <span className="pill"><I.notion width="11" height="11"/> 18 notes</span>
              <span className="pill">Model: Coral-4o</span>
              <button className="btn coral" style={{ marginLeft:'auto', opacity: sending ? 0.6 : 1 }} onClick={handleSend} disabled={sending}>
                {sending ? 'Sending…' : 'Send'} <I.send width="13" height="13"/>
              </button>
            </div>
          </div>
        </div>

      </div>
    </div>
  );
}

function ThreadHeader({ thread, tag = 'blue' }) {
  return (
    <div style={{ display:'flex', alignItems:'center', gap: 10, paddingBottom: 6 }}>
      <span className={'pill ' + tag}>thread</span>
      <div style={{ fontSize: 15, fontWeight: 600,
        whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis', maxWidth: 400 }}>{thread.title}</div>
      <span className="tiny" style={{ marginLeft: 'auto' }}>started {relTime(thread.updatedAt)} ago</span>
    </div>
  );
}

function Bubble({ role, children }) {
  return (
    <div style={{ display:'flex', flexDirection: role === 'user' ? 'row-reverse' : 'row', gap: 10, alignItems:'flex-start' }}>
      {role === 'assistant' && (
        <div style={{
          width: 28, height: 28, borderRadius: 9,
          background:'var(--coral)', flexShrink: 0,
          display:'grid', placeItems:'center'
        }}>
          <Doodles.Sparkle size={14} color="#2a1812"/>
        </div>
      )}
      {role === 'user' && <Avatar initials="A"/>}

      <div className={'bubble ' + role}>
        {role === 'assistant' && (
          <div className="meta">
            <b style={{ color: 'var(--ink)' }}>Coral</b>
            <span>·</span>
            <span>just now</span>
            <span>·</span>
            <span style={{ display:'inline-flex', alignItems:'center', gap: 4 }}>
              <Doodles.Brain size={10}/> reasoning + memory
            </span>
          </div>
        )}
        {children}
      </div>
    </div>
  );
}

/* Structured payload the backend returns alongside the prose answer. */
function AssistantExtras({ insights = [], actions = [], pending = null }) {
  if (!insights.length && !actions.length && !pending) return null;
  return (
    <div style={{ marginTop: 12, display:'flex', flexDirection:'column', gap: 10 }}>
      {insights.length > 0 && (
        <div style={{ display:'flex', flexDirection:'column', gap: 6 }}>
          {insights.map((ins, i) => (
            <div key={i} className="card cream" style={{ padding: 12, borderRadius: 12, border:'none' }}>
              <div style={{ fontSize: 12.5, fontWeight: 600 }}>{ins.title}</div>
              <div className="muted" style={{ fontSize: 12, marginTop: 2, lineHeight: 1.4 }}>{ins.body}</div>
              {ins.source && <div className="tiny" style={{ marginTop: 6 }}>via {ins.source}</div>}
            </div>
          ))}
        </div>
      )}

      {actions.length > 0 && (
        <div style={{ display:'flex', gap: 6, flexWrap:'wrap' }}>
          {actions.map((a, i) => <ActionButton key={i} action={a}/>)}
        </div>
      )}

      {pending && <PendingCard pending={pending}/>}
    </div>
  );
}

function ActionButton({ action }) {
  const [state, setState] = useState(null); // null | 'running' | ActionResult
  async function run() {
    if (!action.confirmToken) return;
    setState('running');
    try { setState(await confirmAction(action.confirmToken)); }
    catch (e) { setState({ executed: false, message: e.message, rowsAffected: 0 }); }
  }
  if (state && state !== 'running') {
    return (
      <span className={'pill ' + (state.executed ? 'ok' : 'warn')} style={{ fontSize: 11.5 }}>
        {state.executed ? '✓ ' : '× '}{state.message}
      </span>
    );
  }
  return (
    <button className="btn light" style={{ fontSize: 11.5, padding:'6px 12px' }} onClick={run} disabled={state === 'running'}>
      {state === 'running' ? 'Working…' : action.label} <I.arrowR width="12" height="12"/>
    </button>
  );
}

function PendingCard({ pending }) {
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  async function confirm() {
    setBusy(true);
    try { setResult(await confirmAction(pending.token)); }
    catch (e) { setResult({ executed: false, message: e.message, rowsAffected: 0 }); }
    finally { setBusy(false); }
  }
  return (
    <div className="card" style={{ padding: 13, borderRadius: 14, background:'var(--yellow-2)', border:'none' }}>
      <div style={{ display:'flex', alignItems:'center', gap: 8, marginBottom: 6 }}>
        <span className="pill warn" style={{ fontSize: 10 }}>confirm needed</span>
        <span style={{ fontSize: 12.5, fontWeight: 600 }}>{pending.actionType}</span>
      </div>
      <div style={{ fontSize: 12.5, lineHeight: 1.4 }}>{pending.description}</div>
      <div className="tiny" style={{ marginTop: 4 }}>affects {pending.affectedRows} row(s)</div>
      {result ? (
        <div className={'pill ' + (result.executed ? 'ok' : 'warn')} style={{ marginTop: 8, fontSize: 11.5 }}>
          {result.executed ? '✓ ' : '× '}{result.message} · {result.rowsAffected} row(s)
        </div>
      ) : (
        <div style={{ display:'flex', gap: 6, marginTop: 8 }}>
          <button className="btn coral" style={{ padding:'6px 12px', fontSize: 11.5 }} onClick={confirm} disabled={busy}>
            {busy ? 'Executing…' : 'Confirm & run'}
          </button>
          <button className="btn ghost" style={{ padding:'6px 12px', fontSize: 11.5 }} onClick={() => setResult({ executed:false, message:'Cancelled', rowsAffected:0 })}>
            Cancel
          </button>
        </div>
      )}
    </div>
  );
}

// ── Markdown renderer ────────────────────────────────────────────────────────

function inlineMarkdown(text) {
  const parts = [];
  const re = /\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`/g;
  let last = 0, key = 0, m;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) parts.push(text.slice(last, m.index));
    if (m[1] !== undefined) parts.push(<strong key={key++}>{m[1]}</strong>);
    else if (m[2] !== undefined) parts.push(<em key={key++}>{m[2]}</em>);
    else if (m[3] !== undefined) parts.push(
      <code key={key++} style={{ background:'rgba(0,0,0,0.06)', padding:'1px 5px', borderRadius:4, fontSize:'0.88em', fontFamily:'monospace' }}>{m[3]}</code>
    );
    last = re.lastIndex;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts.length === 1 && typeof parts[0] === 'string' ? parts[0] : <>{parts}</>;
}

function MarkdownText({ text }) {
  if (!text) return null;
  const lines = text.split('\n');
  const out = [];
  let i = 0, key = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (line.startsWith('### ')) {
      out.push(<p key={key++} style={{ margin:'10px 0 2px', fontSize:13, fontWeight:700, letterSpacing:'-0.01em' }}>{inlineMarkdown(line.slice(4))}</p>);
      i++;
    } else if (line.startsWith('## ')) {
      out.push(<p key={key++} style={{ margin:'12px 0 4px', fontSize:14, fontWeight:700, letterSpacing:'-0.02em' }}>{inlineMarkdown(line.slice(3))}</p>);
      i++;
    } else if (line.startsWith('# ')) {
      out.push(<p key={key++} style={{ margin:'14px 0 6px', fontSize:15, fontWeight:700, letterSpacing:'-0.02em' }}>{inlineMarkdown(line.slice(2))}</p>);
      i++;
    } else if (/^[-*] /.test(line)) {
      const items = [];
      while (i < lines.length && /^[-*] /.test(lines[i])) {
        items.push(<li key={i} style={{ lineHeight:1.55 }}>{inlineMarkdown(lines[i].slice(2))}</li>);
        i++;
      }
      out.push(<ul key={key++} style={{ margin:'4px 0 6px', paddingLeft:20, display:'flex', flexDirection:'column', gap:2 }}>{items}</ul>);
    } else if (/^\d+\. /.test(line)) {
      const items = [];
      while (i < lines.length && /^\d+\. /.test(lines[i])) {
        out.push(
          <div key={key++} style={{ display:'flex', gap:10, alignItems:'flex-start', margin:'6px 0' }}>
            <span style={{ minWidth:20, height:20, borderRadius:6, background:'var(--coral)', display:'grid', placeItems:'center', fontSize:11, fontWeight:700, color:'#2a1812', flexShrink:0, marginTop:1 }}>
              {lines[i].match(/^(\d+)\./)[1]}
            </span>
            <span style={{ lineHeight:1.55 }}>{inlineMarkdown(lines[i].replace(/^\d+\. /, ''))}</span>
          </div>
        );
        i++;
      }
    } else if (line.trim() === '') {
      if (out.length > 0) out.push(<div key={key++} style={{ height:5 }}/>);
      i++;
    } else {
      const para = [];
      while (i < lines.length && lines[i].trim() !== '' && !/^[#\-*]/.test(lines[i]) && !/^\d+\. /.test(lines[i])) {
        para.push(lines[i]);
        i++;
      }
      out.push(<p key={key++} style={{ margin:'0 0 6px', lineHeight:1.6 }}>{inlineMarkdown(para.join(' '))}</p>);
    }
  }
  return <div style={{ fontSize:13.5 }}>{out}</div>;
}

function ThinkingTrace({ label = "Reading notes, commits and calendar events" }) {
  return (
    <div style={{ display:'flex', gap: 10, alignItems:'flex-start' }}>
      <div style={{
        width: 28, height: 28, borderRadius: 9,
        background:'var(--coral)', flexShrink: 0,
        display:'grid', placeItems:'center'
      }}>
        <Doodles.Sparkle size={14} color="#2a1812"/>
      </div>
      <div style={{
        background:'transparent', border: '1px dashed var(--hair)', borderRadius: 14,
        padding: '10px 14px', display:'flex', alignItems:'center', gap: 10,
      }}>
        <span className="thinking"><span/><span/><span/></span>
        <span style={{ fontSize: 12, color: 'var(--muted)' }}>{label}</span>
      </div>
    </div>
  );
}

function ContextPanel() {
  return (
    <div style={{
      background:'#fff', border:'1px solid var(--hair)',
      borderRadius: 22, padding: 16, minHeight: 0,
      display:'flex', flexDirection:'column', gap: 14, overflow:'auto'
    }}>
      <div className="h-card">Context</div>

      <div className="card cream" style={{ padding: 12, borderRadius: 14 }}>
        <div className="tiny" style={{ marginBottom: 6 }}>Active memory</div>
        <div style={{ fontSize: 13, fontWeight: 600 }}>spring-2026/thesis</div>
        <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>
          18 notes · 4 outlines · 12 references
        </div>
      </div>

      <div>
        <div className="tiny" style={{ marginBottom: 8 }}>Reading</div>
        <div style={{ display:'flex', flexDirection:'column', gap: 6 }}>
          {[
            { i: I.notion,  t:'§3.1 problem framing.md', s:'updated May 19' },
            { i: I.notion,  t:'evaluation methods.md',   s:'updated May 22' },
            { i: I.github,  t:'synthwave/eval-bench',    s:'12 commits this wk' },
            { i: I.doc,     t:'Karpathy 2024.pdf',       s:'reader · ch.2 highlighted' },
          ].map((s, i) => (
            <div key={i} style={{ display:'flex', alignItems:'center', gap: 8, padding:'6px 8px', borderRadius: 10, background:'rgba(14,14,14,0.03)' }}>
              <s.i width="14" height="14"/>
              <div style={{ minWidth: 0, flex: 1 }}>
                <div style={{ fontSize: 12, fontWeight: 500, whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis' }}>{s.t}</div>
                <div className="muted" style={{ fontSize: 10.5 }}>{s.s}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="tiny" style={{ marginBottom: 8 }}>Continuity</div>
        <div className="card" style={{ padding: 12, borderRadius: 14, background:'var(--lilac-2)', border:'none' }}>
          <div style={{ fontSize: 12, lineHeight: 1.5 }}>
            <b>Last session</b> · May 25 — you committed to rescoping chapter 3 to focus on
            "latency tradeoffs". Today's reply uses that scope.
          </div>
          <button className="btn ghost" style={{ marginTop: 8, fontSize: 11.5, padding:'6px 10px' }}>
            Open session
          </button>
        </div>
      </div>

      <div>
        <div className="tiny" style={{ marginBottom: 8 }}>Suggested actions</div>
        <div style={{ display:'flex', flexDirection:'column', gap: 6 }}>
          {['Generate citations','Send to Notion','Create review task','Schedule deep-work block'].map((a, i) => (
            <button key={i} className="btn light" style={{ justifyContent:'space-between', width:'100%', fontSize: 12, padding:'8px 12px' }}>
              {a} <I.arrowR width="12" height="12"/>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
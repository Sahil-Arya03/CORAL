import React, { useState, useEffect, useCallback } from 'react';
import { useAuth } from '@clerk/clerk-react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';
import {
  fetchTimeline,
  createCalendarEvent,
  updateCalendarEvent,
  deleteCalendarEvent,
} from '../api.js';

/* ── Source config ───────────────────────────────────────────────────────── */

const SRC = {
  github:     { tone:'blue',   icon:I.github,        label:'GitHub' },
  notion:     { tone:'lilac',  icon:I.notion,        label:'Notion' },
  calendar:   { tone:'yellow', icon:I.cal,           label:'Calendar' },
  gmail:      { tone:'green',  icon:I.mail,          label:'Gmail' },
  reflection: { tone:'pink',   icon:Doodles.Sparkle, label:'AI' },
};
const srcMeta = (s) => SRC[s] || { tone:'cream', icon:I.doc, label: s || 'source' };

/* ── Date helpers ────────────────────────────────────────────────────────── */

const DAY_LABELS   = ['MON','TUE','WED','THU','FRI','SAT','SUN'];
const MONTHS_SHORT = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const MONTHS_LONG  = ['January','February','March','April','May','June','July','August','September','October','November','December'];

// Grid covers 07:00–21:00 local time (15 slots)
const FIRST_HOUR = 7;
const TIMES = Array.from({ length: 15 }, (_, i) => {
  const h = FIRST_HOUR + i;
  return `${String(h).padStart(2,'0')}:00`;
});

const startOfDay  = (d)   => { const x = new Date(d); x.setHours(0,0,0,0); return x; };
const mondayOf    = (d)   => { const x = startOfDay(d); x.setDate(x.getDate() - (x.getDay()+6)%7); return x; };
const sameDay     = (a,b) => startOfDay(a).getTime() === startOfDay(b).getTime();
const addDays     = (d,n) => { const x = new Date(d); x.setDate(x.getDate()+n); return x; };

// All-day calendar events are stored at midnight UTC by CalendarApiClient.
// Detected by type="event" + all UTC time components === 0.
const isAllDay = (ev) =>
  ev.type === 'event' &&
  new Date(ev.occurredAt).getUTCHours()   === 0 &&
  new Date(ev.occurredAt).getUTCMinutes() === 0 &&
  new Date(ev.occurredAt).getUTCSeconds() === 0;

// Date key helpers — must use the same convention for event bucket key and cell lookup key.
// All-day events: UTC date components (avoids timezone shift for midnight-UTC events).
// Timed events: local date components (shows event on the day it occurs locally).
const utcDateKey   = (d) => `${d.getUTCFullYear()}-${d.getUTCMonth()}-${d.getUTCDate()}`;
const localDateKey = (d) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;

const toLocalISO = (dateStr, timeStr) => {
  const [h, m] = timeStr.split(':');
  // dateStr "YYYY-MM-DD" parsed as UTC midnight; setHours shifts to local time
  const d = new Date(dateStr);
  d.setHours(parseInt(h,10), parseInt(m,10), 0, 0);
  return d.toISOString();
};

/* ── Page ────────────────────────────────────────────────────────────────── */

export function TimelinePage() {
  const { getToken } = useAuth();
  const [view,   setView]   = useState('Week');
  // offset meaning: Day → days from today; Week → weeks from current; Month → months from current
  const [offset, setOffset] = useState(0);
  const [events, setEvents] = useState([]);
  const [status, setStatus] = useState('loading');
  const [errMsg, setErrMsg] = useState('');
  const [modal,  setModal]  = useState(null);

  const load = useCallback(async () => {
    setStatus('loading');
    try {
      const t    = await getToken().catch(() => null);
      const data = await fetchTimeline(t);
      setEvents(Array.isArray(data) ? data : []);
      setStatus('ok');
    } catch (e) { setErrMsg(e.message); setStatus('error'); }
  }, [getToken]);

  useEffect(() => { load(); }, [load]);

  const today = new Date();

  const switchView = (v) => { setView(v); setOffset(0); };
  const prev    = () => setOffset(o => o - 1);
  const next    = () => setOffset(o => o + 1);
  const goToday = () => setOffset(0);

  // Compute display window for the current view + offset
  let days, periodLabel, monthCtx;

  if (view === 'Day') {
    const day = addDays(today, offset);
    days = [{ date: day, label: DAY_LABELS[(day.getDay()+6)%7], active: sameDay(day, today) }];
    periodLabel = day.toLocaleDateString('en-US', {
      weekday:'long', month:'long', day:'numeric', year:'numeric',
    });
  } else if (view === 'Month') {
    const ref = new Date(today.getFullYear(), today.getMonth() + offset, 1);
    monthCtx  = { year: ref.getFullYear(), month: ref.getMonth() };
    days      = null; // MonthView builds its own grid
    periodLabel = `${MONTHS_LONG[ref.getMonth()]} ${ref.getFullYear()}`;
  } else {
    // Week
    const mon = addDays(mondayOf(today), offset * 7);
    const sun = addDays(mon, 6);
    days = Array.from({ length: 7 }, (_, i) => {
      const d = addDays(mon, i);
      return { date: d, label: DAY_LABELS[i], active: sameDay(d, today) };
    });
    periodLabel = `${MONTHS_SHORT[mon.getMonth()]} ${mon.getDate()} – ${sun.getDate()}, ${sun.getFullYear()}`;
  }

  // Modal always needs a days array (Month view supplies the current week instead)
  const modalDays = days ?? Array.from({ length: 7 }, (_, i) => {
    const d = addDays(mondayOf(today), i);
    return { date: d, label: DAY_LABELS[i], active: sameDay(d, today) };
  });

  const openCreate = () => setModal({ mode: 'create' });
  const openEdit   = (ev) => setModal({ mode: 'edit', ev });
  const closeModal = () => setModal(null);

  return (
    <div className="page" data-screen-label="Timeline">
      {/* Header */}
      <div style={{ display:'flex', alignItems:'flex-start', justifyContent:'space-between', marginBottom: 22 }}>
        <div>
          <div className="tiny" style={{ marginBottom: 8 }}>Unified timeline</div>
          <h1 className="h-page" style={{ margin: 0 }}>
            Everything you did, in <span className="serif" style={{ fontWeight: 400 }}>one</span> place
          </h1>
          <p className="muted" style={{ margin:'10px 0 0', fontSize: 14, maxWidth: 580 }}>
            GitHub commits, Notion changes, calendar events, email and AI reflections — woven into your week.
          </p>
        </div>
        <div style={{ display:'flex', gap: 8, alignItems:'center' }}>
          <span className="pill"><I.github width="11" height="11"/> GitHub</span>
          <span className="pill"><I.notion width="11" height="11"/> Notion</span>
          <span className="pill"><I.cal width="11" height="11"/> Calendar</span>
          <span className="pill"><Doodles.Sparkle size={11}/> AI reflections</span>
          <button className="btn" onClick={openCreate}><I.plus width="13" height="13"/> Add event</button>
        </div>
      </div>

      {/* Navigation bar */}
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 14 }}>
        <div style={{ display:'flex', alignItems:'center', gap: 10 }}>
          <button className="icon-btn ghost" onClick={prev}><I.chevL width="14" height="14"/></button>
          <span className="pill" style={{ background:'var(--cream)', borderColor:'#ece4ca', padding:'8px 14px' }}>
            <I.cal width="12" height="12"/> {periodLabel}
          </span>
          <button className="icon-btn ghost" onClick={next}><I.chevR width="14" height="14"/></button>
          <button
            className="btn ghost"
            style={{ marginLeft: 8, fontSize: 12, opacity: offset === 0 ? 0.35 : 1 }}
            onClick={goToday}
            disabled={offset === 0}
          >
            Today
          </button>
        </div>
        <div className="seg">
          {['Day','Week','Month'].map(v => (
            <button key={v} className={view === v ? 'active' : ''} onClick={() => switchView(v)}>{v}</button>
          ))}
        </div>
      </div>

      {status === 'error' && (
        <div className="card warn" style={{ padding: 16, borderRadius: 18, marginBottom: 14 }}>
          <b>Couldn't load the timeline.</b> <span className="muted">{errMsg}</span>
        </div>
      )}

      {view === 'Month'
        ? <MonthView  events={events} status={status} {...monthCtx} onEdit={openEdit}/>
        : <GridView   events={events} status={status} days={days}   onEdit={openEdit}/>
      }

      <div style={{ marginTop: 16 }}>
        <ReflectionsCard events={events}/>
      </div>

      {modal && (
        <EventModal
          mode={modal.mode} ev={modal.ev}
          days={modalDays}
          getToken={getToken}
          onClose={closeModal}
          onDone={async () => { closeModal(); await load(); }}
        />
      )}
    </div>
  );
}

/* ── Week / Day grid ─────────────────────────────────────────────────────── */

function GridView({ events, status, days, onEdit }) {
  // For Day view (1 column) we override the CSS grid's hardcoded 7 columns
  const gridCols = days.length === 7
    ? {}
    : { gridTemplateColumns: '56px 1fr' };

  // Separate all-day from timed events
  const allDayCells = {}; // dayIdx → ev[]
  const grid        = {}; // `${dayIdx}-${timeIdx}` → ev[]

  for (const ev of events) {
    const d = new Date(ev.occurredAt);

    if (isAllDay(ev)) {
      // Use UTC date so timezone offsets don't shift the event to a neighbouring day
      const key    = utcDateKey(d);
      const dayIdx = days.findIndex(day => localDateKey(day.date) === key);
      if (dayIdx < 0) continue;
      (allDayCells[dayIdx] ||= []).push(ev);
      continue;
    }

    // Timed events — place by local hour; clamp out-of-range to grid edges
    const dayIdx = days.findIndex(day => localDateKey(day.date) === localDateKey(d));
    if (dayIdx < 0) continue;
    const rawIdx  = d.getHours() - FIRST_HOUR;
    const timeIdx = Math.max(0, Math.min(rawIdx, TIMES.length - 1));
    (grid[`${dayIdx}-${timeIdx}`] ||= []).push(ev);
  }

  const hasAllDay = Object.keys(allDayCells).length > 0;

  return (
    <div className="card" style={{ padding: 16, borderRadius: 22 }}>
      {/* Day-of-week header row */}
      <div className="tl-grid" style={gridCols}>
        <div/>
        {days.map((day, i) => (
          <div key={i} className={'tl-head ' + (day.active ? 'active' : '')}>
            <div style={{ fontSize: 10.5, opacity: 0.7, letterSpacing:'0.08em' }}>{day.label}</div>
            <div style={{ fontSize: 22, fontWeight: 700, letterSpacing:'-0.02em', lineHeight: 1.1 }}>
              {String(day.date.getDate()).padStart(2,'0')}/{String(day.date.getMonth()+1).padStart(2,'0')}
            </div>
          </div>
        ))}
      </div>

      {/* All-day strip — only rendered when there are all-day events */}
      {hasAllDay && (
        <div className="tl-grid" style={{
          ...gridCols,
          borderTop: '1px dashed var(--hair)',
          paddingTop: 6, paddingBottom: 4,
        }}>
          <div className="tl-time" style={{ fontSize: 9, paddingTop: 6 }}>all day</div>
          {days.map((_, di) => (
            <div key={di} className="tl-cell" style={{ minHeight: 36 }}>
              {(allDayCells[di] || []).map((ev, k) => (
                <EventChip key={k} ev={ev} highlight={false}
                  onEdit={ev.source === 'calendar' ? () => onEdit(ev) : null}
                />
              ))}
            </div>
          ))}
        </div>
      )}

      {/* Hourly rows */}
      <div style={{ marginTop: 10 }}>
        {TIMES.map((time, ti) => (
          <div key={ti} className="tl-grid" style={{
            ...gridCols,
            borderTop: ti === 0 ? 'none' : '1px dashed var(--hair)',
            paddingTop: ti === 0 ? 0 : 8,
            paddingBottom: 6,
          }}>
            <div className="tl-time">{time}</div>
            {days.map((day, di) => {
              const cell = grid[`${di}-${ti}`] || [];
              return (
                <div key={di} className="tl-cell">
                  {cell.map((ev, k) => (
                    <EventChip key={k} ev={ev} highlight={day.active && k === 0}
                      onEdit={ev.source === 'calendar' ? () => onEdit(ev) : null}
                    />
                  ))}
                </div>
              );
            })}
          </div>
        ))}
      </div>

      {status === 'loading' && (
        <div className="muted" style={{ textAlign:'center', padding:'24px 0', fontSize:13 }}>
          Loading your timeline…
        </div>
      )}
      {status === 'ok' && events.length === 0 && (
        <div className="muted" style={{ textAlign:'center', padding:'24px 0', fontSize:13 }}>
          No events yet — connect Google Calendar in{' '}
          <a href="/integrations" style={{ color:'var(--coral)' }}>Integrations</a> to see your data.
        </div>
      )}
    </div>
  );
}

/* ── Month grid view ─────────────────────────────────────────────────────── */

function MonthView({ events, status, year, month, onEdit }) {
  const today     = new Date();
  const gridStart = mondayOf(new Date(year, month, 1));

  // Bucket events by date key
  const byDay = {};
  for (const ev of events) {
    const d   = new Date(ev.occurredAt);
    const key = isAllDay(ev) ? utcDateKey(d) : localDateKey(d);
    (byDay[key] ||= []).push(ev);
  }

  // Build up to 6 rows × 7 days; stop when a full row falls outside the month
  const rows = [];
  for (let r = 0; r < 6; r++) {
    const row = Array.from({ length: 7 }, (_, i) => addDays(gridStart, r * 7 + i));
    if (row.every(d => d.getMonth() !== month)) break;
    rows.push(row);
  }

  return (
    <div className="card" style={{ padding: 16, borderRadius: 22 }}>
      {/* Day-of-week header */}
      <div style={{ display:'grid', gridTemplateColumns:'repeat(7,1fr)', gap: 4, marginBottom: 6 }}>
        {DAY_LABELS.map(d => (
          <div key={d} style={{
            textAlign:'center', fontSize: 10.5, opacity: 0.55,
            letterSpacing:'0.08em', padding:'4px 0',
          }}>{d}</div>
        ))}
      </div>

      {/* Day cells */}
      <div style={{ display:'flex', flexDirection:'column', gap: 4 }}>
        {rows.map((row, ri) => (
          <div key={ri} style={{ display:'grid', gridTemplateColumns:'repeat(7,1fr)', gap: 4 }}>
            {row.map((day, di) => {
              const inMonth = day.getMonth() === month;
              const isToday = sameDay(day, today);
              const evs     = byDay[localDateKey(day)] || [];
              return (
                <div key={di} style={{
                  minHeight: 80, borderRadius: 10, padding:'6px 7px',
                  background: isToday ? 'rgba(244,165,141,0.1)' : 'transparent',
                  border: isToday
                    ? '1.5px solid rgba(244,165,141,0.5)'
                    : '1px solid var(--hair)',
                  opacity: inMonth ? 1 : 0.35,
                }}>
                  <div style={{
                    fontSize: 12, fontWeight: isToday ? 700 : 500,
                    color: isToday ? 'var(--coral)' : 'inherit',
                    marginBottom: 4,
                  }}>
                    {day.getDate()}
                  </div>
                  <div style={{ display:'flex', flexDirection:'column', gap: 2 }}>
                    {evs.slice(0, 3).map((ev, k) => {
                      const meta = srcMeta(ev.source);
                      return (
                        <div key={k}
                          className={`tl-event ${meta.tone}`}
                          title={ev.title}
                          style={{
                            padding:'2px 5px', borderRadius: 5, fontSize: 9.5,
                            cursor: ev.source === 'calendar' ? 'pointer' : 'default',
                            whiteSpace:'nowrap', overflow:'hidden', textOverflow:'ellipsis',
                          }}
                          onClick={ev.source === 'calendar' ? () => onEdit(ev) : undefined}
                        >
                          {ev.title}
                        </div>
                      );
                    })}
                    {evs.length > 3 && (
                      <div style={{ fontSize: 9, opacity: 0.5, paddingLeft: 4 }}>
                        +{evs.length - 3} more
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        ))}
      </div>

      {status === 'loading' && (
        <div className="muted" style={{ textAlign:'center', padding:'24px 0', fontSize:13 }}>
          Loading…
        </div>
      )}
    </div>
  );
}

/* ── Event chip (week / day grid) ────────────────────────────────────────── */

function EventChip({ ev, highlight, onEdit }) {
  const meta = srcMeta(ev.source);
  const Icon = meta.icon;
  return (
    <div
      className={'tl-event ' + meta.tone}
      style={{
        borderRadius: 12, padding: 10, position:'relative',
        boxShadow: highlight ? '0 6px 24px -10px rgba(244,165,141,0.7)' : 'none',
        outline:    highlight ? '1.5px solid rgba(244,165,141,0.6)' : 'none',
        cursor: onEdit ? 'pointer' : 'default',
      }}
      onClick={onEdit || undefined}
    >
      <div className="src" style={{ display:'flex', alignItems:'center', gap: 3 }}>
        <Icon width="9" height="9" size={9}/> <span>{ev.source}</span>
        {onEdit && <span style={{ marginLeft:'auto', fontSize: 9, opacity: 0.5 }}>edit</span>}
      </div>
      <div className="ttitle">{ev.title}</div>
      <div className="tsub">{ev.summary}</div>
    </div>
  );
}

/* ── Event modal (create + edit) ─────────────────────────────────────────── */

function EventModal({ mode, ev, days, getToken, onClose, onDone }) {
  const activeDay = days.find(d => d.active)?.date || days[0].date;
  const dateStr   = (d) => d.toISOString().slice(0,10); // YYYY-MM-DD

  const [title, setTitle] = useState(mode === 'edit' ? ev.title : '');
  const [date,  setDate]  = useState(dateStr(activeDay));
  const [start, setStart] = useState('09:00');
  const [end,   setEnd]   = useState('10:00');
  const [desc,  setDesc]  = useState(mode === 'edit' ? (ev.meta?.description || '') : '');
  const [busy,  setBusy]  = useState(false);
  const [error, setError] = useState('');

  const evId = mode === 'edit' ? (ev.meta?.id || '') : '';

  const submit = async () => {
    if (!title.trim()) { setError('Title is required'); return; }
    setBusy(true); setError('');
    try {
      const t       = await getToken().catch(() => null);
      const payload = {
        title:       title.trim(),
        startAt:     toLocalISO(date, start),
        endAt:       toLocalISO(date, end),
        description: desc.trim(),
        location:    '',
      };
      if (mode === 'create') await createCalendarEvent(payload, t);
      else                   await updateCalendarEvent(evId, payload, t);
      await onDone();
    } catch (e) { setError(e.message); setBusy(false); }
  };

  const remove = async () => {
    if (!window.confirm('Delete this event from Google Calendar?')) return;
    setBusy(true);
    try {
      const t = await getToken().catch(() => null);
      await deleteCalendarEvent(evId, t);
      await onDone();
    } catch (e) { setError(e.message); setBusy(false); }
  };

  return (
    <div
      style={{
        position:'fixed', inset:0, background:'rgba(14,14,14,0.45)',
        display:'grid', placeItems:'center', zIndex:1000,
      }}
      onClick={(e) => e.target === e.currentTarget && onClose()}
    >
      <div className="card" style={{
        padding:28, width:420, borderRadius:24,
        display:'flex', flexDirection:'column', gap:14,
      }}>
        <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between' }}>
          <div style={{ fontWeight:700, fontSize:16 }}>
            {mode === 'create' ? 'Add event' : 'Edit event'}
          </div>
          <button className="icon-btn ghost" onClick={onClose} style={{ fontSize:18, lineHeight:1 }}>×</button>
        </div>

        <div style={{ display:'flex', flexDirection:'column', gap:10 }}>
          <label style={{ fontSize:12, fontWeight:600 }}>Title</label>
          <input className="form-input" placeholder="Event title"
            value={title} onChange={e => setTitle(e.target.value)} autoFocus/>

          <label style={{ fontSize:12, fontWeight:600 }}>Date</label>
          <input className="form-input" type="date"
            value={date} onChange={e => setDate(e.target.value)}/>

          <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:10 }}>
            <div>
              <label style={{ fontSize:12, fontWeight:600 }}>Start time</label>
              <input className="form-input" type="time" style={{ marginTop:6 }}
                value={start} onChange={e => setStart(e.target.value)}/>
            </div>
            <div>
              <label style={{ fontSize:12, fontWeight:600 }}>End time</label>
              <input className="form-input" type="time" style={{ marginTop:6 }}
                value={end} onChange={e => setEnd(e.target.value)}/>
            </div>
          </div>

          <label style={{ fontSize:12, fontWeight:600 }}>Description (optional)</label>
          <textarea className="form-input" placeholder="Add details…" rows={3}
            value={desc} onChange={e => setDesc(e.target.value)}
            style={{ resize:'vertical' }}/>
        </div>

        {error && <div style={{ fontSize:12, color:'#b91c1c' }}>{error}</div>}

        <div style={{ display:'flex', gap:8, marginTop:4 }}>
          <button className="btn coral" onClick={submit} disabled={busy} style={{ flex:1 }}>
            {busy ? 'Saving…' : mode === 'create' ? 'Create event' : 'Save changes'}
          </button>
          {mode === 'edit' && (
            <button className="btn" onClick={remove} disabled={busy}
              style={{ background:'#fee2e2', color:'#b91c1c', border:'none' }}>
              Delete
            </button>
          )}
          <button className="btn light" onClick={onClose} disabled={busy}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

/* ── Reflections card ────────────────────────────────────────────────────── */

function ReflectionsCard({ events }) {
  const counts = {};
  for (const ev of events) counts[ev.source] = (counts[ev.source] || 0) + 1;
  const order = ['github','notion','calendar','gmail','reflection'];
  const rows  = order.filter(s => counts[s]).map(s => ({ source:s, count:counts[s], ...srcMeta(s) }));
  const max   = Math.max(1, ...rows.map(r => r.count));

  return (
    <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:16 }}>
      <div className="card pink" style={{ padding:20, position:'relative' }}>
        <Doodles.Sparkle size={18} style={{ position:'absolute', top:16, right:16, opacity:0.55 }}/>
        <div className="tiny">AI reflection · today</div>
        <div style={{ fontSize:18, fontWeight:600, letterSpacing:'-0.012em', marginTop:8, lineHeight:1.35, maxWidth:480 }}>
          "You moved fast on code but skipped the reading block — second time this week. Trade something."
        </div>
        <div className="muted" style={{ fontSize:11.5, marginTop:10 }}>generated 14:02 · based on today's stream</div>
      </div>

      <div className="card cream" style={{ padding:20 }}>
        <div className="h-card">This week, so far</div>
        <div className="tiny" style={{ marginTop:2, marginBottom:14 }}>by source · {events.length} events</div>

        {rows.length === 0 ? (
          <div className="muted" style={{ fontSize:12.5 }}>
            No activity yet — connect integrations to see your data here.
          </div>
        ) : (
          <div style={{ display:'flex', flexDirection:'column', gap:10 }}>
            {rows.map((r, i) => {
              const Icon = r.icon;
              return (
                <div key={i} style={{ display:'flex', alignItems:'center', gap:10 }}>
                  <Icon width="13" height="13" size={13}/>
                  <div style={{ flex:1, minWidth:0 }}>
                    <div style={{ display:'flex', alignItems:'baseline', gap:6 }}>
                      <span style={{ fontSize:12.5, fontWeight:600 }}>{r.label}</span>
                      <span className="muted" style={{ fontSize:11.5 }}>· {r.count} event{r.count === 1 ? '' : 's'}</span>
                    </div>
                  </div>
                  <div style={{ width:80, height:4, borderRadius:4, background:'rgba(14,14,14,0.08)' }}>
                    <div style={{ width:`${(r.count/max)*100}%`, height:'100%', background:'#0e0e0e', borderRadius:4 }}/>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

import React, { useState, useEffect } from 'react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';
import { fetchTimeline } from '../api.js';

/* ─────────────────────────────────────────────────
   UNIFIED TIMELINE — live against /api/timeline
   week view crossing GitHub, Notion, Calendar, Gmail, AI reflections
   ───────────────────────────────────────────────── */

const SRC = {
  github:     { tone:'blue',   icon:I.github,        label:'GitHub' },
  notion:     { tone:'lilac',  icon:I.notion,        label:'Notion' },
  calendar:   { tone:'yellow', icon:I.cal,           label:'Calendar' },
  gmail:      { tone:'green',  icon:I.mail,          label:'Gmail' },
  reflection: { tone:'pink',   icon:Doodles.Sparkle, label:'AI' },
};
const srcMeta = (s) => SRC[s] || { tone:'cream', icon:I.doc, label: s || 'source' };

const DAY_LABELS = ['MON','TUE','WED','THU','FRI','SAT','SUN'];
const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
const TIMES = ['08:00','09:00','10:00','11:00','12:00','13:00','14:00','15:00','16:00','17:00'];
const FIRST_HOUR = 8;

const startOfDay = (d) => { const x = new Date(d); x.setHours(0,0,0,0); return x; };
const mondayOf = (d) => { const x = startOfDay(d); const idx = (x.getDay()+6)%7; x.setDate(x.getDate()-idx); return x; };
const sameDay = (a, b) => startOfDay(a).getTime() === startOfDay(b).getTime();

export function TimelinePage() {
  const [view, setView] = useState('Week');
  const [events, setEvents] = useState([]);
  const [status, setStatus] = useState('loading'); // loading | ok | error
  const [errMsg, setErrMsg] = useState('');

  useEffect(() => {
    let alive = true;
    fetchTimeline()
      .then(data => { if (alive) { setEvents(Array.isArray(data) ? data : []); setStatus('ok'); } })
      .catch(e => { if (alive) { setErrMsg(e.message); setStatus('error'); } });
    return () => { alive = false; };
  }, []);

  // Anchor the week on today; data is centered on the current week.
  const today = new Date();
  const monday = mondayOf(today);
  const days = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(monday); d.setDate(monday.getDate() + i);
    return { date: d, label: DAY_LABELS[i], active: sameDay(d, today) };
  });
  const sunday = days[6].date;
  const weekLabel = `${MONTHS[monday.getMonth()]} ${monday.getDate()} – ${sunday.getDate()}, ${sunday.getFullYear()}`;

  // Bucket events into [dayIndex][timeIndex] for the grid.
  const grid = {};
  for (const ev of events) {
    const d = new Date(ev.occurredAt);
    const dayIdx = Math.round((startOfDay(d).getTime() - monday.getTime()) / 86400000);
    if (dayIdx < 0 || dayIdx > 6) continue;
    const timeIdx = d.getHours() - FIRST_HOUR;
    if (timeIdx < 0 || timeIdx >= TIMES.length) continue;
    (grid[`${dayIdx}-${timeIdx}`] ||= []).push(ev);
  }

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
          <button className="btn"><I.plus width="13" height="13"/> Add event</button>
        </div>
      </div>

      {/* Week picker */}
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 14 }}>
        <div style={{ display:'flex', alignItems:'center', gap: 10 }}>
          <button className="icon-btn ghost"><I.chevL width="14" height="14"/></button>
          <span className="pill" style={{ background:'var(--cream)', borderColor:'#ece4ca', padding:'8px 14px' }}>
            <I.cal width="12" height="12"/> {weekLabel}
          </span>
          <button className="icon-btn ghost"><I.chevR width="14" height="14"/></button>
          <button className="btn ghost" style={{ marginLeft: 8, fontSize: 12 }}>Today</button>
        </div>
        <div className="seg">
          {['Day','Week','Month'].map(v => (
            <button key={v} className={view === v ? 'active' : ''} onClick={() => setView(v)}>{v}</button>
          ))}
        </div>
      </div>

      {status === 'error' && (
        <div className="card warn" style={{ padding: 16, borderRadius: 18, marginBottom: 14 }}>
          <b>Couldn’t load the timeline.</b> <span className="muted">{errMsg}</span> — is the backend running on :8080?
        </div>
      )}

      {/* Day headers + grid */}
      <div className="card" style={{ padding: 16, borderRadius: 22, position:'relative' }}>
        <div className="tl-grid">
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

        {/* Grid rows */}
        <div style={{ marginTop: 10 }}>
          {TIMES.map((time, ti) => (
            <div key={ti} className="tl-grid" style={{ borderTop: ti === 0 ? 'none' : '1px dashed var(--hair)', paddingTop: ti === 0 ? 0 : 8, paddingBottom: 6 }}>
              <div className="tl-time">{time}</div>
              {days.map((day, di) => {
                const cell = grid[`${di}-${ti}`] || [];
                return (
                  <div key={di} className="tl-cell">
                    {cell.map((ev, k) => <TimelineEvent key={k} ev={ev} highlight={day.active && k === 0}/>)}
                  </div>
                );
              })}
            </div>
          ))}
        </div>

        {status === 'loading' && (
          <div className="muted" style={{ textAlign:'center', padding: '24px 0', fontSize: 13 }}>Loading your week…</div>
        )}
      </div>

      {/* AI reflection + this-week-so-far — side by side */}
      <div style={{ marginTop: 16 }}>
        <ReflectionsCard events={events}/>
      </div>
    </div>
  );
}

function TimelineEvent({ ev, highlight }) {
  const meta = srcMeta(ev.source);
  const Icon = meta.icon;
  return (
    <div className={'tl-event ' + meta.tone} style={{
      borderRadius: 12, padding: 10,
      boxShadow: highlight ? '0 6px 24px -10px rgba(244,165,141,0.7)' : 'none',
      outline: highlight ? '1.5px solid rgba(244,165,141,0.6)' : 'none',
    }}>
      <div className="src" style={{ display:'flex', alignItems:'center', gap: 3 }}>
        <Icon width="9" height="9" size={9}/> <span>{ev.source}</span>
      </div>
      <div className="ttitle">{ev.title}</div>
      <div className="tsub">{ev.summary}</div>
    </div>
  );
}

function ReflectionsCard({ events }) {
  const counts = {};
  for (const ev of events) counts[ev.source] = (counts[ev.source] || 0) + 1;
  const order = ['github','notion','calendar','gmail','reflection'];
  const rows = order
    .filter(s => counts[s])
    .map(s => ({ source: s, count: counts[s], ...srcMeta(s) }));
  const max = Math.max(1, ...rows.map(r => r.count));

  return (
    <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap: 16 }}>
      <div className="card pink" style={{ padding: 20, position:'relative' }}>
        <Doodles.Sparkle size={18} style={{ position:'absolute', top: 16, right: 16, opacity: 0.55 }}/>
        <div className="tiny">AI reflection · today</div>
        <div style={{ fontSize: 18, fontWeight: 600, letterSpacing:'-0.012em', marginTop: 8, lineHeight: 1.35, maxWidth: 480 }}>
          "You moved fast on code but skipped the reading block — second time this week. Trade something."
        </div>
        <div className="muted" style={{ fontSize: 11.5, marginTop: 10 }}>generated 14:02 · based on today's stream</div>
      </div>

      <div className="card cream" style={{ padding: 20 }}>
        <div className="h-card">This week, so far</div>
        <div className="tiny" style={{ marginTop: 2, marginBottom: 14 }}>by source · {events.length} events</div>

        {rows.length === 0 ? (
          <div className="muted" style={{ fontSize: 12.5 }}>No activity yet — is the backend running on :8080?</div>
        ) : (
          <div style={{ display:'flex', flexDirection:'column', gap: 10 }}>
            {rows.map((r, i) => {
              const Icon = r.icon;
              return (
                <div key={i} style={{ display:'flex', alignItems:'center', gap: 10 }}>
                  <Icon width="13" height="13" size={13}/>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ display:'flex', alignItems:'baseline', gap: 6 }}>
                      <span style={{ fontSize: 12.5, fontWeight: 600 }}>{r.label}</span>
                      <span className="muted" style={{ fontSize: 11.5 }}>· {r.count} event{r.count === 1 ? '' : 's'}</span>
                    </div>
                  </div>
                  <div style={{ width: 80, height: 4, borderRadius: 4, background:'rgba(14,14,14,0.08)' }}>
                    <div style={{ width:`${(r.count/max)*100}%`, height:'100%', background:'#0e0e0e', borderRadius: 4 }}/>
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

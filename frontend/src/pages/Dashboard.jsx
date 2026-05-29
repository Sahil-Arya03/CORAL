import React, { useState, useEffect } from 'react';
import { useUser } from '@clerk/clerk-react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';
import { Donut, Sparkline, BarChart } from '../components/atoms.jsx';
import { fetchTimeline } from '../api.js';

/* helpers */
const startOfDay = (d) => { const x = new Date(d); x.setHours(0,0,0,0); return x; };
const sameDay = (a, b) => startOfDay(a).getTime() === startOfDay(b).getTime();
const hhmm = (iso) => {
  const d = new Date(iso);
  return String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
};

const GREETINGS = {
  morning: [
    'Good morning',
    'Rise and shine',
    'Morning! Let\'s make it count',
    'Hey, good morning',
    'Fresh start — good morning',
    'Another day, another chance',
  ],
  afternoon: [
    'Good afternoon',
    'Afternoon — keep the momentum',
    'Hope the morning was productive',
    'Hey, good afternoon',
    'Halfway through — good afternoon',
    'Afternoon check-in',
  ],
  evening: [
    'Good evening',
    'Evening — time to wind down',
    'Hope the day treated you well',
    'Hey, good evening',
    'Evening — you made it',
    'Great work today — good evening',
  ],
};

function randomGreeting() {
  const h = new Date().getHours();
  const pool = h < 12 ? GREETINGS.morning : h < 17 ? GREETINGS.afternoon : GREETINGS.evening;
  return pool[Math.floor(Math.random() * pool.length)];
}

function todayLabel() {
  const d = new Date();
  const weekday = d.toLocaleDateString('en-US', { weekday: 'long' });
  const monthDay = d.toLocaleDateString('en-US', { month: 'long', day: 'numeric' });
  return `${weekday} · ${monthDay}`;
}

const SRC_META = {
  github:     { tone:'blue',   Icon: I.github,        label:'GitHub' },
  notion:     { tone:'lilac',  Icon: I.notion,        label:'Notion' },
  calendar:   { tone:'yellow', Icon: I.cal,           label:'Calendar' },
  gmail:      { tone:'green',  Icon: I.mail,          label:'Gmail' },
  reflection: { tone:'coral',  Icon: Doodles.Sparkle, label:'AI' },
};
const srcMeta = (s) => SRC_META[s] || { tone:'cream', Icon: I.doc, label: s || 'source' };

/* ─────────────────────────────────────────────────
   DASHBOARD
   ───────────────────────────────────────────────── */

export function DashboardPage() {
  const { user } = useUser();
  const displayName = user?.firstName || user?.username || user?.primaryEmailAddress?.emailAddress?.split('@')[0] || 'there';

  // Pick a random greeting from the current time-period pool on every page load.
  // Re-rolls every minute so it also rotates while the page stays open.
  const [currentGreeting, setCurrentGreeting] = useState(randomGreeting);
  const [currentDate,     setCurrentDate]     = useState(todayLabel);
  useEffect(() => {
    const id = setInterval(() => {
      setCurrentGreeting(randomGreeting());
      setCurrentDate(todayLabel());
    }, 60_000);
    return () => clearInterval(id);
  }, []);

  const [todayEvents, setTodayEvents] = useState([]);
  const [streamStatus, setStreamStatus] = useState('loading');

  useEffect(() => {
    let alive = true;
    fetchTimeline()
      .then(data => {
        if (!alive) return;
        const today = new Date();
        const todays = (Array.isArray(data) ? data : [])
          .filter(ev => sameDay(new Date(ev.occurredAt), today))
          .sort((a, b) => new Date(a.occurredAt) - new Date(b.occurredAt));
        setTodayEvents(todays);
        setStreamStatus('ok');
      })
      .catch(() => { if (alive) setStreamStatus('error'); });
    return () => { alive = false; };
  }, []);

  return (
    <div className="page" data-screen-label="Dashboard">
      {/* Header row */}
      <div style={{ display:'flex', alignItems:'flex-start', justifyContent:'space-between', marginBottom: 22 }}>
        <div>
          <div className="tiny" style={{ marginBottom: 10 }}>{currentDate}</div>
          <h1 className="h-page" style={{ margin: 0 }}>
            {currentGreeting},{' '}
            <span className="serif" style={{ fontWeight: 400, fontStyle:'italic' }}>{displayName}</span>
          </h1>
          <p className="muted" style={{ maxWidth: 580, margin:'10px 0 0', fontSize: 14 }}>
            CreatorOS read your last 48 hours. You have <b style={{color:'var(--ink)'}}>3 deep-work blocks</b> available
            today, and your <b style={{color:'var(--ink)'}}>commit streak is at 21 days</b>. Stay sharp.
          </p>
        </div>
        <div style={{ display:'flex', gap: 8, alignItems:'center' }}>
          <button className="btn light"><I.plus width="14" height="14"/> New task</button>
          <button className="btn"><I.spark width="14" height="14"/> Ask AI</button>
        </div>
      </div>

      {/* Row 1: AI Daily Brief (wide) + Productivity score + Streak */}
      <div className="dash-grid" style={{ marginBottom: 16 }}>
        <BriefCard/>
        <ProductivityCard/>
        <StreakCard/>
      </div>

      {/* Row 2: Today's stream + Recommendations + Deadlines */}
      <div style={{ display:'grid', gridTemplateColumns:'1.3fr 1fr 1fr', gap: 16, marginBottom: 16 }}>
        <TodayStream events={todayEvents} status={streamStatus}/>
        <RecommendationsCard/>
        <DeadlinesCard/>
      </div>

      {/* Row 3: Activity overview — full width */}
      <div style={{ marginBottom: 16 }}>
        <ActivityCard/>
      </div>

      {/* Row 4: Memory recall — full width, horizontal 4-column */}
      <MemoryCard/>
    </div>
  );
}

/* ───── AI Daily Brief ───── */

function BriefCard() {
  return (
    <div className="card pink brief-card" style={{ minHeight: 240, padding: 22 }}>
      <Doodles.Sparkle size={24} style={{ position:'absolute', top: 18, right: 22, opacity: 0.65 }}/>
      <Doodles.Squiggle w={70} style={{ position:'absolute', bottom: 18, right: 22, opacity: 0.5 }}/>

      <div style={{ display:'flex', alignItems:'center', gap: 8, marginBottom: 14 }}>
        <span className="pill dark" style={{ padding:'5px 12px' }}>
          <Doodles.Sparkle size={11} color="#f3f1ea"/> Daily brief
        </span>
        <span className="tiny" style={{ marginLeft: 4 }}>generated 06:14 AM</span>
      </div>

      <div style={{ fontSize: 22, fontWeight: 600, letterSpacing:'-0.015em', lineHeight: 1.3, maxWidth: 460 }}>
        You closed <span style={{ background:'rgba(255,255,255,0.55)', padding:'0 6px', borderRadius:6 }}>4 PRs</span> yesterday and
        deferred the <span style={{ background:'rgba(255,255,255,0.55)', padding:'0 6px', borderRadius:6 }}>thesis outline</span> twice.
        Start there before standup.
      </div>

      <div style={{ display:'flex', gap: 8, marginTop: 18, flexWrap:'wrap' }}>
        <span className="pill" style={{ background:'rgba(255,255,255,0.7)', borderColor:'transparent' }}>
          🧠 Memory: <b style={{ marginLeft: 3 }}>spring-2026/thesis</b>
        </span>
        <span className="pill" style={{ background:'rgba(255,255,255,0.7)', borderColor:'transparent' }}>
          📍 Best focus: 09:30 – 11:30
        </span>
      </div>

      <button className="btn" style={{ marginTop: 16 }}>
        Open brief <I.arrowR width="14" height="14"/>
      </button>
    </div>
  );
}

/* ───── Productivity Card ───── */

function ProductivityCard() {
  return (
    <div className="card yellow" style={{ padding: 20, position:'relative' }}>
      <Doodles.Star size={20} color="#0e0e0e" style={{ position:'absolute', top: 16, right: 18, opacity: 0.5 }}/>
      <div className="h-card">Productivity</div>
      <div className="tiny" style={{ marginTop: 4, marginBottom: 10 }}>this week</div>

      <Donut
        size={150} thickness={18}
        label="score" value="87"
        segments={[
          { v: 42, color: '#0e0e0e' },
          { v: 22, color: '#f4a58d' },
          { v: 16, color: '#b8d3f6' },
          { v: 20, color: 'rgba(14,14,14,0.12)' },
        ]}
      />

      <div style={{ display:'flex', justifyContent:'space-between', marginTop: 12, fontSize: 11.5 }}>
        <span><b>+12</b> vs last wk</span>
        <span className="muted">top 14%</span>
      </div>
    </div>
  );
}

/* ───── Streak / coding consistency ───── */

function StreakCard() {
  const bars = [3,4,2,5,6,4,7,5,8,6,7,9,8,10,7];
  return (
    <div className="card blue" style={{ padding: 20, position:'relative' }}>
      <Doodles.Lightning size={20} style={{ position:'absolute', top: 16, right: 18, opacity: 0.5 }}/>
      <div className="h-card">Code consistency</div>
      <div className="tiny" style={{ marginTop: 4 }}>commits · last 15 days</div>

      <div style={{ display:'flex', alignItems:'flex-end', justifyContent:'space-between', marginTop: 18 }}>
        <div>
          <div style={{ fontSize: 48, fontWeight: 700, letterSpacing:'-0.025em', lineHeight: 1 }}>21</div>
          <div className="tiny" style={{ marginTop: 4 }}>day streak 🔥</div>
        </div>
        <BarChart data={bars} h={70} gap={4}/>
      </div>

      <div style={{ display:'flex', alignItems:'center', gap: 6, marginTop: 16, fontSize: 11.5 }}>
        <I.github width="13" height="13"/>
        <span className="muted">alex/synthwave · main</span>
        <span style={{ marginLeft:'auto' }} className="pill ok">+8 today</span>
      </div>
    </div>
  );
}

/* ───── Today's stream (live from /api/timeline, falls back to static) ───── */

const STATIC_STREAM = [
  { occurredAt: '2026-05-27T06:14:00', source:'reflection', title:"Generated today's brief",   summary:'3 priorities · 1 nudge · 1 pause' },
  { occurredAt: '2026-05-27T09:20:00', source:'notion',     title:'Edited thesis §3.1',        summary:'+220 words · rescoped intro' },
  { occurredAt: '2026-05-27T13:02:00', source:'github',     title:'Review PR #214',            summary:'Maya · auth refactor · 2 comments' },
  { occurredAt: '2026-05-27T14:00:00', source:'reflection', title:'Mid-day check-in',          summary:'You are 1h ahead of plan' },
];

function TodayStream({ events, status }) {
  const stream = (status === 'ok' && events.length > 0) ? events.slice(0, 4) : STATIC_STREAM;

  return (
    <div className="card" style={{ padding: 20 }}>
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 14 }}>
        <div>
          <div className="h-card">Today's stream</div>
          <div className="tiny" style={{ marginTop: 2 }}>all sources merged</div>
        </div>
        <span className="pill">{stream.length} events</span>
      </div>

      <div style={{ position:'relative', paddingLeft: 80 }}>
        {/* spine */}
        <div style={{
          position:'absolute', left: 64, top: 14, bottom: 14,
          width: 2, background:'rgba(14,14,14,0.08)', borderRadius: 2,
        }}/>

        {stream.map((ev, i) => {
          const meta = srcMeta(ev.source);
          const Icon = meta.Icon;
          return (
            <div key={i} style={{ position:'relative', padding:'10px 0', display:'flex', alignItems:'center', gap: 14 }}>
              <div className="mono" style={{
                position:'absolute', left: -80, top: 14,
                width: 60, textAlign:'right', fontSize: 11, color:'var(--muted)',
              }}>{hhmm(ev.occurredAt)}</div>

              <div style={{
                position:'absolute', left: -16, top: 14,
                width: 22, height: 22, borderRadius:'50%',
                background:'#fff', border:'1.5px solid var(--hair)',
                display:'grid', placeItems:'center', flexShrink: 0,
              }}>
                <Icon size={11} width="11" height="11"/>
              </div>

              <div className={'card ' + meta.tone} style={{ padding:'10px 12px', flex: 1, borderRadius: 14 }}>
                <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
                  <span className="tiny" style={{ fontSize: 10 }}>{meta.label}</span>
                  <span style={{ fontSize: 13, fontWeight: 600 }}>{ev.title}</span>
                </div>
                <div className="muted" style={{ fontSize: 11.5, marginTop: 2 }}>{ev.summary}</div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

/* ───── AI Recommendations ───── */

function RecommendationsCard() {
  const recs = [
    { i:1, txt:'Block 09:30–11:30 for thesis. Your focus quality is 2.3× higher in the morning.', src:'pattern' },
    { i:2, txt:"You haven't reviewed open PRs from Maya in 4 days — 2 are blocking your sprint.", src:'context' },
    { i:3, txt:'Skip the 3pm sync? You marked the last 3 as low-value.', src:'memory' },
  ];

  return (
    <div className="card lilac" style={{ padding: 20, position:'relative' }}>
      <Doodles.Brain size={22} style={{ position:'absolute', top: 16, right: 18, opacity: 0.55 }}/>
      <div className="h-card">AI recommendations</div>
      <div className="tiny" style={{ marginTop: 2, marginBottom: 14 }}>based on your last 14 days</div>

      <div style={{ display:'flex', flexDirection:'column', gap: 10 }}>
        {recs.map(r => (
          <div key={r.i} style={{
            background:'rgba(255,255,255,0.55)',
            borderRadius: 14, padding:'11px 12px',
            display:'flex', gap: 10, alignItems:'flex-start'
          }}>
            <span style={{
              width: 22, height: 22, borderRadius: 7,
              background:'#0e0e0e', color:'#f3f1ea',
              display:'grid', placeItems:'center', fontSize: 11, fontWeight: 700, flexShrink: 0
            }}>{r.i}</span>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12.5, lineHeight: 1.4 }}>{r.txt}</div>
              <div className="tiny" style={{ marginTop: 6 }}>via {r.src}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ───── Deadlines ───── */

function DeadlinesCard() {
  const items = [
    { d:'May 30', t:'CS-401 problem set 6',    sub:'in 3 days',   tone:'warn' },
    { d:'Jun 04', t:'Thesis chapter 3 draft',  sub:'in 8 days',   tone:'rose' },
    { d:'Jun 12', t:'Synthwave v0.4 release',  sub:'in 16 days',  tone:'info' },
    { d:'Jun 20', t:'Internship demo day',     sub:'in 24 days',  tone:'lilac' },
  ];
  return (
    <div className="card green" style={{ padding: 20, position:'relative' }}>
      <Doodles.Asterisk size={20} style={{ position:'absolute', top: 16, right: 18, opacity: 0.55 }}/>
      <div className="h-card">Upcoming deadlines</div>
      <div className="tiny" style={{ marginTop: 2, marginBottom: 14 }}>next 30 days</div>

      <div style={{ display:'flex', flexDirection:'column', gap: 10 }}>
        {items.map((d, i) => (
          <div key={i} style={{ display:'flex', alignItems:'center', gap: 12 }}>
            <div style={{
              minWidth: 58, padding:'6px 8px', borderRadius: 10,
              background:'rgba(255,255,255,0.55)',
              textAlign:'center', flexShrink: 0
            }}>
              <div style={{ fontSize: 9, color:'var(--muted)', letterSpacing:'0.08em', textTransform:'uppercase' }}>
                {d.d.split(' ')[0]}
              </div>
              <div style={{ fontSize: 18, fontWeight: 700, letterSpacing:'-0.02em', lineHeight: 1 }}>
                {d.d.split(' ')[1]}
              </div>
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600 }}>{d.t}</div>
              <div className="muted" style={{ fontSize: 11.5 }}>{d.sub}</div>
            </div>
            <span className={'pill ' + d.tone} style={{ fontSize: 10.5 }}>•</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ───── Activity overview (full width, combined sources) ───── */

function ActivityCard() {
  const stats = [
    { label:'Focus hours',   value: 28.5, delta:'+3.2h', tone:'pink',   tag:'this wk' },
    { label:'Commits',       value: 47,   delta:'+8',    tone:'blue',   tag:'this wk' },
    { label:'Tasks shipped', value: 19,   delta:'+5',    tone:'yellow', tag:'this wk' },
    { label:'Notes captured',value: 32,   delta:'+11',   tone:'lilac',  tag:'this wk' },
  ];
  const trend = [22, 26, 18, 30, 24, 36, 32, 40, 34, 42, 38, 48, 44, 52];
  const focus = [16, 22, 20, 26, 18, 28, 30, 32, 28, 36, 32, 40, 38, 46];

  return (
    <div className="card" style={{ padding: 20 }}>
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 14 }}>
        <div>
          <div className="h-card">Activity overview</div>
          <div className="tiny" style={{ marginTop: 2 }}>across all your sources</div>
        </div>
        <div style={{ display:'flex', gap: 6 }}>
          <span className="pill"><I.github width="11" height="11"/> GitHub</span>
          <span className="pill"><I.notion width="11" height="11"/> Notion</span>
          <span className="pill"><I.cal width="11" height="11"/> Calendar</span>
        </div>
      </div>

      <div style={{ display:'grid', gridTemplateColumns:'repeat(4, 1fr)', gap: 10, marginBottom: 16 }}>
        {stats.map((s, i) => (
          <div key={i} className={'card ' + s.tone} style={{ padding: 14, borderRadius: 16 }}>
            <div className="tiny">{s.label}</div>
            <div style={{ display:'flex', alignItems:'baseline', gap: 6, marginTop: 4 }}>
              <span style={{ fontSize: 26, fontWeight: 700, letterSpacing:'-0.02em' }}>{s.value}</span>
              <span style={{ fontSize: 11, fontWeight: 500 }}>{s.delta}</span>
            </div>
            <div className="tiny" style={{ marginTop: 2 }}>{s.tag}</div>
          </div>
        ))}
      </div>

      <div style={{ background:'rgba(14,14,14,0.025)', borderRadius: 16, padding: 14 }}>
        <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 8 }}>
          <div className="tiny">Combined activity · 14d</div>
          <div style={{ display:'flex', alignItems:'center', gap: 6, fontSize: 11.5 }}>
            <span style={{ width: 8, height: 8, borderRadius: 50, background:'var(--coral)' }}/>
            <span>output</span>
            <span style={{ width: 8, height: 8, borderRadius: 50, background:'rgba(14,14,14,0.5)', marginLeft: 8 }}/>
            <span>focus</span>
          </div>
        </div>
        {/* Full-width dual-series chart */}
        <div style={{ width:'100%', height: 110 }}>
          <svg width="100%" height="100%" viewBox="0 0 1000 110" preserveAspectRatio="none" style={{ display:'block' }}>
            <FilledArea data={trend} w={1000} h={110} fill="rgba(244,165,141,0.22)" stroke="var(--coral)"/>
            <FilledArea data={focus} w={1000} h={110} fill="none" stroke="#0e0e0e" dash="4 4" yOffset={6}/>
          </svg>
        </div>
        <div style={{ display:'flex', justifyContent:'space-between', marginTop: 6, fontSize: 10, color:'var(--muted)' }}>
          <span>May 14</span><span>May 17</span><span>May 20</span><span>May 23</span><span>May 26</span><span>today</span>
        </div>
      </div>
    </div>
  );
}

function FilledArea({ data, w, h, fill, stroke, dash, yOffset = 0 }) {
  const max = Math.max(...data), min = Math.min(...data);
  const range = max - min || 1;
  const sx = w / (data.length - 1);
  const pts = data.map((v, i) => [i * sx, h - ((v - min) / range) * (h - 12) - 6 + yOffset]);
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const [x1, y1] = pts[i], [x2, y2] = pts[i+1];
    d += ` Q${x1},${y1} ${(x1+x2)/2},${(y1+y2)/2} T${x2},${y2}`;
  }
  return (
    <>
      {fill !== 'none' && <path d={`${d} L${w},${h} L0,${h} Z`} fill={fill}/>}
      <path d={d} fill="none" stroke={stroke} strokeWidth="2" strokeLinecap="round" strokeDasharray={dash || ''}/>
    </>
  );
}

/* ───── Memory recall (full width, 4-column horizontal) ───── */

function MemoryCard() {
  const items = [
    { ago:'2h', t:'Decided to defer the GraphQL migration',         src:'Notion · decisions', Icon: I.notion },
    { ago:'5h', t:'Promised Sarah the figma export by Friday',      src:'Slack · #design',    Icon: I.spark  },
    { ago:'1d', t:'Deep work after 10am works best',                src:'Journal entry',      Icon: Doodles.Brain },
    { ago:'2d', t:'Bookmarked "Designing data-intensive apps" ch.4',src:'Reader',             Icon: I.doc    },
  ];

  return (
    <div className="card ink" style={{ padding: 22, position:'relative', overflow:'hidden' }}>
      <Doodles.Burst size={120} color="#222" style={{ position:'absolute', right: -50, top: -50, opacity: 0.55, pointerEvents:'none' }}/>
      <Doodles.Squiggle w={120} color="#222" style={{ position:'absolute', left: 220, top: 22, opacity: 0.4, pointerEvents:'none' }}/>

      {/* Header */}
      <div style={{
        position:'relative', zIndex: 1,
        display:'flex', alignItems:'center', justifyContent:'space-between',
        marginBottom: 18, gap: 16,
      }}>
        <div>
          <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
            <span className="pulse"/>
            <span className="tiny" style={{ color:'#a6a39a' }}>Memory recall</span>
          </div>
          <div className="h-card" style={{ marginTop: 8, color:'#f3f1ea', fontSize: 18 }}>
            Things you said you'd remember
          </div>
          <div style={{ fontSize: 12, color:'#8a8780', marginTop: 4 }}>
            Recent decisions, intents and notes CreatorOS surfaces back when relevant.
          </div>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap: 8, flexShrink: 0 }}>
          <span className="pill" style={{ background:'#1a1a1a', color:'#c9c6bd', border:'1px solid #2a2a2a' }}>
            2,341 contexts
          </span>
          <button className="btn coral">
            Open memory <I.arrowR width="13" height="13"/>
          </button>
        </div>
      </div>

      {/* 4-column memory cards */}
      <div style={{
        position:'relative', zIndex: 1,
        display:'grid', gridTemplateColumns:'repeat(4, 1fr)', gap: 12,
      }}>
        {items.map((m, i) => {
          const Icon = m.Icon;
          return (
            <div key={i} style={{
              background:'#1a1a1a',
              border:'1px solid #232323',
              borderRadius: 16,
              padding: 14,
              display:'flex', flexDirection:'column', gap: 10,
              minHeight: 112,
            }}>
              <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between' }}>
                <div style={{
                  width: 26, height: 26, borderRadius: 8,
                  background:'#2a2a2a',
                  display:'grid', placeItems:'center',
                  color:'#c9c6bd',
                }}>
                  <Icon width="13" height="13" size={13} color="#c9c6bd"/>
                </div>
                <span className="mono" style={{
                  fontSize: 10.5, color:'#8a8780', letterSpacing:'0.06em',
                  padding:'3px 8px', borderRadius: 999,
                  background:'#0e0e0e', border:'1px solid #232323',
                }}>{m.ago} ago</span>
              </div>

              <div style={{ fontSize: 13, color:'#ececdf', lineHeight: 1.4, letterSpacing:'-0.005em', flex: 1 }}>
                {m.t}
              </div>

              <div style={{
                fontSize: 10.5, color:'#6e6c64',
                letterSpacing:'0.02em',
                paddingTop: 8, borderTop:'1px solid #232323',
              }}>
                {m.src}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
import React, { useState } from 'react';
import { useUser } from '@clerk/clerk-react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';
import { Sparkline, BarChart } from '../components/atoms.jsx';

/* date helpers */
const fmtShort = (d) => d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
function trendXLabels(rangeDays) {
  const today = new Date();
  const step  = Math.floor(rangeDays / 4);
  return Array.from({ length: 5 }, (_, i) => {
    const d = new Date(today);
    d.setDate(today.getDate() - (4 - i) * step);
    return fmtShort(d);
  });
}

export function AnalyticsPage() {
  const { user } = useUser();
  const firstName = user?.firstName || user?.username || 'your';
  const [range, setRange] = useState('30d');

  return (
    <div className="page" data-screen-label="Analytics">
      {/* Header */}
      <div style={{ display:'flex', alignItems:'flex-start', justifyContent:'space-between', marginBottom: 22 }}>
        <div>
          <div className="tiny" style={{ marginBottom: 8 }}>Analytics</div>
          <h1 className="h-page" style={{ margin: 0 }}>
            How your <span className="serif" style={{ fontWeight: 400 }}>focus</span> moves
          </h1>
          <p className="muted" style={{ margin:'10px 0 0', fontSize: 14, maxWidth: 580 }}>
            Patterns CreatorOS sees across your code, notes, and calendar. Soft signal — no vanity metrics.
          </p>
        </div>
        <div style={{ display:'flex', gap: 8, alignItems:'center' }}>
          <div className="seg">
            {['7d','30d','90d','1y'].map(r => (
              <button key={r} className={range === r ? 'active' : ''} onClick={() => setRange(r)}>{r}</button>
            ))}
          </div>
          <button className="btn ghost"><I.doc width="13" height="13"/> Export</button>
        </div>
      </div>

      {/* Row 1: KPI strip — compact tiles, no sparklines */}
      <div style={{ display:'grid', gridTemplateColumns:'repeat(4, 1fr)', gap: 16, marginBottom: 16 }}>
        <KpiCard tone="pink"   label="Focus hours"      value="112.4" unit="h"   delta="+18%" sub="vs prev 30d"/>
        <KpiCard tone="yellow" label="Deep-work blocks"  value="38"    unit=""    delta="+6"   sub="of 42 scheduled"/>
        <KpiCard tone="blue"   label="Commits"           value="184"   unit=""    delta="+22"  sub="streak 21 days"/>
        <KpiCard tone="lilac"  label="Avg session"       value="46"    unit="min" delta="−4m"  sub="shorter, denser"/>
      </div>

      {/* Row 2: Productivity trend — full width */}
      <div style={{ marginBottom: 16 }}>
        <TrendCard range={range}/>
      </div>

      {/* Row 3: Consistency calendar — full width */}
      <div style={{ marginBottom: 16 }}>
        <ConsistencyCard/>
      </div>

      {/* Row 4: Focus quality + By source */}
      <div style={{ display:'grid', gridTemplateColumns:'1.2fr 1fr', gap: 16 }}>
        <FocusQualityCard/>
        <BySourceCard/>
      </div>
    </div>
  );
}

/* ───── KPI tile — compact, delta pill top-right, no sparkline ───── */

function KpiCard({ tone, label, value, unit, delta, sub }) {
  const isNegative = delta && (delta.startsWith('−') || delta.startsWith('-'));
  return (
    <div className={'card ' + tone} style={{ padding:'14px 16px', position:'relative' }}>
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 4 }}>
        <div className="tiny">{label}</div>
        {delta && (
          <span style={{
            fontSize: 10.5, fontWeight: 600,
            padding:'2px 7px', borderRadius: 999,
            background:'rgba(255,255,255,0.55)',
            color: isNegative ? '#7a3a3a' : 'var(--ink)',
          }}>{delta}</span>
        )}
      </div>
      <div style={{ display:'flex', alignItems:'baseline', gap: 4 }}>
        <span style={{ fontSize: 30, fontWeight: 700, letterSpacing:'-0.025em', lineHeight: 1 }}>{value}</span>
        {unit && <span style={{ fontSize: 13, fontWeight: 500 }}>{unit}</span>}
      </div>
      <div className="muted" style={{ fontSize: 11, marginTop: 6 }}>{sub}</div>
    </div>
  );
}

/* ───── Productivity trend — full width, dual overlapping series ───── */

function TrendCard({ range }) {
  const rangeDays = range === '7d' ? 7 : range === '90d' ? 90 : range === '1y' ? 365 : 30;
  const xLabels   = trendXLabels(rangeDays);
  const focus  = [4,6,5,8,7,9,11,8,10,12,11,14,13,16,14,18,16,17,15,19,18,21,19,22,20,24,22,25,23,26];
  const output = [2,3,4,3,5,4,6,5,7,6,8,7,9,8,10,9,11,10,12,11,13,12,14,13,15,14,16,15,17,16];

  return (
    <div className="card" style={{ padding: 20 }}>
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 12 }}>
        <div>
          <div className="h-card">Productivity trend</div>
          <div className="tiny" style={{ marginTop: 2 }}>focus hours · daily output</div>
        </div>
        <div style={{ display:'flex', gap: 12, alignItems:'center', fontSize: 11.5 }}>
          <span style={{ display:'flex', alignItems:'center', gap: 6 }}>
            <span style={{ width: 10, height: 3, background:'#0e0e0e', borderRadius: 3 }}/> focus
          </span>
          <span style={{ display:'flex', alignItems:'center', gap: 6 }}>
            <span style={{ width: 10, height: 3, background:'var(--coral)', borderRadius: 3 }}/> output
          </span>
        </div>
      </div>

      <div style={{ background:'var(--cream)', borderRadius: 16, padding: 18 }}>
        <div style={{ position:'relative' }}>
          <div style={{
            position:'absolute', left: 0, top: 0, bottom: 24, width: 30,
            display:'flex', flexDirection:'column', justifyContent:'space-between',
            fontSize: 10, color:'var(--muted)',
          }}>
            <span>30h</span><span>20h</span><span>10h</span><span>0</span>
          </div>
          <div style={{ marginLeft: 36 }}>
            {/* Full-width dual-series chart using scalable viewBox */}
            <div style={{ width:'100%', height: 180 }}>
              <svg width="100%" height="180" viewBox="0 0 1000 180" preserveAspectRatio="none" style={{ display:'block' }}>
                <TrendArea data={focus}  w={1000} h={180} fill="rgba(14,14,14,0.05)"        stroke="#0e0e0e"/>
                <TrendArea data={output} w={1000} h={180} fill="rgba(244,165,141,0.16)"     stroke="var(--coral)"/>
              </svg>
            </div>
            <div style={{ display:'flex', justifyContent:'space-between', fontSize: 10, color:'var(--muted)', marginTop: 6 }}>
              {xLabels.map((l, i) => <span key={i}>{l}</span>)}
            </div>
          </div>
        </div>
      </div>

      <div style={{ display:'flex', gap: 10, marginTop: 12, fontSize: 11.5 }}>
        <span className="pill rose">↑ 23% focus vs prev. month</span>
        <span className="pill info">output → focus correlation 0.78</span>
      </div>
    </div>
  );
}

function TrendArea({ data, w, h, fill, stroke }) {
  const max = Math.max(...data), min = Math.min(...data);
  const range = max - min || 1;
  const sx = w / (data.length - 1);
  const pts = data.map((v, i) => [i * sx, h - ((v - min) / range) * (h - 14) - 7]);
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const [x1, y1] = pts[i], [x2, y2] = pts[i + 1];
    d += ` Q${x1},${y1} ${(x1+x2)/2},${(y1+y2)/2} T${x2},${y2}`;
  }
  return (
    <>
      {fill && <path d={`${d} L${w},${h} L0,${h} Z`} fill={fill}/>}
      <path d={d} fill="none" stroke={stroke} strokeWidth="2.5" strokeLinecap="round"/>
    </>
  );
}

/* ───── Consistency — proper May 2026 monthly calendar ───── */

// Fixture activity levels — any extra days beyond the array default to 0
const ACTIVITY_FIXTURE = [
  1,0,0,2,3,3, 2,1,1,1,3,4,3, 3,2,1,1,4,4,3,
  3,1,1,2,4,3,0, 0,0,0,0,
];

function ConsistencyCard() {
  const now          = new Date();
  const today        = now.getDate();
  const year         = now.getFullYear();
  const monthNum     = now.getMonth();
  const month        = now.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  const daysInMonth  = new Date(year, monthNum + 1, 0).getDate();
  // Monday-anchored offset: getDay() 0=Sun → map to Mon=0
  const firstDayOffset = (new Date(year, monthNum, 1).getDay() + 6) % 7;

  const activity = Array.from({ length: daysInMonth }, (_, i) =>
    ACTIVITY_FIXTURE[i] ?? 0
  );

  // Streak label: 21 days ending today
  const streakStart = new Date(now);
  streakStart.setDate(now.getDate() - 20);
  const streakSub = `${fmtShort(streakStart)} → today`;

  const cells = [];
  for (let i = 0; i < firstDayOffset; i++) cells.push({ pad: true });
  for (let d = 1; d <= daysInMonth; d++) cells.push({ d, level: activity[d - 1] });
  while (cells.length % 7 !== 0) cells.push({ pad: true });

  // pink scale: level 0 = rest, 1–3 = pink tints, 4 = ink
  const tone = ['transparent', '#fad4e1', '#f6c3d6', '#f4a58d', '#0e0e0e'];

  return (
    <div className="card" style={{ padding: 20, position:'relative' }}>
      {/* Header */}
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 18 }}>
        <div>
          <div className="h-card">Consistency</div>
          <div className="tiny" style={{ marginTop: 2 }}>daily activity · {month}</div>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
          <button className="icon-btn ghost"><I.chevL width="14" height="14"/></button>
          <span className="pill" style={{ background:'var(--cream)', borderColor:'#ece4ca', padding:'7px 14px', fontWeight: 600 }}>
            {month}
          </span>
          <button className="icon-btn ghost"><I.chevR width="14" height="14"/></button>
        </div>
      </div>

      <div style={{ display:'grid', gridTemplateColumns:'1fr 280px', gap: 18 }}>
        {/* Calendar grid */}
        <div>
          {/* Weekday header */}
          <div style={{ display:'grid', gridTemplateColumns:'repeat(7, 1fr)', gap: 8, marginBottom: 10 }}>
            {['MON','TUE','WED','THU','FRI','SAT','SUN'].map((w, i) => (
              <div key={i} style={{
                textAlign:'center',
                fontSize: 10.5, letterSpacing:'0.12em',
                color: i >= 5 ? 'var(--muted-2)' : 'var(--muted)',
                fontWeight: 600,
              }}>{w}</div>
            ))}
          </div>

          {/* Day cells */}
          <div style={{ display:'grid', gridTemplateColumns:'repeat(7, 1fr)', gap: 8 }}>
            {cells.map((c, i) => {
              if (c.pad) return <div key={i} style={{ aspectRatio:'1/1', minHeight: 56 }}/>;

              const isToday  = c.d === today;
              const isFuture = c.d > today;
              const isRest   = c.level === 0 && !isFuture && !isToday;
              const lvl = c.level;

              return (
                <div key={i} style={{
                  position:'relative',
                  aspectRatio:'1/1',
                  minHeight: 56,
                  borderRadius: 12,
                  padding: 8,
                  background: isToday  ? '#0e0e0e'
                            : isFuture ? 'transparent'
                            : isRest   ? 'rgba(14,14,14,0.04)'
                            : tone[lvl],
                  border: isFuture ? '1px dashed var(--hair)'
                        : '1px solid ' + (isToday ? '#0e0e0e' : 'transparent'),
                  color: isToday ? '#f3f1ea' : (isFuture ? 'var(--muted-2)' : 'var(--ink)'),
                  display:'flex', flexDirection:'column', justifyContent:'space-between',
                }}>
                  <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between' }}>
                    <span style={{ fontSize: 13, fontWeight: 700, letterSpacing:'-0.01em', lineHeight: 1 }}>{c.d}</span>
                    {isToday && <span className="pulse" style={{ width: 7, height: 7 }}/>}
                  </div>

                  {/* Activity dots at bottom */}
                  {!isFuture && !isToday && (
                    <div style={{ display:'flex', gap: 3 }}>
                      {[0,1,2,3].map(di => (
                        <span key={di} style={{
                          width: 4, height: 4, borderRadius: 50,
                          background: di < lvl
                            ? (lvl === 4 ? '#f3f1ea' : 'rgba(14,14,14,0.7)')
                            : (lvl === 4 ? 'rgba(255,255,255,0.3)' : 'rgba(14,14,14,0.15)'),
                        }}/>
                      ))}
                    </div>
                  )}
                  {isToday && (
                    <span style={{ fontSize: 8, color:'#a6a39a', letterSpacing:'0.1em', lineHeight: 1 }}>
                      TODAY · LIVE
                    </span>
                  )}
                </div>
              );
            })}
          </div>

          {/* Legend */}
          <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginTop: 14, fontSize: 11.5 }}>
            <div style={{ display:'flex', alignItems:'center', gap: 10 }}>
              <span className="muted">Activity</span>
              <div style={{ display:'flex', gap: 4 }}>
                {tone.map((t, i) => (
                  <div key={i} style={{
                    width: 14, height: 14, borderRadius: 4,
                    background: t === 'transparent' ? 'rgba(14,14,14,0.06)' : t,
                  }}/>
                ))}
              </div>
              <span className="muted">none → peak</span>
            </div>
            <span className="muted">Rest days in faint grey</span>
          </div>
        </div>

        {/* Side stats */}
        <div style={{ display:'flex', flexDirection:'column', gap: 10 }}>
          <SummaryStat label="Current streak" value="21"  unit="days"  sub={streakSub}/>
          <SummaryStat label="Days active"    value="24"  unit={`of ${today}`} sub="this month"/>
          <SummaryStat label="Daily avg"      value="3.8" unit="h"     sub="focus + code"/>
          <SummaryStat label="Best day"       value="Tue" unit=""      sub="4.6h avg focus"/>
        </div>
      </div>

      {/* AI nudge band */}
      <div style={{
        marginTop: 14, padding: 14,
        background:'rgba(244,165,141,0.12)', borderRadius: 14,
        display:'flex', gap: 12, alignItems:'flex-start',
      }}>
        <Doodles.Sparkle size={18} style={{ flexShrink: 0, marginTop: 2 }}/>
        <div style={{ fontSize: 13, lineHeight: 1.5 }}>
          <b>21 days unbroken.</b> Your most consistent slot is <b>Tuesday 09:30–11:30</b>. Friday is your usual rest day — protect it.
        </div>
      </div>
    </div>
  );
}

function SummaryStat({ label, value, unit, sub }) {
  return (
    <div style={{ background:'var(--cream)', borderRadius: 12, padding:'10px 14px', border:'1px solid #ece4ca' }}>
      <div className="tiny" style={{ marginBottom: 2 }}>{label}</div>
      <div style={{ display:'flex', alignItems:'baseline', gap: 4 }}>
        <span style={{ fontSize: 22, fontWeight: 700, letterSpacing:'-0.02em' }}>{value}</span>
        {unit && <span style={{ fontSize: 11.5, fontWeight: 500 }}>{unit}</span>}
      </div>
      <div className="muted" style={{ fontSize: 10.5, marginTop: 1 }}>{sub}</div>
    </div>
  );
}

/* ───── Focus quality by hour ───── */

function FocusQualityCard() {
  // Stable values — no Math.random() so the chart doesn't flicker on re-render
  const hours = [5,5,5,5,5,5,5,32,38,90,92,88,35,66,64,52,54,52,34,34,72,74,70,5];

  return (
    <div className="card" style={{ padding: 20 }}>
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 14 }}>
        <div>
          <div className="h-card">Focus quality by hour</div>
          <div className="tiny" style={{ marginTop: 2 }}>last 30 days, normalized</div>
        </div>
        <span className="pill rose">peak 09:00 – 11:00</span>
      </div>

      <div style={{ background:'var(--cream)', borderRadius: 16, padding: 18 }}>
        <div style={{ display:'flex', alignItems:'flex-end', gap: 3, height: 140 }}>
          {hours.map((h, i) => {
            const isPeak = i >= 9 && i <= 11;
            return (
              <div key={i} style={{ flex: 1, display:'flex', flexDirection:'column', alignItems:'center' }}>
                <div style={{
                  width:'100%',
                  height: `${h}%`,
                  background: isPeak ? 'var(--coral)' : '#0e0e0e',
                  opacity: isPeak ? 1 : (h > 60 ? 0.85 : 0.35),
                  borderRadius: 4,
                  minHeight: 4,
                }}/>
              </div>
            );
          })}
        </div>
        <div style={{ display:'flex', justifyContent:'space-between', marginTop: 8, fontSize: 10, color:'var(--muted)' }}>
          <span>00</span><span>06</span><span>12</span><span>18</span><span>24</span>
        </div>
      </div>
    </div>
  );
}

/* ───── By source ───── */

function BySourceCard() {
  return (
    <div className="card" style={{ padding: 20 }}>
      <div className="h-card">By source</div>
      <div className="tiny" style={{ marginTop: 2, marginBottom: 14 }}>where your output comes from</div>

      <div style={{ display:'flex', flexDirection:'column', gap: 14 }}>
        {[
          { i: I.github,        l:'GitHub',   bars:[6,8,5,9,12,7,11,9,14,10,13,12], c:'var(--blue)',  total:'184 commits' },
          { i: I.notion,        l:'Notion',   bars:[3,4,2,5,3,4,6,5,4,7,5,8],       c:'var(--lilac)', total:'62 edits' },
          { i: I.cal,           l:'Calendar', bars:[2,3,3,4,2,3,4,3,5,3,4,3],       c:'var(--yellow)',total:'38 events' },
          { i: Doodles.Sparkle, l:'AI',       bars:[4,5,4,6,5,7,6,8,7,9,8,10],      c:'var(--pink)',  total:'76 sessions' },
        ].map((s, i) => (
          <div key={i}>
            <div style={{ display:'flex', alignItems:'center', gap: 8, marginBottom: 6 }}>
              <s.i width="13" height="13" size={13}/>
              <span style={{ fontSize: 12.5, fontWeight: 600 }}>{s.l}</span>
              <span className="muted" style={{ fontSize: 11.5, marginLeft:'auto' }}>{s.total}</span>
            </div>
            <BarChart data={s.bars} h={42} color={s.c} w={true} gap={3}/>
          </div>
        ))}
      </div>
    </div>
  );
}
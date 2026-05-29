import React from 'react';
import { useUser } from '@clerk/clerk-react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';
import { Donut } from '../components/atoms.jsx';

/* ─────────────────────────────────────────────────
   Service icons — small inline SVGs per brand
   ───────────────────────────────────────────────── */

const Svc = {
  github: ({ size = 22 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="#0e0e0e">
      <path d="M12 2C6.48 2 2 6.58 2 12.25c0 4.52 2.87 8.36 6.84 9.71.5.1.68-.22.68-.49 0-.24-.01-.88-.01-1.73-2.78.62-3.37-1.36-3.37-1.36-.45-1.18-1.11-1.49-1.11-1.49-.91-.63.07-.62.07-.62 1 .07 1.53 1.05 1.53 1.05.89 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.63-1.37-2.22-.26-4.56-1.14-4.56-5.07 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.27 2.75 1.05.8-.23 1.65-.34 2.5-.34s1.7.11 2.5.34c1.91-1.32 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.94-2.34 4.81-4.57 5.06.36.32.68.94.68 1.9 0 1.37-.01 2.48-.01 2.82 0 .27.18.6.69.49C19.14 20.6 22 16.76 22 12.25 22 6.58 17.52 2 12 2z"/>
    </svg>
  ),

  notion: ({ size = 22 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="#0e0e0e">
      <path d="M4.46 4.31C5.06 4.83 5.31 4.78 6.43 4.71l10.56-.63c.23 0 .04-.23-.04-.26L15.2 2.56c-.34-.27-.79-.57-1.66-.5L3.32 2.83c-.37.04-.45.23-.31.38l1.45 1.1zM4.7 5.94v11.13c0 .6.3.82.97.79l11.61-.67c.67-.04.75-.45.75-.94V4.65c0-.49-.19-.75-.6-.71L5.21 4.64c-.45.04-.51.27-.51.79zm11.42.94c.07.34 0 .68-.34.72l-.56.11v8.21c-.49.27-.94.41-1.32.41-.6 0-.75-.19-1.21-.75l-3.71-5.82v5.62l1.16.27s0 .68-.94.68l-2.59.15c-.07-.15 0-.49.26-.56l.68-.19V8.5l-.94-.07c-.07-.41.11-.83.6-.86l2.78-.19 3.86 5.91V8.05l-.97-.11c-.07-.41.23-.71.6-.75l2.6-.31z"/>
    </svg>
  ),

  gcal: ({ size = 22 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <rect x="3" y="3" width="18" height="18" rx="3" fill="#fff" stroke="#0e0e0e" strokeWidth="1.4"/>
      <text x="12" y="16.5" textAnchor="middle" fontSize="9" fontFamily="Space Grotesk" fontWeight="700" fill="#4285F4">31</text>
    </svg>
  ),

  gmail: ({ size = 22 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="#0e0e0e" strokeWidth="1.6">
      <rect x="3" y="5" width="18" height="14" rx="2"/>
      <path d="M3 7l9 6 9-6" strokeLinecap="round"/>
    </svg>
  ),

  spotify: ({ size = 22 }) => (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="#1DB954" strokeWidth="1.8">
      <circle cx="12" cy="12" r="9"/>
      <path d="M7 9.5c4-1.5 8-1 11 1M7.5 13c3-1 6.5-.5 9 1M8 16c2.5-.8 5-.4 7 .8" strokeLinecap="round"/>
    </svg>
  ),
};

/* ─────────────────────────────────────────────────
   PAGE
   ───────────────────────────────────────────────── */

export function IntegrationsPage() {
  const { user } = useUser();
  const userEmail  = user?.primaryEmailAddress?.emailAddress || 'your account';
  const userHandle = user?.username || user?.firstName || userEmail.split('@')[0];

  return (
    <div className="page" data-screen-label="Integrations">
      {/* Header */}
      <div style={{ display:'flex', alignItems:'flex-start', justifyContent:'space-between', marginBottom: 22, gap: 24 }}>
        <div>
          <div className="tiny" style={{ marginBottom: 10 }}>Workspace · Integrations</div>
          <h1 className="h-page" style={{ margin: 0 }}>
            Your <span className="serif" style={{ fontWeight: 400 }}>connected</span> world
          </h1>
          <p className="muted" style={{ maxWidth: 580, margin:'10px 0 0', fontSize: 14 }}>
            CreatorOS reads from <b style={{ color:'var(--ink)' }}>4 sources</b> right now —
            every email, doc, event and listening session feeds the same memory.
          </p>
        </div>
        <div style={{ display:'flex', gap: 8, alignItems:'center', flexShrink: 0 }}>
          <button className="btn light"><I.refresh width="14" height="14"/> Sync now</button>
          <button className="btn"><I.gear width="14" height="14"/> Manage</button>
        </div>
      </div>

      {/* Pipeline hero + Sync health */}
      <div style={{ display:'grid', gridTemplateColumns:'1.6fr 1fr', gap: 16, marginBottom: 16 }}>
        <PipelineCard/>
        <SyncHealthCard/>
      </div>

      {/* Connected list + Sync activity */}
      <div style={{ display:'grid', gridTemplateColumns:'1.5fr 1fr', gap: 16, marginBottom: 16 }}>
        <ConnectedCard userEmail={userEmail} userHandle={userHandle}/>
        <RecentSyncCard/>
      </div>

      {/* Developer / API — full width dark card */}
      <ApiCard/>
    </div>
  );
}

/* ─────────────────────────────────────────────────
   Pipeline visualization
   ───────────────────────────────────────────────── */

function PipelineCard() {
  const sources = [
    { Icon: Svc.gmail,   c:'var(--cream)',   label:'Gmail' },
    { Icon: Svc.notion,  c:'var(--blue-2)',  label:'Notion' },
    { Icon: Svc.gcal,    c:'var(--green-2)', label:'Google Calendar' },
    { Icon: Svc.spotify, c:'var(--lilac-2)', label:'Spotify' },
  ];

  return (
    <div className="card pink" style={{ padding: 22, position:'relative', overflow:'hidden', minHeight: 260 }}>
      <Doodles.Burst size={120} color="#0e0e0e" style={{ position:'absolute', right: -40, top: -40, opacity: 0.25 }}/>
      <Doodles.Squiggle w={70} style={{ position:'absolute', bottom: 18, right: 22, opacity: 0.45 }}/>

      <div style={{ display:'flex', alignItems:'center', gap: 8, marginBottom: 14 }}>
        <span className="pill dark" style={{ padding:'5px 12px' }}>
          <Doodles.Sparkle size={11} color="#f3f1ea"/> Pipeline
        </span>
      </div>

      <div style={{ fontSize: 22, fontWeight: 600, letterSpacing:'-0.015em', lineHeight: 1.3, maxWidth: 460 }}>
        <span style={{ background:'rgba(255,255,255,0.55)', padding:'0 6px', borderRadius: 6 }}>586 events</span>
        {' '}from 4 sources flowed into your memory this week.
      </div>

      {/* Pipeline diagram: bubbles → curves → memory node */}
      <div style={{
        marginTop: 22,
        display:'grid',
        gridTemplateColumns:'150px 1fr auto',
        alignItems:'center',
        gap: 12,
        position:'relative', zIndex: 1,
      }}>
        {/* Source bubbles */}
        <div style={{ display:'flex', flexDirection:'column', gap: 6 }}>
          {sources.map((s, i) => (
            <div key={i} style={{
              display:'flex', alignItems:'center', gap: 8,
              background: s.c, padding:'5px 10px 5px 5px',
              borderRadius: 999, fontSize: 11.5, fontWeight: 500,
              border:'1px solid rgba(14,14,14,0.06)', width:'100%',
            }}>
              <span style={{
                width: 22, height: 22, borderRadius: 50,
                background:'#fff', display:'grid', placeItems:'center', flexShrink: 0,
              }}>
                <s.Icon size={13}/>
              </span>
              {s.label}
            </div>
          ))}
        </div>

        {/* Connector SVG */}
        <svg viewBox="0 0 200 164" preserveAspectRatio="none"
             style={{ width:'100%', height: 164, display:'block' }}>
          {[22, 62, 102, 142].map((y, i) => (
            <path key={i}
              d={`M0 ${y} C 70 ${y}, 130 82, 200 82`}
              stroke="#0e0e0e" strokeWidth="1.2" fill="none"
              strokeDasharray="3 3" opacity="0.4"
              vectorEffect="non-scaling-stroke"
            />
          ))}
          <circle cx="200" cy="82" r="3" fill="#0e0e0e"/>
        </svg>

        {/* Memory node */}
        <div style={{
          background:'#0e0e0e', color:'#f3f1ea',
          borderRadius: 18, padding:'14px 16px',
          minWidth: 140,
          boxShadow:'0 10px 30px -10px rgba(14,14,14,0.4)',
        }}>
          <div style={{ display:'flex', alignItems:'center', gap: 6, marginBottom: 6 }}>
            <span className="pulse"/>
            <span className="tiny" style={{ color:'#a6a39a' }}>memory</span>
          </div>
          <div style={{ fontSize: 24, fontWeight: 700, letterSpacing:'-0.02em', lineHeight: 1 }}>
            2,341
          </div>
          <div style={{ fontSize: 10.5, color:'#8a8780', marginTop: 4 }}>contexts indexed</div>
        </div>
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────────
   Sync health donut
   ───────────────────────────────────────────────── */

function SyncHealthCard() {
  const sources = [
    { label:'Gmail',           color:'#f4a58d' },
    { label:'Notion',          color:'#b8d3f6' },
    { label:'Google Calendar', color:'#c7e08f' },
    { label:'Spotify',         color:'#d8cbf2' },
  ];

  return (
    <div className="card yellow" style={{ padding: 20, position:'relative' }}>
      <Doodles.Star size={20} style={{ position:'absolute', top: 16, right: 18, opacity: 0.5 }}/>
      <div className="h-card">Sync health</div>
      <div className="tiny" style={{ marginTop: 4, marginBottom: 6 }}>all integrations · 24h</div>

      <Donut
        size={140} thickness={16}
        label="syncing" value="4/4"
        segments={sources.map(s => ({ v: 1, color: s.color }))}
      />

      <div style={{ display:'flex', flexDirection:'column', gap: 6, marginTop: 14, fontSize: 11.5 }}>
        {sources.map((s, i) => (
          <div key={i} style={{ display:'flex', alignItems:'center', gap: 8 }}>
            <span style={{ width: 8, height: 8, borderRadius: 50, background: s.color, flexShrink: 0 }}/>
            <span>{s.label}</span>
            <span className="muted" style={{ marginLeft:'auto', fontSize: 11 }}>on schedule</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────────
   Connected integrations list
   ───────────────────────────────────────────────── */

function ConnectedCard({ userEmail, userHandle }) {
  const items = [
    { Icon: Svc.gmail,   name:'Gmail',           acct: userEmail,                              status:'ok', sync:'2m ago',  items:'1,240 threads', tone:'cream' },
    { Icon: Svc.notion,  name:'Notion',          acct:'spring-2026 workspace',                 status:'ok', sync:'8m ago',  items:'186 docs',       tone:'blue'  },
    { Icon: Svc.gcal,    name:'Google Calendar', acct: userEmail,                              status:'ok', sync:'14m ago', items:'62 events',      tone:'green' },
    { Icon: Svc.spotify, name:'Spotify',         acct:`${userHandle} · listening history`,     status:'ok', sync:'live',    items:'184 tracks',     tone:'lilac' },
  ];

  const StatusPill = ({ s }) => {
    if (s === 'ok')       return <span className="pill ok">live</span>;
    if (s === 'throttle') return <span className="pill warn">throttled</span>;
    return <span className="pill" style={{ background:'rgba(14,14,14,0.06)' }}>paused</span>;
  };

  return (
    <div className="card" style={{ padding: 20 }}>
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 14 }}>
        <div>
          <div className="h-card">Connected</div>
          <div className="tiny" style={{ marginTop: 2 }}>4 sources · feeding memory</div>
        </div>
        <button className="btn ghost">Manage all <I.chevR width="13" height="13"/></button>
      </div>

      <div style={{ display:'flex', flexDirection:'column', gap: 8 }}>
        {items.map((it, i) => (
          <div key={i} className={'task-row ' + it.tone} style={{ alignItems:'center' }}>
            <span style={{
              width: 36, height: 36, borderRadius: 12,
              background:'#fff', border:'1px solid rgba(14,14,14,0.06)',
              display:'grid', placeItems:'center', flexShrink: 0,
            }}>
              <it.Icon size={18}/>
            </span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
                <span style={{ fontWeight: 600, fontSize: 13.5 }}>{it.name}</span>
                <StatusPill s={it.status}/>
              </div>
              <div className="muted" style={{ fontSize: 11.5, marginTop: 2 }}>{it.acct}</div>
            </div>
            <div style={{ textAlign:'right', minWidth: 110, flexShrink: 0 }}>
              <div style={{ fontSize: 12, fontWeight: 600 }}>{it.items}</div>
              <div className="tiny" style={{ marginTop: 2 }}>last · {it.sync}</div>
            </div>
            <button className="icon-btn ghost" style={{ width: 30, height: 30 }}>
              <I.more width="14" height="14"/>
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────────
   Recent sync activity stream
   ───────────────────────────────────────────────── */

function RecentSyncCard() {
  const events = [
    { Icon: Svc.gmail,   ago:'just now', t:'Pulled 3 new threads',           sub:'inbox · starred',              tone:'pink-2'  },
    { Icon: Svc.spotify, ago:'1m',       t:'Logged 42-min focus session',    sub:'lo-fi beats · deep work',      tone:'lilac-2' },
    { Icon: Svc.notion,  ago:'2m',       t:'Indexed "Thesis ch. 3"',         sub:'spring-2026 / drafts',         tone:'blue-2'  },
    { Icon: Svc.gcal,    ago:'14m',      t:'Synced 2 new events',            sub:'CS-401 · office hours',        tone:'green-2' },
    { Icon: Svc.gmail,   ago:'1h',       t:'Captured commitment to Sarah',   sub:'figma export by Friday',       tone:'pink-2'  },
    { Icon: Svc.spotify, ago:'2h',       t:'Top genre this week',            sub:'ambient · 8.4 hrs listened',   tone:'lilac-2' },
  ];

  return (
    <div className="card lilac" style={{ padding: 20, position:'relative' }}>
      <Doodles.Wave w={60} style={{ position:'absolute', top: 22, right: 22, opacity: 0.5 }}/>

      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom: 10 }}>
        <div>
          <div className="h-card">Sync activity</div>
          <div className="tiny" style={{ marginTop: 2 }}>live · last 3 hours</div>
        </div>
        <span className="pill dark" style={{ padding:'4px 10px' }}>
          <span className="pulse" style={{ width: 6, height: 6 }}/> streaming
        </span>
      </div>

      <div style={{ position:'relative', display:'flex', flexDirection:'column', gap: 10, paddingLeft: 18, marginTop: 8 }}>
        {/* timeline rail */}
        <span style={{
          position:'absolute', left: 8, top: 14, bottom: 14,
          width: 1.5, background:'rgba(14,14,14,0.18)',
        }}/>

        {events.map((e, i) => (
          <div key={i} style={{ position:'relative', display:'flex', alignItems:'flex-start', gap: 12 }}>
            <span style={{
              position:'absolute', left: -14, top: 8,
              width: 9, height: 9, borderRadius: 50,
              background:'#0e0e0e',
              boxShadow:'0 0 0 3px var(--lilac)',
            }}/>
            <span style={{
              width: 26, height: 26, borderRadius: 9,
              background:`var(--${e.tone})`,
              display:'grid', placeItems:'center', flexShrink: 0,
            }}>
              <e.Icon size={14}/>
            </span>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display:'flex', alignItems:'baseline', gap: 8 }}>
                <span style={{ fontSize: 12.5, fontWeight: 600 }}>{e.t}</span>
                <span className="tiny" style={{ marginLeft:'auto' }}>{e.ago}</span>
              </div>
              <div className="muted" style={{ fontSize: 11, marginTop: 2 }}>{e.sub}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ─────────────────────────────────────────────────
   Developer / API card — full-width dark
   ───────────────────────────────────────────────── */

function ApiCard() {
  const keys = [
    { name:'personal-cli',    key:'cos_live_•••••••••••••8a2f', last:'17m ago', scope:'memory:read, tasks:write' },
    { name:'home-automation', key:'cos_live_•••••••••••••3df1', last:'2d ago',  scope:'events:read' },
  ];

  return (
    <div className="card ink" style={{ padding: 22, position:'relative', overflow:'hidden' }}>
      <Doodles.Burst size={120} color="#222" style={{ position:'absolute', right: -50, top: -50, opacity: 0.55 }}/>
      <Doodles.Squiggle w={120} color="#222" style={{ position:'absolute', left: 240, top: 22, opacity: 0.4 }}/>

      {/* Header */}
      <div style={{
        position:'relative', zIndex: 1,
        display:'flex', alignItems:'flex-start', justifyContent:'space-between', gap: 16, marginBottom: 18,
      }}>
        <div>
          <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
            <I.code width="14" height="14"/>
            <span className="tiny" style={{ color:'#a6a39a' }}>Developer</span>
          </div>
          <div className="h-card" style={{ marginTop: 8, color:'#f3f1ea', fontSize: 18 }}>
            Build on top of your memory
          </div>
          <div style={{ fontSize: 12, color:'#8a8780', marginTop: 4, maxWidth: 480 }}>
            API keys, webhooks and an MCP endpoint — wire CreatorOS into your own scripts and agents.
          </div>
        </div>
        <div style={{ display:'flex', gap: 8, flexShrink: 0 }}>
          <button className="btn light">View docs</button>
          <button className="btn coral">
            New API key <I.arrowR width="13" height="13"/>
          </button>
        </div>
      </div>

      {/* Three sub-cards */}
      <div style={{ position:'relative', zIndex: 1, display:'grid', gridTemplateColumns:'2fr 1fr 1fr', gap: 12 }}>
        {/* Active keys */}
        <div style={{ background:'#1a1a1a', border:'1px solid #232323', borderRadius: 16, padding: 14 }}>
          <div className="tiny" style={{ color:'#8a8780', marginBottom: 10 }}>Active keys</div>
          <div style={{ display:'flex', flexDirection:'column', gap: 8 }}>
            {keys.map((k, i) => (
              <div key={i} style={{
                display:'flex', alignItems:'center', gap: 10,
                padding:'8px 10px',
                background:'#0e0e0e', borderRadius: 10,
                border:'1px solid #232323',
              }}>
                <span style={{
                  width: 8, height: 8, borderRadius: 50, background:'var(--coral)',
                  boxShadow:'0 0 0 3px rgba(244,165,141,0.15)', flexShrink: 0,
                }}/>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
                    <span style={{ fontSize: 12.5, fontWeight: 600, color:'#ececdf' }}>{k.name}</span>
                    <span className="mono" style={{ fontSize: 10.5, color:'#8a8780' }}>{k.key}</span>
                  </div>
                  <div style={{ fontSize: 10.5, color:'#6e6c64', marginTop: 2 }}>
                    {k.scope} · used {k.last}
                  </div>
                </div>
                <button style={{
                  background:'transparent', border:'1px solid #2a2a2a', color:'#c9c6bd',
                  padding:'4px 10px', borderRadius: 999, fontSize: 11,
                }}>Revoke</button>
              </div>
            ))}
          </div>
        </div>

        {/* Webhook */}
        <div style={{ background:'#1a1a1a', border:'1px solid #232323', borderRadius: 16, padding: 14, display:'flex', flexDirection:'column', gap: 8 }}>
          <div className="tiny" style={{ color:'#8a8780' }}>Webhook</div>
          <div className="mono" style={{
            fontSize: 11, color:'#ececdf',
            padding:'8px 10px', background:'#0e0e0e',
            borderRadius: 10, border:'1px solid #232323',
            wordBreak:'break-all', lineHeight: 1.4,
          }}>
            https://hooks.creatoros.app/<br/>w/9f4a-2b81-•••
          </div>
          <div style={{ display:'flex', alignItems:'center', gap: 6, fontSize: 11, color:'#8a8780' }}>
            <span className="pulse" style={{ width: 6, height: 6 }}/>
            <span>delivering · 312 events / 24h</span>
          </div>
        </div>

        {/* MCP endpoint */}
        <div style={{ background:'#1a1a1a', border:'1px solid #232323', borderRadius: 16, padding: 14, display:'flex', flexDirection:'column', gap: 8 }}>
          <div style={{ display:'flex', alignItems:'center', gap: 6 }}>
            <span className="tiny" style={{ color:'#8a8780' }}>MCP endpoint</span>
            <span className="pill ok" style={{ fontSize: 9.5, padding:'2px 7px' }}>beta</span>
          </div>
          <div className="mono" style={{
            fontSize: 11, color:'#ececdf',
            padding:'8px 10px', background:'#0e0e0e',
            borderRadius: 10, border:'1px solid #232323',
            wordBreak:'break-all', lineHeight: 1.4,
          }}>
            mcp://memory.creatoros<br/>.app/alex
          </div>
          <div style={{ fontSize: 11, color:'#8a8780' }}>
            Connect Claude, Cursor or your own agent.
          </div>
        </div>
      </div>
    </div>
  );
}
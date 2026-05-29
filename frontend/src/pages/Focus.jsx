import React, { useState, useEffect } from 'react';
import { useUser } from '@clerk/clerk-react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';

/* ─────────────────────────────────────────────────
   FOCUS MODE
   Cinematic, distraction-free, ambient gradients
   ───────────────────────────────────────────────── */

export function FocusPage() {
  const { user } = useUser();
  const firstName = user?.firstName || user?.username || '';

  const [running, setRunning]   = useState(true);
  const [seconds, setSeconds]   = useState(0);
  const [target]                = useState(90 * 60);
  const [task, setTask]         = useState('');
  const [editingTask, setEditingTask] = useState(false);

  // Record the exact time focus mode was entered
  const [startTime]             = useState(() => {
    const d = new Date();
    return String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0');
  });

  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => setSeconds(s => s + 1), 1000);
    return () => clearInterval(t);
  }, [running]);

  const mm = String(Math.floor(seconds / 60)).padStart(2,'0');
  const ss = String(seconds % 60).padStart(2,'0');
  const pct = Math.min(100, (seconds / target) * 100);

  return (
    <div className="page" data-screen-label="Focus mode" style={{ padding: 0, position:'relative', overflow:'hidden', minHeight: 'calc(100vh - 36px - 78px)' }}>
      {/* Ambient gradient stage */}
      <div className="focus-stage">
        <div className="focus-content">
          {/* Floating quiet header */}
          <div style={{
            position:'absolute', top: 28, left: 28, right: 28,
            display:'flex', alignItems:'center', justifyContent:'space-between',
          }}>
            <div style={{ display:'flex', alignItems:'center', gap: 10 }}>
              <span className="pulse"/>
              <span className="tiny" style={{ color: 'var(--ink)' }}>Focus mode active</span>
              <span className="muted" style={{ fontSize: 11.5 }}>· notifications muted · macOS DND on</span>
            </div>
            <div style={{ display:'flex', gap: 8 }}>
              <button className="btn light" style={{ padding:'7px 12px', fontSize: 12 }}>End session</button>
            </div>
          </div>

          {/* Top hand-drawn doodles for warmth */}
          <Doodles.Sparkle size={32} color="#0e0e0e" style={{ position:'absolute', top: 120, left: '12%', opacity: 0.35 }}/>
          <Doodles.Heart size={24} color="#0e0e0e" style={{ position:'absolute', top: 180, right: '18%', opacity: 0.32 }}/>
          <Doodles.Squiggle w={120} color="#0e0e0e" style={{ position:'absolute', bottom: 140, left: '8%', opacity: 0.28 }}/>
          <Doodles.Asterisk size={28} color="#0e0e0e" style={{ position:'absolute', bottom: 180, right: '12%', opacity: 0.35 }}/>

          <div className="eyebrow">
            Deep work{firstName ? ` · ${firstName}` : ''}
          </div>
          <h1 style={{
            fontSize: 76, fontWeight: 700, letterSpacing:'-0.03em', lineHeight: 1,
            margin: 0, maxWidth: 880,
          }}>
            One thing.{' '}
            <span className="serif" style={{ fontWeight: 400, fontStyle:'italic' }}>Quietly</span>.
          </h1>

          <div className="focus-clock">{mm}:{ss}</div>

          {/* Progress arc */}
          <div style={{ width: 360, marginTop: 8 }}>
            <div style={{ height: 6, borderRadius: 6, background:'rgba(14,14,14,0.08)', overflow:'hidden' }}>
              <div style={{
                width: `${pct}%`, height: '100%',
                background: 'linear-gradient(90deg, var(--coral), #0e0e0e)',
                borderRadius: 6,
                transition: 'width 600ms ease',
              }}/>
            </div>
            <div style={{ display:'flex', justifyContent:'space-between', marginTop: 8, fontSize: 11.5, color:'var(--muted)' }}>
              <span>Started {startTime}</span>
              <span>Target {Math.floor(target/60)}m</span>
            </div>
          </div>

          {/* Controls */}
          <div style={{ display:'flex', gap: 10, marginTop: 26 }}>
            <button className="btn light" onClick={() => setRunning(r => !r)}>
              {running ? <I.pause width="14" height="14"/> : <I.play width="14" height="14"/>}
              {running ? 'Pause' : 'Resume'}
            </button>
            <button className="btn ghost">Extend +15m</button>
            <button className="btn ghost">Take a break</button>
          </div>

          {/* What you're working on — editable */}
          <div style={{
            marginTop: 36,
            display:'flex', gap: 10, padding:'10px 14px',
            background:'rgba(255,255,255,0.5)',
            backdropFilter:'blur(20px)',
            WebkitBackdropFilter:'blur(20px)',
            borderRadius: 999,
            border:'1px solid rgba(255,255,255,0.7)',
            alignItems:'center',
          }}>
            <span className="tiny">Working on</span>
            {editingTask ? (
              <input
                autoFocus
                value={task}
                onChange={e => setTask(e.target.value)}
                onBlur={() => setEditingTask(false)}
                onKeyDown={e => e.key === 'Enter' && setEditingTask(false)}
                placeholder="What are you working on?"
                style={{
                  background: 'none', border: 'none', outline: 'none',
                  fontSize: 13, fontWeight: 600, width: 240,
                  fontFamily: 'inherit', color: 'var(--ink)',
                }}
              />
            ) : (
              <span
                style={{ fontSize: 13, fontWeight: 600, cursor: 'text', minWidth: 180 }}
                onClick={() => setEditingTask(true)}
                title="Click to edit"
              >
                {task || <span style={{ color:'var(--muted)', fontWeight: 400 }}>Click to set your task…</span>}
              </span>
            )}
            <button
              className="btn ghost"
              style={{ padding:'5px 10px', fontSize: 11.5 }}
              onClick={() => setEditingTask(true)}
            >
              {task ? 'Edit' : 'Set task'}
            </button>
          </div>
        </div>

        {/* Right aside — AI focus assistant */}
        <FocusAssistant seconds={seconds}/>
      </div>
    </div>
  );
}

function FocusAssistant({ seconds }) {
  const minutes = Math.floor(seconds / 60);

  return (
    <div className="focus-aside" style={{ alignSelf:'stretch', marginTop: 28, marginBottom: 28 }}>
      {/* AI assistant */}
      <div style={{ display:'flex', alignItems:'center', gap: 10, marginBottom: 14 }}>
        <div style={{
          width: 32, height: 32, borderRadius: 10,
          background:'var(--coral)', display:'grid', placeItems:'center',
        }}>
          <Doodles.Sparkle size={16} color="#2a1812"/>
        </div>
        <div>
          <div style={{ fontSize: 13, fontWeight: 600 }}>Coral · focus mode</div>
          <div className="muted" style={{ fontSize: 11 }}>watching quietly</div>
        </div>
      </div>

      {/* Nudges */}
      <div style={{ display:'flex', flexDirection:'column', gap: 10 }}>
        <div style={{ background:'var(--cream)', padding: 12, borderRadius: 14 }}>
          <div className="tiny" style={{ marginBottom: 4 }}>Pre-session intent · captured 13:32</div>
          <div style={{ fontSize: 12.5, lineHeight: 1.45 }}>
            "Outline §3.2. Don't open Slack. Cite Karpathy + Anthropic skills paper."
          </div>
        </div>

        <div style={{ background:'var(--pink-2)', padding: 12, borderRadius: 14 }}>
          <div style={{ display:'flex', alignItems:'center', gap: 6, marginBottom: 4 }}>
            <Doodles.Brain size={12}/>
            <span className="tiny">live nudge · {minutes}m in</span>
          </div>
          <div style={{ fontSize: 12.5, lineHeight: 1.45 }}>
            Your typing slowed 40s ago. Stuck on a sentence — want me to suggest a phrasing?
          </div>
          <div style={{ display:'flex', gap: 6, marginTop: 8 }}>
            <button className="btn" style={{ padding:'5px 10px', fontSize: 11 }}>Help me</button>
            <button className="btn ghost" style={{ padding:'5px 10px', fontSize: 11 }}>Dismiss</button>
          </div>
        </div>

        <div style={{ background:'var(--lilac-2)', padding: 12, borderRadius: 14 }}>
          <div className="tiny" style={{ marginBottom: 4 }}>Held back for you</div>
          <div style={{ fontSize: 12.5, lineHeight: 1.4 }}>
            3 Slack mentions · 2 emails · 1 calendar nudge
          </div>
          <button className="btn ghost" style={{ marginTop: 8, padding:'5px 10px', fontSize: 11 }}>
            See after session
          </button>
        </div>
      </div>

      {/* Quiet ambient picker */}
      <div style={{ marginTop: 16 }}>
        <div className="tiny" style={{ marginBottom: 8 }}>Ambient</div>
        <div style={{ display:'flex', gap: 6, flexWrap:'wrap' }}>
          {[
            { l:'Silence',  active: true },
            { l:'Rain' },
            { l:'Cafe' },
            { l:'Drone' },
            { l:'Forest' },
          ].map((a, i) => (
            <span key={i} className={'pill' + (a.active ? ' dark' : '')} style={{ fontSize: 11 }}>
              {a.l}
            </span>
          ))}
        </div>
      </div>

      {/* Session stats */}
      <div style={{ marginTop: 16, padding: 12, background:'rgba(14,14,14,0.04)', borderRadius: 14 }}>
        <div className="tiny" style={{ marginBottom: 8 }}>This session</div>
        <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap: 12 }}>
          <div>
            <div style={{ fontSize: 22, fontWeight: 700, letterSpacing:'-0.02em' }}>418</div>
            <div className="tiny">words written</div>
          </div>
          <div>
            <div style={{ fontSize: 22, fontWeight: 700, letterSpacing:'-0.02em' }}>0</div>
            <div className="tiny">context switches</div>
          </div>
        </div>
      </div>
    </div>
  );
}

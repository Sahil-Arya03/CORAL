import React from 'react';

/* Avatar */
export function Avatar({ initials, color, size }) {
  return (
    <span
      className={'av' + (size === 'lg' ? ' lg' : '')}
      style={{ background: color || 'var(--cream)' }}
    >
      {initials}
    </span>
  );
}

export function AvatarStack({ people = [] }) {
  return (
    <span className="av-stack">
      {people.map((p, i) => <Avatar key={i} initials={p.i} color={p.c}/>)}
    </span>
  );
}

/* Donut chart (SVG) */
export function Donut({ segments, size = 180, thickness = 22, label, value }) {
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  const total = segments.reduce((s, x) => s + x.v, 0);
  let offset = 0;
  return (
    <div className="donut-wrap" style={{ width: size, height: size }}>
      <svg width={size} height={size} style={{ transform: 'rotate(-90deg)' }}>
        <circle cx={size/2} cy={size/2} r={r} stroke="rgba(14,14,14,0.06)" strokeWidth={thickness} fill="none"/>
        {segments.map((seg, i) => {
          const len = (seg.v / total) * c;
          const dash = `${len} ${c - len}`;
          const el = (
            <circle key={i}
              cx={size/2} cy={size/2} r={r}
              stroke={seg.color} strokeWidth={thickness}
              strokeDasharray={dash} strokeDashoffset={-offset}
              fill="none" strokeLinecap="butt"
            />
          );
          offset += len;
          return el;
        })}
      </svg>
      <div className="donut-center">
        <div style={{ fontSize: 12, color: 'var(--muted)' }}>{label}</div>
        <div style={{ fontSize: 38, fontWeight: 700, letterSpacing: '-0.02em' }}>{value}</div>
      </div>
    </div>
  );
}

/* Sparkline (smoothed) */
export function Sparkline({ data, w = 240, h = 60, color = '#0e0e0e', fill = 'none' }) {
  if (!data || data.length === 0) return null;
  const max = Math.max(...data), min = Math.min(...data);
  const range = max - min || 1;
  const stepX = w / (data.length - 1);
  const points = data.map((v, i) => [i * stepX, h - ((v - min) / range) * (h - 6) - 3]);

  // smooth using catmull-rom-ish bezier
  let d = `M${points[0][0]},${points[0][1]}`;
  for (let i = 0; i < points.length - 1; i++) {
    const [x1, y1] = points[i], [x2, y2] = points[i+1];
    const mx = (x1 + x2) / 2;
    d += ` Q${x1},${y1} ${mx},${(y1+y2)/2}`;
    d += ` T${x2},${y2}`;
  }

  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none">
      {fill !== 'none' && <path d={`${d} L${w},${h} L0,${h} Z`} fill={fill}/>}
      <path d={d} stroke={color} strokeWidth="2" fill="none" strokeLinecap="round"/>
    </svg>
  );
}

/* Bars chart */
export function BarChart({ data, color = '#0e0e0e', h = 60, gap = 6, w }) {
  const max = Math.max(...data);
  return (
    <div className="bars" style={{ height: h, gap, width: w }}>
      {data.map((v, i) => (
        <span key={i} style={{
          height: `${(v / max) * 100}%`,
          background: color,
          opacity: v/max > 0.5 ? 1 : 0.55,
          flex: w ? 1 : 'initial',
        }}/>
      ))}
    </div>
  );
}

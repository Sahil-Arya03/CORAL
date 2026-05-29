import React, { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUser, useClerk } from '@clerk/clerk-react';
import { I } from './icons.jsx';

export function Topbar({ scopes = ['Tasks','Code','Notes','Calendar','Memory'] }) {
  const navigate = useNavigate();
  const { user } = useUser();
  const { signOut } = useClerk();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);

  const routes = { Calendar: '/timeline', Memory: '/memory' };

  // Close menu when clicking outside
  useEffect(() => {
    if (!menuOpen) return;
    function handleClick(e) {
      if (menuRef.current && !menuRef.current.contains(e.target)) setMenuOpen(false);
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [menuOpen]);

  const displayName = user?.firstName || user?.username
    || user?.primaryEmailAddress?.emailAddress?.split('@')[0] || '';
  const email       = user?.primaryEmailAddress?.emailAddress || '';
  const initials    = displayName.slice(0, 2).toUpperCase() || '?';
  const avatarUrl   = user?.imageUrl;

  return (
    <div className="topbar">
      <div className="search-pill">
        <div className="s-ico"><I.search width="14" height="14"/></div>
        <input placeholder="Ask CreatorOS or search anything…"/>
        <div className="scope">
          <span>In:</span>
          {scopes.map(s => {
            const dest = routes[s];
            return (
              <button
                key={s}
                className="scope-chip"
                onClick={() => dest && navigate(dest)}
                style={{
                  border: 'inherit', font: 'inherit',
                  cursor: dest ? 'pointer' : 'default',
                }}
                title={dest ? `Open ${s === 'Calendar' ? 'Timeline' : s}` : undefined}
              >
                {s}
              </button>
            );
          })}
        </div>
      </div>

      <div className="top-actions">
        <button className="icon-btn ghost" title="Refresh">
          <I.refresh width="16" height="16"/>
        </button>
        <button className="icon-btn" title="Notifications">
          <I.bell width="16" height="16"/>
        </button>

        {/* User avatar / menu */}
        <div ref={menuRef} style={{ position: 'relative' }}>
          <button
            className="icon-btn"
            title={displayName || 'Profile'}
            onClick={() => setMenuOpen(o => !o)}
            style={{ padding: 0, overflow: 'hidden', borderRadius: '50%', width: 32, height: 32 }}
          >
            {avatarUrl ? (
              <img src={avatarUrl} alt={displayName}
                   style={{ width: '100%', height: '100%', objectFit: 'cover' }}/>
            ) : (
              <span style={{
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                width: '100%', height: '100%',
                background: 'var(--coral, #e07b6a)', color: '#fff',
                fontSize: 12, fontWeight: 600, letterSpacing: '0.02em',
              }}>
                {initials}
              </span>
            )}
          </button>

          {menuOpen && (
            <div style={{
              position: 'absolute', top: 'calc(100% + 8px)', right: 0,
              background: 'var(--card-bg, #fff)',
              border: '1px solid var(--border, #e8e4de)',
              borderRadius: 10, padding: '6px 0',
              minWidth: 200, boxShadow: '0 4px 20px rgba(0,0,0,0.10)',
              zIndex: 100,
            }}>
              {/* User info */}
              <div style={{ padding: '10px 16px 8px', borderBottom: '1px solid var(--border, #e8e4de)' }}>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{displayName}</div>
                {email && <div style={{ fontSize: 12, color: 'var(--muted, #888)', marginTop: 2 }}>{email}</div>}
              </div>

              <button
                onClick={() => { setMenuOpen(false); signOut(() => navigate('/signin')); }}
                style={{
                  display: 'block', width: '100%', textAlign: 'left',
                  padding: '9px 16px', fontSize: 13,
                  background: 'none', border: 'none', cursor: 'pointer',
                  color: 'var(--ink, #1a1a1a)',
                }}
                onMouseEnter={e => e.currentTarget.style.background = 'var(--hover, #f5f3ef)'}
                onMouseLeave={e => e.currentTarget.style.background = 'none'}
              >
                Sign out
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

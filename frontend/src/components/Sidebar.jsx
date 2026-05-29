import React from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { I } from './icons.jsx';

export function Sidebar({ collapsed }) {
  const navigate = useNavigate();
  const navMain = [
    { id: 'dashboard', label: 'Dashboard', Icon: I.home },
    { id: 'chat',      label: 'AI Chat',   Icon: I.spark, badge: 'New' },
    { id: 'timeline',  label: 'Timeline',  Icon: I.timeline },
    { id: 'analytics', label: 'Analytics', Icon: I.chart },
    { id: 'focus',     label: 'Focus mode',Icon: I.focus },
  ];
  const navTools = [
    { id: 'projects',     label: 'Projects',     Icon: I.code },
    { id: 'memory',       label: 'Memory',       Icon: I.brain },
    { id: 'integrations', label: 'Integrations', Icon: I.plug },
    { id: 'settings',     label: 'Settings',     Icon: I.gear },
  ];

  const NavBtn = ({ item }) => (
    <NavLink
      to={'/' + item.id}
      className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
      title={collapsed ? item.label : undefined}
    >
      <item.Icon className="ico"/>
      <span className="nav-label">{item.label}</span>
      {item.badge && <span className="badge">{item.badge}</span>}
      <span className="tip">{item.label}</span>
    </NavLink>
  );

  return (
    <aside className={'sidebar' + (collapsed ? ' collapsed' : '')}>
      <div className="sidebar-logo">
        <span className="dot"/>
        <span className="logo-text">CreatorOS</span>
      </div>

      <div className="sidebar-scroll">
        <div className="nav-section">
          <div className="label">General</div>
          {navMain.map(item => <NavBtn key={item.id} item={item}/>)}
        </div>

        <div className="nav-section">
          <div className="label">Workspace</div>
          {navTools.map(item => <NavBtn key={item.id} item={item}/>)}
        </div>
      </div>

      {/* Memory status — hidden when collapsed */}
      <div className="memory-status">
        <div style={{ display:'flex', alignItems:'center', gap:8, fontSize: 11.5, color:'#c9c6bd' }}>
          <span className="pulse"/>
          <span>Memory active</span>
        </div>
        <div style={{ fontSize: 10.5, color:'#6e6c64', marginTop: 4 }}>
          2,341 contexts · synced 2m ago
        </div>
      </div>

      <button className="logout" onClick={() => navigate('/signin')} title={collapsed ? 'Log out' : undefined}>
        <I.logout className="ico"/>
        <span className="nav-label">Log out</span>
      </button>
    </aside>
  );
}

export function CollapseToggle({ collapsed, setCollapsed }) {
  return (
    <button
      className="collapse-toggle"
      onClick={() => setCollapsed(c => !c)}
      title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
      aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
    >
      {/* chevron-left — rotates 180° when collapsed via CSS */}
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
           strokeLinecap="round" strokeLinejoin="round">
        <path d="M15 6l-6 6 6 6"/>
      </svg>
    </button>
  );
}
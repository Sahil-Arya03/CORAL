import React, { useState, useEffect, useRef } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { useAuth, useUser, AuthenticateWithRedirectCallback } from '@clerk/clerk-react';
import { Sidebar, CollapseToggle } from './components/Sidebar.jsx';
import { Topbar } from './components/Topbar.jsx';
import { DashboardPage } from './pages/Dashboard.jsx';
import { ChatPage } from './pages/Chat.jsx';
import { TimelinePage } from './pages/Timeline.jsx';
import { AnalyticsPage } from './pages/Analytics.jsx';
import { FocusPage } from './pages/Focus.jsx';
import { IntegrationsPage } from './pages/Integrations.jsx';
import { SignInPage } from './pages/SignIn.jsx';
import { syncUser } from './api.js';

export default function App() {
  const location = useLocation();
  const { isSignedIn, isLoaded } = useAuth();
  const { user } = useUser();

  // Sync Clerk user to our DB whenever they sign in (upsert — safe to repeat)
  useEffect(() => {
    if (!isSignedIn || !user) return;
    syncUser({
      clerkId:     user.id,
      email:       user.primaryEmailAddress?.emailAddress ?? '',
      username:    user.username ?? '',
      displayName: user.fullName ?? '',
    })
      .then(({ id }) => {
        try { localStorage.setItem('coral.userId', String(id)); } catch { /* ignore */ }
      })
      .catch(() => { /* non-fatal — app still works */ });
  }, [isSignedIn, user?.id]);

  const [collapsed, setCollapsed] = useState(() => {
    try { return localStorage.getItem('coros.sidebar') === 'collapsed'; } catch { return false; }
  });

  useEffect(() => {
    try { localStorage.setItem('coros.sidebar', collapsed ? 'collapsed' : 'expanded'); } catch {}
  }, [collapsed]);

  const wasCollapsedRef = useRef(collapsed);
  const isFocus = location.pathname === '/focus';
  useEffect(() => {
    if (isFocus) {
      wasCollapsedRef.current = collapsed;
      setCollapsed(true);
    } else {
      if (!wasCollapsedRef.current) setCollapsed(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isFocus]);

  // Wait for Clerk to finish loading session state
  if (!isLoaded) return null;

  const path = location.pathname;
  const isAuthRoute = path === '/signin' || path === '/signup' || path === '/sso-callback';

  // Redirect unauthenticated users away from protected pages
  if (!isSignedIn && !isAuthRoute) {
    return <Navigate to="/signin" replace />;
  }

  // Auth pages render without the app shell
  if (isAuthRoute) {
    return (
      <Routes>
        <Route path="/signin"       element={<SignInPage />} />
        <Route path="/signup"       element={<SignInPage defaultMode="signup" />} />
        {/* Clerk posts back here after OAuth social sign-in */}
        <Route path="/sso-callback" element={<AuthenticateWithRedirectCallback />} />
      </Routes>
    );
  }

  return (
    <div className="os-frame">
      <div className={'os-shell' + (collapsed ? ' collapsed' : '')}>
        <Sidebar collapsed={collapsed}/>
        <CollapseToggle collapsed={collapsed} setCollapsed={setCollapsed}/>
        <div className="main">
          {!isFocus && <Topbar />}
          <Routes>
            <Route path="/"            element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard"   element={<DashboardPage />} />
            <Route path="/chat"        element={<ChatPage />} />
            <Route path="/timeline"    element={<TimelinePage />} />
            <Route path="/analytics"   element={<AnalyticsPage />} />
            <Route path="/focus"       element={<FocusPage />} />
            <Route path="/integrations"element={<IntegrationsPage />} />
            <Route path="*"            element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </div>
      </div>
    </div>
  );
}

import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, useNavigate } from 'react-router-dom';
import { ClerkProvider } from '@clerk/clerk-react';
import App from './App.jsx';
import './styles.css';

const PUBLISHABLE_KEY = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY;
if (!PUBLISHABLE_KEY || PUBLISHABLE_KEY.startsWith('pk_test_REPLACE')) {
  throw new Error(
    'Missing Clerk key — open frontend/.env and set VITE_CLERK_PUBLISHABLE_KEY'
  );
}

// ClerkProvider must live inside BrowserRouter so it can use useNavigate.
// Without this, Clerk falls back to window.location on setActive() and
// may redirect to the wrong URL (e.g. /sso-callback from a prior OAuth attempt).
function ClerkWithRouter({ children }) {
  const navigate = useNavigate();
  return (
    <ClerkProvider
      publishableKey={PUBLISHABLE_KEY}
      routerPush={(to) => navigate(to)}
      routerReplace={(to) => navigate(to, { replace: true })}
      afterSignInUrl="/dashboard"
      afterSignUpUrl="/dashboard"
      signInUrl="/signin"
      signUpUrl="/signup"
    >
      {children}
    </ClerkProvider>
  );
}

createRoot(document.getElementById('root')).render(
  <BrowserRouter>
    <ClerkWithRouter>
      <App />
    </ClerkWithRouter>
  </BrowserRouter>
);
import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSignIn, useSignUp } from '@clerk/clerk-react';
import { Doodles } from '../components/doodles.jsx';
import { I } from '../components/icons.jsx';

export function SignInPage({ defaultMode = 'signin' }) {
  const [mode, setMode]           = useState(defaultMode);
  const [step, setStep]           = useState('form');   // 'form' | 'verify'
  const [fullName, setFullName]   = useState('');
  const [email, setEmail]         = useState('');
  const [password, setPassword]   = useState('');
  const [username, setUsername]   = useState('');
  const [code, setCode]           = useState('');
  const [error, setError]         = useState('');
  const [loading, setLoading]     = useState(false);

  const navigate = useNavigate();
  const { signIn, setActive: setSignInActive, isLoaded: signInLoaded } = useSignIn();
  const { signUp, setActive: setSignUpActive, isLoaded: signUpLoaded } = useSignUp();

  const ready    = signInLoaded && signUpLoaded;
  const isSignup = mode === 'signup';

  function switchMode(next) {
    setMode(next);
    setStep('form');
    setError('');
    setCode('');
    setFullName('');
  }

  // ── Email / password submit ────────────────────────────────────────────────
  async function handleSubmit(e) {
    e.preventDefault();
    if (!ready) return;
    setError('');
    setLoading(true);
    try {
      if (isSignup) {
        const nameParts = fullName.trim().split(/\s+/);
        const firstName = nameParts[0] || undefined;
        const lastName  = nameParts.slice(1).join(' ') || undefined;

        const result = await signUp.create({
          emailAddress: email,
          password,
          firstName,
          lastName,
          username: username.trim() || undefined,
        });

        if (result.status === 'complete') {
          await setSignUpActive({ session: result.createdSessionId });
          navigate('/dashboard');
          return;
        }

        // Email verification required — send a 6-digit code so the user
        // stays on this page (no email-link redirect to /sso-callback)
        await signUp.prepareEmailAddressVerification({ strategy: 'email_code' });
        setStep('verify');

      } else {
        const result = await signIn.create({
          identifier: email,
          password,
        });
        if (result.status === 'complete') {
          await setSignInActive({ session: result.createdSessionId });
          navigate('/dashboard');
        } else {
          setError('Additional verification required. Please try again.');
        }
      }
    } catch (err) {
      setError(
        err?.errors?.[0]?.longMessage ??
        err?.errors?.[0]?.message ??
        'Something went wrong. Please try again.'
      );
    } finally {
      setLoading(false);
    }
  }

  // ── Email OTP verification (sign-up only) ─────────────────────────────────
  async function handleVerify(e) {
    e.preventDefault();
    if (!ready) return;
    setError('');
    setLoading(true);
    try {
      const result = await signUp.attemptEmailAddressVerification({ code });
      if (result.status === 'complete') {
        await setSignUpActive({ session: result.createdSessionId });
        navigate('/dashboard');
      } else {
        setError('Verification incomplete — please try again.');
      }
    } catch (err) {
      setError(
        err?.errors?.[0]?.longMessage ??
        err?.errors?.[0]?.message ??
        'Invalid code. Please check and try again.'
      );
    } finally {
      setLoading(false);
    }
  }

  async function resendCode() {
    try {
      await signUp.prepareEmailAddressVerification({ strategy: 'email_code' });
      setError('');
    } catch {
      setError('Could not resend code. Please try again.');
    }
  }

  // ── OAuth social sign-in ───────────────────────────────────────────────────
  async function handleSocial(strategy) {
    if (!signInLoaded) return;
    setError('');
    try {
      await signIn.authenticateWithRedirect({
        strategy,
        redirectUrl:         `${window.location.origin}/sso-callback`,
        redirectUrlComplete: `${window.location.origin}/dashboard`,
      });
    } catch (err) {
      setError(err?.errors?.[0]?.message ?? 'OAuth sign-in failed.');
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────
  return (
    <div className="auth-page">

      {/* ── Brand panel ── */}
      <section className="brand-panel">
        <Doodles.Sparkle size={34} color="#3a3a3a"
          style={{ position:'absolute', top:34, right:40, opacity:0.5 }}/>
        <Doodles.Squiggle w={90} color="#2c2c2c"
          style={{ position:'absolute', bottom:120, right:54, opacity:0.45 }}/>
        <Doodles.Asterisk size={26} color="#333"
          style={{ position:'absolute', top:180, right:90, opacity:0.4 }}/>

        <div className="brand-logo">
          <span className="dot"/>
          <span>CreatorOS</span>
        </div>

        <div className="brand-mid">
          <div className="brand-eyebrow">
            <span className="pulse"/>
            Your AI productivity OS
          </div>
          <h2 className="brand-head">
            One mind for everything you{' '}
            <span className="serif" style={{ color:'var(--coral)' }}>
              build, learn &amp; remember.
            </span>
          </h2>
          <p className="brand-sub">
            Notes, code, calendar and memory — woven together and surfaced
            back exactly when you need them.
          </p>
          <div className="brand-features">
            <div className="feat">
              <span className="tile"><I.spark  width="16" height="16"/></span>
              <span>A daily brief that reads your last 48 hours</span>
            </div>
            <div className="feat">
              <span className="tile"><I.brain  width="16" height="16"/></span>
              <span>Second-brain memory, synced two-way with Notion</span>
            </div>
            <div className="feat">
              <span className="tile"><I.focus  width="16" height="16"/></span>
              <span>Focus mode that protects your deep-work window</span>
            </div>
          </div>
          <div className="brand-foot">
            <span className="av-stack">
              <span className="av" style={{ background:'var(--pink)' }}>AR</span>
              <span className="av" style={{ background:'var(--blue)' }}>MJ</span>
              <span className="av" style={{ background:'var(--green)' }}>SK</span>
              <span className="av" style={{ background:'var(--lilac)' }}>+</span>
            </span>
            <span>Joined by <span className="ctx">12,000+</span> makers remembering more.</span>
          </div>
        </div>
      </section>

      {/* ── Form panel ── */}
      <section className="form-panel">
        <div className="form-wrap">

          {/* ── Email verification step ── */}
          {step === 'verify' ? (
            <>
              <div className="form-top">
                <h1>Check your email</h1>
                <p>
                  We sent a 6-digit code to <strong>{email}</strong>.
                  Enter it below to verify your account.
                </p>
              </div>

              {error && <ErrorBanner msg={error}/>}

              <form onSubmit={handleVerify}>
                <div className="field">
                  <label htmlFor="otp-code">Verification code</label>
                  <input
                    id="otp-code"
                    type="text"
                    inputMode="numeric"
                    placeholder="123456"
                    maxLength={6}
                    value={code}
                    onChange={e => setCode(e.target.value.replace(/\D/g, ''))}
                    autoFocus
                    required
                  />
                </div>

                <button className="submit-btn" type="submit" disabled={!ready || loading}>
                  {loading ? 'Verifying…' : 'Verify and continue'}
                  {!loading && <ArrowIcon/>}
                </button>
              </form>

              <div className="form-switch">
                <span>
                  Didn&apos;t get it?{' '}
                  <button type="button" onClick={resendCode}>Resend code</button>
                  {' · '}
                  <button type="button" onClick={() => switchMode('signup')}>Go back</button>
                </span>
              </div>
            </>
          ) : (

          /* ── Sign-in / sign-up form ── */
          <>
            <div className="form-top">
              <h1>{isSignup ? 'Create your OS' : 'Welcome back'}</h1>
              <p>
                {isSignup
                  ? 'Start free — connect your tools and let it learn you.'
                  : 'Sign in to pick up where your second brain left off.'}
              </p>
            </div>

            <div className="auth-seg" role="tablist">
              <button className={!isSignup ? 'active' : ''}
                onClick={() => switchMode('signin')}>Sign in</button>
              <button className={isSignup ? 'active' : ''}
                onClick={() => switchMode('signup')}>Create account</button>
            </div>

            <div className="social">
              <button type="button" onClick={() => handleSocial('oauth_google')}>
                <svg width="17" height="17" viewBox="0 0 24 24">
                  <path fill="#4285F4" d="M22.5 12.27c0-.79-.07-1.54-.2-2.27H12v4.3h5.9a5.05 5.05 0 0 1-2.19 3.31v2.76h3.54c2.08-1.92 3.25-4.74 3.25-8.1z"/>
                  <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.54-2.76c-.98.66-2.23 1.06-3.74 1.06-2.87 0-5.3-1.94-6.17-4.55H2.18v2.85A11 11 0 0 0 12 23z"/>
                  <path fill="#FBBC05" d="M5.83 14.09a6.6 6.6 0 0 1 0-4.18V7.06H2.18a11 11 0 0 0 0 9.88l3.65-2.85z"/>
                  <path fill="#EA4335" d="M12 4.75c1.62 0 3.07.56 4.21 1.65l3.14-3.14C17.45 1.46 14.97.5 12 .5A11 11 0 0 0 2.18 7.06l3.65 2.85C6.7 6.7 9.13 4.75 12 4.75z"/>
                </svg>
                Continue with Google
              </button>
              <button type="button" onClick={() => handleSocial('oauth_github')}>
                <I.github width="17" height="17"/>
                Continue with GitHub
              </button>
            </div>

            <div className="auth-divider">or with email</div>

            {error && <ErrorBanner msg={error}/>}

            <form onSubmit={handleSubmit}>
              {isSignup && (
                <>
                  <div className="field">
                    <label htmlFor="auth-name">Full name</label>
                    <input
                      id="auth-name"
                      type="text"
                      placeholder="Alex Rivera"
                      autoComplete="name"
                      value={fullName}
                      onChange={e => setFullName(e.target.value)}
                      required
                    />
                  </div>
                  <div className="field">
                    <label htmlFor="auth-username">Username <span style={{color:'var(--muted)',fontWeight:400}}>(optional)</span></label>
                    <input
                      id="auth-username"
                      type="text"
                      placeholder="alexrivera"
                      autoComplete="username"
                      value={username}
                      onChange={e => setUsername(e.target.value)}
                    />
                  </div>
                </>
              )}

              <div className="field">
                <label htmlFor="auth-email">Email</label>
                <input
                  id="auth-email"
                  type="email"
                  placeholder="you@studio.com"
                  autoComplete="email"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  required
                />
              </div>

              <div className="field">
                <label htmlFor="auth-password">Password</label>
                <input
                  id="auth-password"
                  type="password"
                  placeholder="••••••••"
                  autoComplete={isSignup ? 'new-password' : 'current-password'}
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  required
                />
              </div>

              {!isSignup && (
                <div className="row-between">
                  <label className="remember">
                    <input type="checkbox"/> Remember me
                  </label>
                  <a className="auth-link" href="#">Forgot password?</a>
                </div>
              )}

              {isSignup && <div style={{ height:6 }}/>}

              <button className="submit-btn" type="submit" disabled={!ready || loading}>
                {loading
                  ? (isSignup ? 'Creating account…' : 'Signing in…')
                  : (isSignup ? 'Create account' : 'Sign in to CreatorOS')}
                {!loading && <ArrowIcon/>}
              </button>

              {isSignup && (
                <p className="auth-terms">
                  By creating an account you agree to our{' '}
                  <a href="#">Terms</a> and <a href="#">Privacy Policy</a>.
                </p>
              )}
            </form>

            <div className="form-switch">
              {isSignup ? (
                <span>
                  Already have an account?{' '}
                  <button onClick={() => switchMode('signin')}>Sign in</button>
                </span>
              ) : (
                <span>
                  New to CreatorOS?{' '}
                  <button onClick={() => switchMode('signup')}>Create an account</button>
                </span>
              )}
            </div>
          </>
          )}

        </div>
      </section>
    </div>
  );
}

function ErrorBanner({ msg }) {
  return (
    <div style={{
      background:'#fee2e2', color:'#991b1b',
      borderRadius:8, padding:'10px 14px',
      fontSize:13, marginBottom:12,
    }}>
      {msg}
    </div>
  );
}

function ArrowIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="1.8"
         strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 12h14"/><path d="M13 6l6 6-6 6"/>
    </svg>
  );
}

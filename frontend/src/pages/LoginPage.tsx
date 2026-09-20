import { FormEvent, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { extractError } from '../api/client';
import { mfaApi } from '../api/auth';
import { LoginBackground } from '../components/auth/LoginBackground';
import { MfaSetup } from '../components/auth/MfaSetup';
import { IconCheck } from '../components/Icons';
import { PasswordInput } from '../components/PasswordInput';
import { PreferencesToggle } from '../components/PreferencesToggle';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';

import type { Role, User } from '../api/types';

function homeFor(role: Role): string {
  if (role === 'SUPER_ADMIN') return '/super';
  if (role === 'ADMIN') return '/admin';
  return '/dashboard';
}

/** Where a finished sign-in should land, honouring a redirect origin if present. */
function destinationFor(user: User, from?: string): string {
  return from && from !== '/login' ? from : homeFor(user.role);
}

/** credentials → (password ok) → code | enroll → next → signed in. */
type Phase = 'credentials' | 'code' | 'enroll' | 'next';

interface LoginPageProps {
  onShowGuidelines?: () => void;
}

export function LoginPage({ onShowGuidelines }: LoginPageProps = {}) {
  const { login, completeMfa, cancelMfa, user } = useAuth();
  const { t, lang } = useT();
  const navigate = useNavigate();
  const location = useLocation();

  const [phase, setPhase] = useState<Phase>('credentials');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [ticket, setTicket] = useState('');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;

  useEffect(() => {
    if (user) navigate(destinationFor(user, from), { replace: true });
  }, [user, navigate, from]);

  /** Abandon the challenge: drop the pending ticket and start over. */
  function backToCredentials() {
    cancelMfa();
    setPhase('credentials');
    setTicket('');
    setCode('');
    setError(null);
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!username.trim() || !password) {
      setError(t('auth.fillFields'));
      return;
    }
    setSubmitting(true);
    try {
      const outcome = await login(username.trim(), password);
      if (outcome.status === 'ok') {
        navigate(destinationFor(outcome.user, from), { replace: true });
        return;
      }
      // The password was accepted; only the second factor is owed now. Route by
      // enrollment state — a device exists → enter a code; none yet → first-run setup.
      setTicket(outcome.ticket);
      try {
        const status = await mfaApi.status();
        setPhase(status.enrolled ? 'code' : 'enroll');
      } catch {
        setPhase('code');
      }
    } catch (err) {
      setError(extractError(err, t('auth.failed')));
    } finally {
      setSubmitting(false);
    }
  }

  async function onVerify(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!code.trim()) return;
    setSubmitting(true);
    try {
      const u = await completeMfa(ticket, code.trim());
      navigate(destinationFor(u, from), { replace: true });
    } catch (err) {
      setError(extractError(err, t('mfa.wrongCode')));
    } finally {
      setSubmitting(false);
    }
  }

  /** Enrollment finished; one more (fresh) code swaps the pending ticket for a session. */
  function onEnrollDone() {
    setCode('');
    setError(null);
    setPhase('next');
  }

  const isAr = lang === 'ar';
  const headline = isAr
    ? 'أدر عمليات إدخال البيانات بثقة'
    : 'Manage data entry with confidence';
  const tagline = isAr
    ? 'منصّة موحّدة لتنظيم فرق إدخال البيانات، تتبّع المهام، ومراقبة الأداء لحظة بلحظة.'
    : 'One workspace to organize your data entry team, track tasks, and monitor performance in real time.';
  const features = isAr
    ? ['تتبّع كل مهمة من التقديم للاعتماد', 'حقول مخصّصة يديرها المشرف', 'تقارير أداء وتحليلات مباشرة']
    : ['Track every task from submit to sign-off', 'Custom fields managed by the admin', 'Live performance reports & analytics'];

  const showDemoCreds = import.meta.env.VITE_SHOW_DEMO_CREDS === 'true';
  const hasError = Boolean(error);

  const words = headline.trim().split(' ');
  const headlineHead = words.slice(0, -1).join(' ');
  const headlineTail = words[words.length - 1];

  return (
    <div className="auth-shell">
      <div className="auth-corner">
        <PreferencesToggle />
      </div>

      <aside className="auth-brand-panel">
        <img
          className="auth-brand-mark-bg"
          src="/neurix-mark.png"
          alt=""
          aria-hidden="true"
          onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
        />
        <LoginBackground />

        <div className="auth-brand-content">
          <h1 className="auth-brand-headline">
            {headlineHead}{' '}
            <span className="accent">{headlineTail}</span>
          </h1>
          <p className="auth-brand-tagline">{tagline}</p>

          <ul className="auth-brand-features">
            {features.map((f) => (
              <li key={f}>
                <span className="dot" aria-hidden="true"><IconCheck size={12} /></span>
                {f}
              </li>
            ))}
          </ul>
        </div>

        <div className="auth-brand-footer">
          © {new Date().getFullYear()} Neurix — {isAr ? 'كل الحقوق محفوظة' : 'All rights reserved'}
        </div>
      </aside>

      <main className="auth-form-panel">
        <div className="auth-card">
          <div className="auth-form-logo">
            <img
              src="/neurix-logo.png"
              alt="Neurix"
              width={240}
              height={72}
              onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
            />
          </div>

          {phase === 'credentials' && (
            <>
              <h1 className="auth-title">{t('auth.welcome')}</h1>
              <p className="auth-subtitle">{t('auth.subtitle')}</p>

              {error && (
                <div className="alert alert-error" role="alert" aria-live="assertive">
                  {error}
                </div>
              )}

              <form onSubmit={onSubmit} noValidate>
                <div className="field">
                  <label className="field-label" htmlFor="username">{t('auth.username')}</label>
                  <input
                    id="username"
                    className="input"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    autoComplete="username"
                    autoFocus
                    aria-invalid={hasError || undefined}
                  />
                </div>
                <div className="field">
                  <label className="field-label" htmlFor="password">{t('auth.password')}</label>
                  <PasswordInput
                    id="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="current-password"
                    aria-invalid={hasError || undefined}
                    showLabel={isAr ? 'إظهار كلمة المرور' : 'Show password'}
                    hideLabel={isAr ? 'إخفاء كلمة المرور' : 'Hide password'}
                  />
                </div>
                <button
                  type="submit"
                  className="btn btn-primary auth-submit"
                  disabled={submitting}
                  aria-busy={submitting || undefined}
                >
                  {submitting ? (
                    <>
                      <span className="spinner" aria-hidden="true" />
                      <span className="sr-only">{t('auth.signingIn')}</span>
                    </>
                  ) : t('auth.signIn')}
                </button>
              </form>

              {showDemoCreds && (
                <div className="demo-creds">
                  <div className="mb-1"><strong>{isAr ? 'حسابات تجريبية' : 'Demo accounts'}</strong></div>
                  <div>{isAr ? 'المشرف:' : 'Admin:'} <code>admin</code> / <code>admin123</code></div>
                  <div className="demo-creds-row">{isAr ? 'الموظف:' : 'Agent:'} <code>agent1</code> / <code>agent123</code></div>
                </div>
              )}

              <p className="small muted auth-hint">{t('auth.hint')}</p>

              {onShowGuidelines && (
                <button type="button" className="auth-guidelines-link small" onClick={onShowGuidelines}>
                  {isAr ? 'إرشادات رفع الملفات' : 'Upload guidelines'}
                </button>
              )}
            </>
          )}

          {(phase === 'code' || phase === 'next') && (
            <>
              <h1 className="auth-title">
                {phase === 'code' ? t('mfa.challengeTitle') : t('mfa.nextCodeTitle')}
              </h1>
              <p className="auth-subtitle">
                {phase === 'code' ? t('mfa.challengeHint') : t('mfa.nextCodeHint')}
              </p>

              {error && (
                <div className="alert alert-error" role="alert" aria-live="assertive">
                  {error}
                </div>
              )}

              <form onSubmit={onVerify} noValidate>
                <div className="field">
                  <label className="field-label" htmlFor="mfa-code">{t('mfa.codeLabel')}</label>
                  <input
                    id="mfa-code"
                    className="input"
                    value={code}
                    onChange={(e) => setCode(e.target.value.replace(/[^0-9-]/g, ''))}
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    placeholder={t('mfa.codePlaceholder')}
                    autoFocus
                    required
                  />
                </div>
                <button
                  type="submit"
                  className="btn btn-primary auth-submit"
                  disabled={submitting || !code}
                  aria-busy={submitting || undefined}
                >
                  {submitting ? t('mfa.verifying') : t('mfa.verify')}
                </button>
              </form>

              {phase === 'code' && (
                <p className="small muted auth-hint">{t('mfa.recoveryHint')}</p>
              )}
              <button type="button" className="auth-guidelines-link small" onClick={backToCredentials}>
                {t('mfa.otherAccount')}
              </button>
            </>
          )}

          {phase === 'enroll' && (
            <>
              <h1 className="auth-title">{t('mfa.enrollTitle')}</h1>
              <p className="auth-subtitle">{t('mfa.enrollHint')}</p>
              <MfaSetup
                initialPassword={password}
                onDone={onEnrollDone}
                onCancel={backToCredentials}
              />
            </>
          )}
        </div>
      </main>
    </div>
  );
}


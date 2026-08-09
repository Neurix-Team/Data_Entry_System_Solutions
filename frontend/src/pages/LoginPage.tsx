import { FormEvent, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { extractError } from '../api/client';
import { IconCheck } from '../components/Icons';
import { PreferencesToggle } from '../components/PreferencesToggle';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';

function homeFor(role: 'ADMIN' | 'USER'): string {
  return role === 'ADMIN' ? '/admin' : '/dashboard';
}

export function LoginPage() {
  const { login, user } = useAuth();
  const { t, lang } = useT();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Redirect an already-logged-in user *after* render, not during it — calling navigate()
  // inside the render body triggers React's "cannot update during render" warning.
  useEffect(() => {
    if (user) navigate(homeFor(user.role), { replace: true });
  }, [user, navigate]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!username.trim() || !password) {
      setError(t('auth.fillFields'));
      return;
    }
    setSubmitting(true);
    try {
      const u = await login(username.trim(), password);
      const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
      const dest = from && from !== '/login' ? from : homeFor(u.role);
      navigate(dest, { replace: true });
    } catch (err) {
      setError(extractError(err, t('auth.failed')));
    } finally {
      setSubmitting(false);
    }
  }

  const isAr = lang === 'ar';
  const headline = isAr
    ? 'أدر عمليات إدخال البيانات بكفاءة عالية'
    : 'Manage data entry with confidence';
  const tagline = isAr
    ? 'منصّة موحّدة لتنظيم فرق إدخال البيانات، تتبّع المهام، ومراقبة الأداء لحظة بلحظة.'
    : 'One workspace to organize your data entry team, track tasks, and monitor performance in real time.';
  const features = isAr
    ? ['تتبّع كل مهمة من التقديم للاعتماد', 'حقول مخصّصة يديرها المشرف', 'تقارير أداء وتحليلات مباشرة']
    : ['Track every task from submit to sign-off', 'Custom fields managed by the admin', 'Live performance reports & analytics'];

  // Demo credentials are useful in local dev but leak the default admin password to anyone
  // who lands on the login page in production.  Gate on a Vite env var (falsy by default).
  const showDemoCreds = import.meta.env.VITE_SHOW_DEMO_CREDS === 'true';

  const hasError = Boolean(error);

  return (
    <div className="auth-shell">
      <div className="auth-corner">
        <PreferencesToggle />
      </div>

      <aside className="auth-brand-panel">
        <div className="auth-brand-top">
          <div className="bar" />
          <div className="name">{t('brand')}</div>
        </div>

        <div className="auth-brand-content">
          <h1 className="auth-brand-headline">{headline}</h1>
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
          © {new Date().getFullYear()} DataEntry — {isAr ? 'كل الحقوق محفوظة' : 'All rights reserved'}
        </div>
      </aside>

      <main className="auth-form-panel">
        <div className="auth-card">
          <div className="brand-mark-logo">
            <div className="brand-mark-bar" aria-hidden="true" />
            <div className="brand-mark-name">{t('brand')}</div>
          </div>

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
              <input
                id="password"
                type="password"
                className="input"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                aria-invalid={hasError || undefined}
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

          <p className="small muted auth-hint">
            {t('auth.hint')}
          </p>
        </div>
      </main>
    </div>
  );
}

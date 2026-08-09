import { FormEvent, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { extractError } from '../api/client';
import { IconCheck } from '../components/Icons';
import { PreferencesToggle } from '../components/PreferencesToggle';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';

export function LoginPage() {
  const { login, user } = useAuth();
  const { t, lang } = useT();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (user) {
    const dest = user.role === 'ADMIN' ? '/admin' : '/submit';
    navigate(dest, { replace: true });
    return null;
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
      const u = await login(username.trim(), password);
      const from = (location.state as { from?: { pathname?: string } } | null)?.from?.pathname;
      const dest = from && from !== '/login' ? from : u.role === 'ADMIN' ? '/admin' : '/submit';
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

          <ul className="auth-brand-features" style={{ listStyle: 'none', padding: 0, margin: '2rem 0 0' }}>
            {features.map((f) => (
              <li key={f}>
                <span className="dot"><IconCheck size={12} /></span>
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
            <div className="brand-mark-bar" />
            <div className="brand-mark-name">{t('brand')}</div>
          </div>

          <h1 className="auth-title">{t('auth.welcome')}</h1>
          <p className="auth-subtitle">{t('auth.subtitle')}</p>

          {error && <div className="alert alert-error">{error}</div>}

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
              />
            </div>
            <button
              className="btn btn-primary"
              style={{ width: '100%', marginTop: '0.5rem' }}
              disabled={submitting}
            >
              {submitting ? <span className="spinner" /> : t('auth.signIn')}
            </button>
          </form>

          <div className="demo-creds">
            <div className="mb-1"><strong>{isAr ? 'حسابات تجريبية' : 'Demo accounts'}</strong></div>
            <div>{isAr ? 'المشرف:' : 'Admin:'} <code>admin</code> / <code>admin123</code></div>
            <div style={{ marginTop: 4 }}>{isAr ? 'الموظف:' : 'Agent:'} <code>agent1</code> / <code>agent123</code></div>
          </div>

          <p className="small muted" style={{ textAlign: 'center', marginTop: '1.5rem', marginBottom: 0 }}>
            {t('auth.hint')}
          </p>
        </div>
      </main>
    </div>
  );
}

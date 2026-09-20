import { FormEvent, useState } from 'react';
import { mfaApi } from '../../api/auth';
import { extractError } from '../../api/client';
import type { MfaEnrollResponse, MfaRecoveryCodes } from '../../api/types';
import { PasswordInput } from '../PasswordInput';
import { useT } from '../../i18n';

type Step = 'password' | 'secret' | 'confirm' | 'codes';

interface MfaSetupProps {
  /** Password already proven by the sign-in step; when absent the wizard asks for it. */
  initialPassword?: string;
  onDone: () => void;
  onCancel?: () => void;
}

/**
 * First-run TOTP enrollment wizard: password re-proof, one-time secret, first code,
 * recovery codes. The shared secret and the recovery codes are shown exactly once.
 */
export function MfaSetup({ initialPassword, onDone, onCancel }: MfaSetupProps) {
  const { t } = useT();
  const [step, setStep] = useState<Step>('password');
  const [password, setPassword] = useState(initialPassword ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [enroll, setEnroll] = useState<MfaEnrollResponse | null>(null);
  const [code, setCode] = useState('');
  const [codes, setCodes] = useState<MfaRecoveryCodes | null>(null);
  const [copied, setCopied] = useState(false);

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard unavailable */ }
  }

  async function start(e?: FormEvent) {
    e?.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = await mfaApi.enroll(password);
      setEnroll(res);
      setStep('secret');
    } catch (err) {
      setError(extractError(err, t('common.somethingWrong')));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!code.trim()) return;
    setBusy(true);
    try {
      const res = await mfaApi.confirmEnrollment(code.trim());
      setCodes(res);
      setStep('codes');
    } catch (err) {
      setError(extractError(err, t('mfa.wrongCode')));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mfa-setup">
      {error && <div className="alert alert-error" role="alert">{error}</div>}

      {step === 'password' && (
        <form onSubmit={start} noValidate>
          <div className="field">
            <label className="field-label" htmlFor="mfa-password">{t('mfa.passwordLabel')}</label>
            <PasswordInput
              id="mfa-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />
          </div>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button type="submit" className="btn btn-primary" disabled={busy || !password}>
              {busy ? t('mfa.starting') : t('mfa.start')}
            </button>
            {onCancel && (
              <button type="button" className="btn" onClick={onCancel}>
                {t('mfa.otherAccount')}
              </button>
            )}
          </div>
        </form>
      )}

      {step === 'secret' && enroll && (
        <div>
          <p className="small muted">{t('mfa.secretHint')}</p>
          <div className="field">
            <label className="field-label" htmlFor="mfa-secret">{t('mfa.secretLabel')}</label>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <input
                id="mfa-secret"
                className="input"
                readOnly
                value={enroll.secret}
                style={{ fontFamily: 'monospace' }}
                onFocus={(e) => e.currentTarget.select()}
              />
              <button type="button" className="btn" onClick={() => copy(enroll.secret)}>
                {copied ? t('mfa.copied') : t('mfa.copy')}
              </button>
            </div>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="mfa-link">{t('mfa.otpauthLabel')}</label>
            <input
              id="mfa-link"
              className="input"
              readOnly
              value={enroll.otpauthUri}
              style={{ fontFamily: 'monospace', fontSize: '0.75rem' }}
              onFocus={(e) => e.currentTarget.select()}
            />
          </div>
          <button type="button" className="btn btn-primary" onClick={() => setStep('confirm')}>
            {t('mfa.addedIt')}
          </button>
        </div>
      )}

      {step === 'confirm' && (
        <form onSubmit={confirm} noValidate>
          <p className="small muted">{t('mfa.confirmHint')}</p>
          <div className="field">
            <label className="field-label" htmlFor="mfa-confirm-code">{t('mfa.codeLabel')}</label>
            <input
              id="mfa-confirm-code"
              className="input"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/[^0-9]/g, ''))}
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder={t('mfa.codePlaceholder')}
              autoFocus
              required
            />
          </div>
          <button type="submit" className="btn btn-primary" disabled={busy || !code}>
            {busy ? t('mfa.confirming') : t('mfa.verify')}
          </button>
        </form>
      )}

      {step === 'codes' && codes && (
        <div>
          <p className="small muted">{t('mfa.codesHint')}</p>
          <ul
            className="mfa-codes"
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(2, 1fr)',
              gap: '0.25rem 1.5rem',
              margin: '0.75rem 0',
              padding: 0,
              listStyle: 'none',
              fontFamily: 'monospace',
            }}
          >
            {codes.codes.map((c) => <li key={c}>{c}</li>)}
          </ul>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button type="button" className="btn" onClick={() => copy(codes.codes.join('\n'))}>
              {copied ? t('mfa.copied') : t('mfa.copyCodes')}
            </button>
            <button type="button" className="btn btn-primary" onClick={onDone}>
              {t('mfa.savedCodes')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

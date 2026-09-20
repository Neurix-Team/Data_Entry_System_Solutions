import { ChangeEvent, FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { authApi, mfaApi } from '../api/auth';
import { extractError } from '../api/client';
import { avatarUrl, profileApi } from '../api/profile';
import type { MfaRecoveryCodes, MfaStatus } from '../api/types';
import { Avatar } from '../components/Avatar';
import { MfaSetup } from '../components/auth/MfaSetup';
import { PasswordInput } from '../components/PasswordInput';
import { useToast } from '../components/toast/ToastContext';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';

const MAX_MB = 2;
const ALLOWED = ['image/png', 'image/jpeg', 'image/jpg', 'image/webp', 'image/gif'];

export function ProfilePage() {
  const { user, refresh } = useAuth();
  const { t, lang } = useT();
  const toast = useToast();
  const isAr = lang === 'ar';

  const [displayName, setDisplayName] = useState<string>(user?.displayName ?? '');
  const [email, setEmail] = useState<string>(user?.email ?? '');
  const [phone, setPhone] = useState<string>(user?.phone ?? '');
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [changingPassword, setChangingPassword] = useState(false);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  const fileRef = useRef<HTMLInputElement | null>(null);
  const [photoBusy, setPhotoBusy] = useState<'upload' | 'remove' | null>(null);
  const [photoError, setPhotoError] = useState<string | null>(null);

  // --- two-factor state -------------------------------------------------------------
  const [mfaStatus, setMfaStatus] = useState<MfaStatus | null>(null);
  const [mfaBusy, setMfaBusy] = useState(false);
  const [mfaError, setMfaError] = useState<string | null>(null);
  const [showEnroll, setShowEnroll] = useState(false);
  const [showDisable, setShowDisable] = useState(false);
  const [disableCode, setDisableCode] = useState('');
  const [showRegen, setShowRegen] = useState(false);
  const [regenPassword, setRegenPassword] = useState('');
  const [freshCodes, setFreshCodes] = useState<MfaRecoveryCodes | null>(null);

  const currentSrc = useMemo(
    () => (user ? avatarUrl(user.id, user.avatarUpdatedAt) : null),
    [user],
  );

  if (!user) return null;

  const displayFinal = displayName.trim() || user.username;
  const dirty =
    (displayName.trim() || '') !== (user.displayName ?? '') ||
    (email.trim() || '') !== (user.email ?? '') ||
    (phone.trim() || '') !== (user.phone ?? '');

  async function onSaveProfile(e: FormEvent) {
    e.preventDefault();
    if (!dirty) {
      setProfileError(t('profile.noChanges'));
      return;
    }
    setProfileError(null);
    setSavingProfile(true);
    try {
      await authApi.updateMe({
        displayName: displayName.trim(),
        email: email.trim() || null,
        phone: phone.trim() || null,
      });
      await refresh();
      toast.success(t('profile.profileSaved'));
    } catch (err) {
      const msg = extractError(err, t('common.somethingWrong'));
      setProfileError(msg);
      toast.error(msg);
    } finally {
      setSavingProfile(false);
    }
  }

  async function onChangePassword(e: FormEvent) {
    e.preventDefault();
    setPasswordError(null);
    if (newPassword.length < 8) {
      setPasswordError(t('profile.passwordMinLength'));
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError(t('profile.passwordMismatch'));
      return;
    }
    setChangingPassword(true);
    try {
      await authApi.changePassword({ currentPassword, newPassword });
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      toast.success(t('profile.passwordSaved'));
    } catch (err) {
      const msg = extractError(err, t('common.somethingWrong'));
      setPasswordError(msg);
      toast.error(msg);
    } finally {
      setChangingPassword(false);
    }
  }

  // --- two-factor handlers ----------------------------------------------------------

  const isPrivileged = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';

  useEffect(() => {
    mfaApi.status().then(setMfaStatus).catch(() => setMfaStatus(null));
  }, []);

  async function reloadMfa() {
    try {
      setMfaStatus(await mfaApi.status());
    } catch { /* keep the last known state */ }
  }

  async function onDisableMfa(e: FormEvent) {
    e.preventDefault();
    setMfaError(null);
    if (!disableCode.trim()) return;
    setMfaBusy(true);
    try {
      await mfaApi.disable(disableCode.trim());
      toast.success(t('mfa.disabled'));
      setShowDisable(false);
      setDisableCode('');
      await reloadMfa();
    } catch (err) {
      setMfaError(extractError(err, t('mfa.wrongCode')));
    } finally {
      setMfaBusy(false);
    }
  }

  async function onRegenCodes(e: FormEvent) {
    e.preventDefault();
    setMfaError(null);
    if (!regenPassword) return;
    setMfaBusy(true);
    try {
      const res = await mfaApi.regenerateRecoveryCodes(regenPassword);
      setFreshCodes(res);
      setShowRegen(false);
      setRegenPassword('');
      toast.success(t('mfa.codesRegenerated'));
      await reloadMfa();
    } catch (err) {
      setMfaError(extractError(err, t('common.somethingWrong')));
    } finally {
      setMfaBusy(false);
    }
  }

  async function onEnrollDone() {
    setShowEnroll(false);
    await reloadMfa();
    toast.success(t('mfa.enrolled'));
  }

  async function onPickPhoto(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (!ALLOWED.includes(file.type)) {
      setPhotoError(t('profile.photoInvalid'));
      return;
    }
    if (file.size > MAX_MB * 1024 * 1024) {
      setPhotoError(t('profile.photoTooLarge'));
      return;
    }
    setPhotoError(null);
    setPhotoBusy('upload');
    try {
      await profileApi.upload(file);
      await refresh();
      toast.success(t('profile.photoUploaded'));
    } catch (err) {
      const msg = extractError(err, t('profile.photoUploadFailed'));
      setPhotoError(msg);
      toast.error(msg);
    } finally {
      setPhotoBusy(null);
    }
  }

  async function onRemovePhoto() {
    setPhotoError(null);
    setPhotoBusy('remove');
    try {
      await profileApi.remove();
      await refresh();
      toast.success(t('profile.photoRemoved'));
    } catch (err) {
      const msg = extractError(err, t('profile.photoRemoveFailed'));
      setPhotoError(msg);
      toast.error(msg);
    } finally {
      setPhotoBusy(null);
    }
  }

  const memberSince = user.createdAt
    ? new Date(user.createdAt).toLocaleDateString(isAr ? 'ar-EG' : 'en-US', {
        year: 'numeric',
        month: 'long',
        day: 'numeric',
      })
    : '—';

  return (
    <div className="profile-page">
      <div className="profile-page-hero">
        <Avatar name={displayFinal} src={currentSrc} size="xl" />
        <div className="profile-hero-text">
          <div className="profile-hero-name">{displayFinal}</div>
          <div className="profile-hero-handle">@{user.username}</div>
          <div className="profile-hero-meta">
            <span className="profile-hero-chip" data-role={user.role}>
              {user.role === 'SUPER_ADMIN'
                ? isAr ? 'مدير عام' : 'Super Admin'
                : user.role === 'ADMIN'
                  ? isAr ? 'مدير' : 'Admin'
                  : isAr ? 'موظف إدخال' : 'Agent'}
            </span>
            {user.team?.name && (
              <span className="profile-hero-chip">{user.team.name}</span>
            )}
          </div>
        </div>
      </div>

      <div className="profile-grid">
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          <section className="profile-card">
            <h3>{t('profile.photoSection')}</h3>
            <p className="card-hint">{t('profile.photoHint')}</p>
            {photoError && (
              <div className="alert alert-error" role="alert" style={{ marginBottom: '0.75rem' }}>
                {photoError}
              </div>
            )}
            <div className="profile-photo-block">
              <Avatar name={displayFinal} src={currentSrc} size="lg" />
              <div className="profile-photo-actions">
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => fileRef.current?.click()}
                  disabled={photoBusy !== null}
                >
                  {photoBusy === 'upload'
                    ? t('profile.uploading')
                    : currentSrc ? t('profile.changePhoto') : t('profile.uploadPhoto')}
                </button>
                {currentSrc && (
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={onRemovePhoto}
                    disabled={photoBusy !== null}
                  >
                    {photoBusy === 'remove' ? t('profile.removing') : t('profile.removePhoto')}
                  </button>
                )}
              </div>
            </div>
            <input
              ref={fileRef}
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              onChange={onPickPhoto}
              style={{ display: 'none' }}
            />
          </section>

          <section className="profile-card">
            <h3>{t('profile.accountSection')}</h3>
            <p className="card-hint">{t('profile.subtitle')}</p>
            {profileError && (
              <div className="alert alert-error" role="alert" style={{ marginBottom: '0.75rem' }}>
                {profileError}
              </div>
            )}
            <form onSubmit={onSaveProfile}>
              <div className="profile-field-grid">
                <label className="full">
                  {t('profile.displayName')}
                  <input
                    type="text"
                    value={displayName}
                    onChange={(e) => setDisplayName(e.target.value)}
                    maxLength={150}
                    autoComplete="name"
                  />
                </label>
                <label>
                  {t('profile.username')}
                  <input type="text" value={user.username} readOnly />
                </label>
                <label>
                  {t('profile.role')}
                  <input type="text" value={user.role} readOnly />
                </label>
                <label>
                  {t('profile.email')}
                  <input
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    maxLength={200}
                    autoComplete="email"
                  />
                </label>
                <label>
                  {t('profile.phone')}
                  <input
                    type="tel"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    maxLength={40}
                    autoComplete="tel"
                  />
                </label>
              </div>
              <div className="profile-actions-row">
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={savingProfile || !dirty}
                >
                  {savingProfile ? t('profile.saving') : t('profile.saveProfile')}
                </button>
              </div>
            </form>
          </section>

          <section className="profile-card">
            <h3>{t('profile.passwordSection')}</h3>
            {passwordError && (
              <div className="alert alert-error" role="alert" style={{ marginBottom: '0.75rem' }}>
                {passwordError}
              </div>
            )}
            <form onSubmit={onChangePassword}>
              <div className="profile-field-grid">
                <label className="full">
                  {t('profile.currentPassword')}
                  <PasswordInput
                    value={currentPassword}
                    onChange={(e) => setCurrentPassword(e.target.value)}
                    autoComplete="current-password"
                    required
                  />
                </label>
                <label>
                  {t('profile.newPassword')}
                  <PasswordInput
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    autoComplete="new-password"
                    minLength={8}
                    required
                  />
                </label>
                <label>
                  {t('profile.confirmPassword')}
                  <PasswordInput
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    autoComplete="new-password"
                    minLength={8}
                    required
                  />
                </label>
              </div>
              <div className="profile-actions-row">
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={
                    changingPassword
                    || !currentPassword
                    || !newPassword
                    || !confirmPassword
                  }
                >
                  {changingPassword ? t('profile.changingPassword') : t('profile.changePassword')}
                </button>
              </div>
            </form>
          </section>

          <section className="profile-card">
            <h3>{t('mfa.title')}</h3>
            <p className="small muted">{t('mfa.sectionHint')}</p>

            {mfaError && (
              <div className="alert alert-error" role="alert" style={{ marginTop: '0.75rem' }}>
                {mfaError}
              </div>
            )}

            {mfaStatus && !showEnroll && (
              <ul className="profile-meta-list" style={{ margin: '0.75rem 0' }}>
                <li>
                  <dt>{t('mfa.title')}</dt>
                  <dd>
                    {mfaStatus.enrolled
                      ? (mfaStatus.enabledAt
                          ? t('mfa.enabledSince', { date: new Date(mfaStatus.enabledAt).toLocaleDateString() })
                          : t('mfa.enabled'))
                      : (isPrivileged ? t('mfa.requiredBadge') : t('mfa.optional'))}
                  </dd>
                </li>
                {mfaStatus.enrolled && (
                  <li>
                    <dt>{t('mfa.codesTitle')}</dt>
                    <dd>{t('mfa.recoveryLeft', { count: mfaStatus.recoveryCodesRemaining })}</dd>
                  </li>
                )}
              </ul>
            )}

            {!mfaStatus && <p className="small muted">…</p>}

            {mfaStatus && !mfaStatus.enrolled && !showEnroll && (
              <button type="button" className="btn btn-primary" onClick={() => setShowEnroll(true)}>
                {t('mfa.enable')}
              </button>
            )}

            {mfaStatus && mfaStatus.enrolled && !showDisable && !showRegen && !freshCodes && (
              <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
                <button type="button" className="btn" onClick={() => { setShowRegen(true); setMfaError(null); }}>
                  {t('mfa.regenerate')}
                </button>
                {isPrivileged ? (
                  <span className="small muted">{t('mfa.disableBlocked')}</span>
                ) : (
                  <button type="button" className="btn" onClick={() => { setShowDisable(true); setMfaError(null); }}>
                    {t('mfa.disable')}
                  </button>
                )}
              </div>
            )}

            {showEnroll && (
              <MfaSetup onDone={onEnrollDone} onCancel={() => setShowEnroll(false)} />
            )}

            {showDisable && (
              <form onSubmit={onDisableMfa} noValidate>
                <p className="small muted">{t('mfa.disableHint')}</p>
                <div className="field">
                  <label className="field-label" htmlFor="mfa-disable-code">{t('mfa.codeLabel')}</label>
                  <input
                    id="mfa-disable-code"
                    className="input"
                    value={disableCode}
                    onChange={(e) => setDisableCode(e.target.value.replace(/[^0-9]/g, ''))}
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    required
                  />
                </div>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button type="submit" className="btn btn-primary" disabled={mfaBusy || !disableCode}>
                    {t('mfa.disableConfirm')}
                  </button>
                  <button type="button" className="btn" onClick={() => setShowDisable(false)}>
                    {t('common.cancel')}
                  </button>
                </div>
              </form>
            )}

            {showRegen && (
              <form onSubmit={onRegenCodes} noValidate>
                <p className="small muted">{t('mfa.regenerateHint')}</p>
                <div className="field">
                  <label className="field-label" htmlFor="mfa-regen-password">{t('mfa.passwordLabel')}</label>
                  <PasswordInput
                    id="mfa-regen-password"
                    value={regenPassword}
                    onChange={(e) => setRegenPassword(e.target.value)}
                    autoComplete="current-password"
                    required
                  />
                </div>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                  <button type="submit" className="btn btn-primary" disabled={mfaBusy || !regenPassword}>
                    {t('mfa.regenerate')}
                  </button>
                  <button type="button" className="btn" onClick={() => setShowRegen(false)}>
                    {t('common.cancel')}
                  </button>
                </div>
              </form>
            )}

            {freshCodes && (
              <div>
                <p className="small muted">{t('mfa.codesHint')}</p>
                <ul
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
                  {freshCodes.codes.map((c) => <li key={c}>{c}</li>)}
                </ul>
                <button type="button" className="btn" onClick={() => setFreshCodes(null)}>
                  {t('mfa.savedCodes')}
                </button>
              </div>
            )}
          </section>
        </div>

        <aside className="profile-card" style={{ alignSelf: 'flex-start' }}>
          <h3>{t('profile.accountSection')}</h3>
          <ul className="profile-meta-list">
            <li>
              <dt>{t('profile.username')}</dt>
              <dd>@{user.username}</dd>
            </li>
            <li>
              <dt>{t('profile.role')}</dt>
              <dd>{user.role}</dd>
            </li>
            {user.team?.name && (
              <li>
                <dt>{t('profile.team')}</dt>
                <dd>{user.team.name}</dd>
              </li>
            )}
            <li>
              <dt>{t('profile.memberSince')}</dt>
              <dd>{memberSince}</dd>
            </li>
          </ul>
        </aside>
      </div>
    </div>
  );
}

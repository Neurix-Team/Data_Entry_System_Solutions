import { ChangeEvent, useRef, useState } from 'react';
import { extractError } from '../api/client';
import { avatarUrl, profileApi } from '../api/profile';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';
import { Avatar } from './Avatar';
import { useToast } from './toast/ToastContext';

interface Props {
  onClose: () => void;
}

const MAX_MB = 2;
const ALLOWED = ['image/png', 'image/jpeg', 'image/jpg', 'image/webp', 'image/gif'];

/**
 * Profile settings modal — currently focused on the avatar. Opens from the topbar user chip.
 * Shows a large preview of the current avatar and lets the user upload a new picture or
 * remove the existing one. Server-side rules enforced independently.
 */
export function ProfileModal({ onClose }: Props) {
  const { user, refresh } = useAuth();
  const { lang } = useT();
  const toast = useToast();
  const isAr = lang === 'ar';
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState<'upload' | 'remove' | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (!user) return null;

  const currentSrc = avatarUrl(user.id, user.avatarUpdatedAt);

  async function onPick(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    // Reset the input so re-picking the same file still fires onChange
    e.target.value = '';
    if (!file) return;

    if (!ALLOWED.includes(file.type)) {
      setError(isAr ? 'الصورة لازم تكون PNG أو JPEG أو WEBP أو GIF.' : 'Image must be PNG, JPEG, WEBP, or GIF.');
      return;
    }
    if (file.size > MAX_MB * 1024 * 1024) {
      setError(isAr ? `الحجم الأقصى ${MAX_MB} ميجا.` : `Maximum file size is ${MAX_MB} MB.`);
      return;
    }

    setError(null);
    setBusy('upload');
    try {
      await profileApi.upload(file);
      await refresh();
      toast.success(isAr ? 'تم رفع الصورة 🎉' : 'Photo uploaded 🎉');
    } catch (err) {
      const msg = extractError(err, isAr ? 'رفع الصورة فشل. حاول تاني.' : 'Upload failed. Try again.');
      setError(msg);
      toast.error(msg);
    } finally {
      setBusy(null);
    }
  }

  async function onRemove() {
    setError(null);
    setBusy('remove');
    try {
      await profileApi.remove();
      await refresh();
      toast.success(isAr ? 'تم حذف الصورة' : 'Photo removed');
    } catch (err) {
      const msg = extractError(err, isAr ? 'حذف الصورة فشل.' : 'Remove failed.');
      setError(msg);
      toast.error(msg);
    } finally {
      setBusy(null);
    }
  }

  const displayName = user.displayName || user.username;

  return (
    <div className="modal-backdrop" onClick={onClose} role="presentation">
      <div className="modal profile-modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label={isAr ? 'الملف الشخصي' : 'Profile'}>
        <div className="modal-header">
          <h3>{isAr ? 'الملف الشخصي' : 'Profile'}</h3>
          <button className="icon-btn" onClick={onClose} aria-label={isAr ? 'إغلاق' : 'Close'}>×</button>
        </div>

        <div className="profile-body">
          <div className="profile-avatar-wrap">
            <Avatar name={displayName} size="xl" src={currentSrc} />
            <button
              type="button"
              className="profile-avatar-edit"
              onClick={() => fileRef.current?.click()}
              aria-label={isAr ? 'تغيير الصورة' : 'Change photo'}
              disabled={busy !== null}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M4 8l2-3h6l2 3h4a2 2 0 012 2v9a2 2 0 01-2 2H4a2 2 0 01-2-2v-9a2 2 0 012-2z" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round"/>
                <circle cx="12" cy="13" r="3.5" stroke="currentColor" strokeWidth="1.7"/>
              </svg>
            </button>
          </div>

          <div className="profile-name">{displayName}</div>
          <div className="profile-username">@{user.username}</div>

          {error && <div className="alert alert-error profile-error" role="alert">{error}</div>}

          <div className="profile-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => fileRef.current?.click()}
              disabled={busy !== null}
            >
              {busy === 'upload'
                ? (isAr ? 'جارٍ الرفع…' : 'Uploading…')
                : currentSrc
                  ? (isAr ? 'تغيير الصورة' : 'Change photo')
                  : (isAr ? 'ارفع صورة' : 'Upload photo')}
            </button>
            {currentSrc && (
              <button
                type="button"
                className="btn btn-secondary"
                onClick={onRemove}
                disabled={busy !== null}
              >
                {busy === 'remove'
                  ? (isAr ? 'جارٍ الحذف…' : 'Removing…')
                  : (isAr ? 'حذف الصورة' : 'Remove photo')}
              </button>
            )}
          </div>

          <p className="profile-hint">
            {isAr
              ? `PNG أو JPG أو WEBP أو GIF. الحد الأقصى ${MAX_MB} ميجا.`
              : `PNG, JPG, WEBP, or GIF. Up to ${MAX_MB} MB.`}
          </p>

          <input
            ref={fileRef}
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            onChange={onPick}
            style={{ display: 'none' }}
          />
        </div>
      </div>
    </div>
  );
}

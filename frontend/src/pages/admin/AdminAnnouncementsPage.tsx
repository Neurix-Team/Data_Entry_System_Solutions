import { useCallback, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { adminFeaturesApi } from '../../api/resources';
import type { Announcement } from '../../api/types';
import { useConfirm } from '../../components/ConfirmDialog';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

const AUDIENCES: Array<Announcement['audience']> = ['ALL', 'USERS', 'ADMINS'];

/** C2: one message, the whole team — in-app + browser push, plus a history. */
export function AdminAnnouncementsPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const [rows, setRows] = useState<Announcement[] | null>(null);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [audience, setAudience] = useState<Announcement['audience']>('ALL');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    adminFeaturesApi.listAnnouncements()
      .then((r) => { setRows(r); setError(null); })
      .catch((e) => setError(extractError(e)));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function send() {
    if (!title.trim() || !body.trim() || sending) return;
    const ok = await confirm({
      title: t('announcements.confirmTitle'),
      message: t('announcements.confirmMsg', { audience: t(`announcements.audience.${audience}`) }),
      confirmLabel: t('announcements.send'),
    });
    if (!ok) return;
    setSending(true);
    try {
      await adminFeaturesApi.announce({ title: title.trim(), body: body.trim(), audience });
      toast.success(t('announcements.sent'));
      setTitle('');
      setBody('');
      load();
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('announcements.title')}</h1>
          <p className="subtitle">{t('announcements.subtitle')}</p>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        {error && <div className="alert alert-error">{error}</div>}
        <div className="field">
          <label className="field-label">{t('announcements.titleLabel')} <span className="req">*</span></label>
          <input className="input" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="field">
          <label className="field-label">{t('announcements.bodyLabel')} <span className="req">*</span></label>
          <textarea
            className="input"
            rows={4}
            value={body}
            maxLength={4000}
            onChange={(e) => setBody(e.target.value)}
          />
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div className="field" style={{ minWidth: 180 }}>
            <label className="field-label">{t('announcements.audienceLabel')}</label>
            <select className="select" value={audience} onChange={(e) => setAudience(e.target.value as Announcement['audience'])}>
              {AUDIENCES.map((a) => (
                <option key={a} value={a}>{t(`announcements.audience.${a}`)}</option>
              ))}
            </select>
          </div>
          <button
            type="button"
            className="btn btn-primary"
            disabled={sending || !title.trim() || !body.trim()}
            onClick={send}
            style={{ marginBottom: 14 }}
          >
            📣 {sending ? t('common.loading') : t('announcements.send')}
          </button>
        </div>
      </div>

      <div className="page-header">
        <div><h2 style={{ fontSize: 16, margin: 0 }}>{t('announcements.history')}</h2></div>
      </div>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>{t('announcements.titleLabel')}</th>
              <th>{t('announcements.audienceLabel')}</th>
              <th>{t('announcements.by')}</th>
              <th>{t('announcements.at')}</th>
            </tr>
          </thead>
          <tbody>
            {rows === null ? (
              <tr><td colSpan={4} className="muted">{t('common.loading')}</td></tr>
            ) : rows.length === 0 ? (
              <tr><td colSpan={4} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                {t('announcements.empty')}
              </td></tr>
            ) : rows.map((a) => (
              <tr key={a.id}>
                <td>
                  <div style={{ fontWeight: 600 }}>{a.title}</div>
                  <div className="muted small" style={{ whiteSpace: 'pre-wrap' }}>{a.body}</div>
                </td>
                <td><span className="small">{t(`announcements.audience.${a.audience}`)}</span></td>
                <td><span className="small">{a.createdByName || '—'}</span></td>
                <td><span className="muted small">
                  {new Date(a.createdAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)}
                </span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
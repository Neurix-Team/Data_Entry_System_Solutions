import { useNavigate } from 'react-router-dom';
import type { RecentTicket } from '../../../api/types';
import { StatusPill } from '../../../components/StatusPill';
import { useT } from '../../../i18n';

interface Props {
  items: RecentTicket[];
}

export function RecentActivity({ items }: Props) {
  const { t, lang } = useT();
  const nav = useNavigate();
  if (items.length === 0) {
    return <div className="udash-empty">{t('user.dashboard.emptyRecent')}</div>;
  }
  return (
    <div className="udash-recent">
      {items.map((it) => (
        <div
          key={it.id}
          className="udash-recent-item"
          role="button"
          tabIndex={0}
          onClick={() => nav(`/my-tickets?ticket=${it.id}`)}
          onKeyDown={(e) => { if (e.key === 'Enter') nav(`/my-tickets?ticket=${it.id}`); }}
        >
          <div className="udash-recent-mark">#{it.id}</div>
          <div className="udash-recent-body">
            <div className="udash-recent-title">
              {it.title || t('user.dashboard.untitled')}
            </div>
            <div className="udash-recent-meta">
              {it.departmentName}
              {it.subcategoryName ? ` · ${it.subcategoryName}` : ''}
            </div>
          </div>
          <StatusPill status={it.status} />
          <span className="udash-recent-time">{relativeTime(it.submittedAt, lang)}</span>
        </div>
      ))}
    </div>
  );
}

function relativeTime(iso: string, lang: string): string {
  const now = Date.now();
  const then = new Date(iso).getTime();
  const diffSec = Math.floor((now - then) / 1000);
  if (diffSec < 60) return lang === 'ar' ? 'الآن' : 'just now';
  const min = Math.floor(diffSec / 60);
  if (min < 60) return lang === 'ar' ? `منذ ${min} دقيقة` : `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return lang === 'ar' ? `منذ ${hr} ساعة` : `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 30) return lang === 'ar' ? `منذ ${day} يوم` : `${day}d ago`;
  return new Date(iso).toLocaleDateString(lang === 'ar' ? 'ar-EG' : undefined);
}

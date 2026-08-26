import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { extractError } from '../../api/client';
import { projectFoldersApi } from '../../api/resources';
import type { ProjectFolderSummary } from '../../api/types';
import { useAuth } from '../../context/AuthContext';
import { IconFolder, IconAlert, IconCheck } from '../../components/Icons';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';

/**
 * Folder grid — one card per project. Users see the projects they're a member of,
 * with counts scoped to their own tickets. Admins/super-admins see every project
 * across the team with counts across every submitter.
 */
export function ProjectFoldersPage() {
  const { t, lang } = useT();
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';

  const [folders, setFolders] = useState<ProjectFolderSummary[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState('');

  useEffect(() => {
    let alive = true;
    setLoading(true);
    projectFoldersApi
      .list()
      .then((rows) => { if (alive) setFolders(rows); })
      .catch((e) => { if (alive) setError(extractError(e)); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, []);

  const filtered = useMemo(() => {
    if (!folders) return [];
    const term = q.trim().toLowerCase();
    if (!term) return folders;
    return folders.filter((f) => {
      const name = pickLocalized(f, 'projectName', lang);
      const sub = pickLocalized(f, 'subtitle', lang);
      return `${name} ${sub}`.toLowerCase().includes(term);
    });
  }, [folders, q, lang]);

  const totals = useMemo(() => {
    if (!folders) return { total: 0, pending: 0, approved: 0 };
    return folders.reduce(
      (acc, f) => ({
        total: acc.total + f.total,
        pending: acc.pending + f.pending,
        approved: acc.approved + f.approved,
      }),
      { total: 0, pending: 0, approved: 0 },
    );
  }, [folders]);

  const heading = isAdmin
    ? (lang === 'ar' ? 'مجلدات المشاريع' : 'Project Folders')
    : (lang === 'ar' ? 'مجلداتي' : 'My Project Folders');
  const subtitle = isAdmin
    ? (lang === 'ar'
      ? 'كل مشروع مجلد يحوي التذاكر اللي اليوزر بيشتغل عليها. افتح المجلد لتراجع وتوافق.'
      : 'Every project is a folder. Open one to review your team\'s entries and approve them.')
    : (lang === 'ar'
      ? 'شوف كل التذاكر اللي رفعتها في كل مشروع، وحالتها بعد ما يوافق عليها المشرف.'
      : 'Every project you\'re on is a folder — track everything you\'ve submitted and see when the admin approves it.');

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{heading}</h1>
          <p className="subtitle">{subtitle}</p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
        <MiniStat
          label={lang === 'ar' ? 'إجمالي التذاكر' : 'Total entries'}
          value={totals.total}
          icon={<IconFolder size={16} />}
          tone="blue"
        />
        <MiniStat
          label={lang === 'ar' ? 'بانتظار الموافقة' : 'Pending approval'}
          value={totals.pending}
          icon={<IconAlert size={16} />}
          tone="yellow"
        />
        <MiniStat
          label={lang === 'ar' ? 'موافَق عليها' : 'Approved'}
          value={totals.approved}
          icon={<IconCheck size={16} />}
          tone="amber"
        />
      </div>

      <div className="toolbar">
        <input
          className="input grow"
          type="search"
          placeholder={lang === 'ar' ? 'ابحث بالاسم…' : 'Search folders…'}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        {q && (
          <button className="btn btn-ghost btn-sm" onClick={() => setQ('')}>
            {t('common.clear')}
          </button>
        )}
        {folders && (
          <span className="muted small" style={{ marginInlineStart: 'auto' }}>
            {filtered.length} / {folders.length}
          </span>
        )}
      </div>

      {loading ? (
        <div className="card" style={{ padding: '1.5rem' }}>
          <span className="muted">{t('common.loading')}</span>
        </div>
      ) : filtered.length === 0 ? (
        <div className="card" style={{ padding: '2rem', textAlign: 'center' }}>
          <IconFolder size={40} />
          <p className="muted" style={{ marginTop: '0.75rem' }}>
            {q
              ? (lang === 'ar' ? 'مفيش مجلدات مطابقة' : 'No folders match your search')
              : isAdmin
                ? (lang === 'ar' ? 'مفيش مشاريع لسه — اعمل مشروع الأول' : 'No projects yet — create one first')
                : (lang === 'ar' ? 'لسه ما اتخصصلكش مشروع' : 'You haven\'t been assigned to a project yet')}
          </p>
        </div>
      ) : (
        <div className="dept-grid">
          {filtered.map((f, idx) => (
            <FolderCard
              key={f.projectId}
              folder={f}
              colorIndex={(idx % 6) + 1}
              isAdmin={isAdmin}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function FolderCard({
  folder, colorIndex, isAdmin,
}: {
  folder: ProjectFolderSummary;
  colorIndex: number;
  isAdmin: boolean;
}) {
  const { lang } = useT();
  const name = pickLocalized(folder, 'projectName', lang);
  const subtitle = pickLocalized(folder, 'subtitle', lang);

  const openTo = isAdmin
    ? `/admin/project-folders/${folder.projectId}`
    : `/project-folders/${folder.projectId}`;

  return (
    <Link
      to={openTo}
      className="dept-card"
      data-color={colorIndex}
      style={{ display: 'block', textDecoration: 'none', color: 'inherit' }}
      aria-label={lang === 'ar' ? `افتح مجلد ${name}` : `Open folder ${name}`}
    >
      <div className="dept-card-head">
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
          <IconFolder size={22} />
          <div>
            <div className="dept-card-name" style={{ lineHeight: 1.1 }}>{name}</div>
            {subtitle && <div className="muted small">{subtitle}</div>}
          </div>
        </div>
      </div>

      <div className="dept-card-mini-grid">
        <div className="dept-mini-stat tint-a">
          <div className="dept-mini-stat-label">
            {lang === 'ar' ? 'بانتظار الموافقة' : 'Pending'}
          </div>
          <div className="dept-mini-stat-value">{folder.pending}</div>
        </div>
        <div className="dept-mini-stat">
          <div className="dept-mini-stat-label">
            {lang === 'ar' ? 'موافَق عليها' : 'Approved'}
          </div>
          <div className="dept-mini-stat-value">{folder.approved}</div>
        </div>
      </div>

      <div className="row-between">
        <span className="muted small">
          {lang === 'ar' ? `${folder.total} تذكرة` : `${folder.total} entries`}
        </span>
        <span className="muted small" aria-hidden="true">
          {lang === 'ar' ? 'اضغط للفتح ←' : '→ Open'}
        </span>
      </div>
    </Link>
  );
}

function MiniStat({
  label, value, icon, tone,
}: {
  label: string; value: number; icon: React.ReactNode;
  tone: 'yellow' | 'blue' | 'amber';
}) {
  return (
    <div className={`stat-card hero-${tone}`}>
      <div className="stat-card-header">
        <span className="stat-card-label">{label}</span>
        <span className="stat-card-icon">{icon}</span>
      </div>
      <div className="stat-card-value">{value}</div>
    </div>
  );
}

import { useCallback, useEffect, useState } from 'react';
import { API_BASE, extractError } from '../../api/client';
import { superApi } from '../../api/super';
import type { DatasetPublishResult, DatasetRow, DatasetStats } from '../../api/super';
import { IconCheck, IconDatabase } from '../../components/Icons';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

export function SuperDatasetPage() {
  const { lang, t } = useT();
  const toast = useToast();
  const [items, setItems] = useState<DatasetRow[]>([]);
  const [total, setTotal] = useState(0);
  const [cursor, setCursor] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [publishing, setPublishing] = useState(false);
  const [lastPublish, setLastPublish] = useState<DatasetPublishResult | null>(null);
  const [stats, setStats] = useState<DatasetStats | null>(null);
  const ar = lang === 'ar';

  const load = useCallback(async (next?: number) => {
    setLoading(true);
    try {
      const [page, latestStats] = await Promise.all([
        superApi.dataset(next, 50),
        next ? Promise.resolve(null) : superApi.datasetStats(),
      ]);
      setItems((old) => next ? [...old, ...page.items] : page.items);
      setTotal(page.total);
      setCursor(page.nextCursor);
      setHasMore(page.hasMore);
      if (latestStats) setStats(latestStats);
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  async function publish() {
    setPublishing(true);
    try {
      const result = await superApi.publishDataset();
      setLastPublish(result);
      toast.success(ar
        ? `تم تجهيز ${result.inserted} جديد وتحديث ${result.updated}`
        : `Published ${result.inserted} new and updated ${result.updated}`);
      await load();
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setPublishing(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <IconDatabase size={24} /> {ar ? 'بيانات السيرفر الجاهزة' : 'Published server dataset'}
          </h1>
          <p className="subtitle">
            {ar
              ? 'نسخة مسطحة وثابتة يمكن سحبها لاحقاً من الـ API، من غير تكرار التذكرة.'
              : 'A flat, durable snapshot ready for downstream API consumers, without duplicate tickets.'}
          </p>
        </div>
        <button className="btn btn-primary" type="button" onClick={publish} disabled={publishing}>
          <IconCheck size={15} />{' '}
          {publishing ? (ar ? 'جاري التجهيز…' : 'Publishing…') : (ar ? 'رفع الجديد للسيرفر' : 'Publish new data')}
        </button>
      </div>

      {lastPublish && (
        <div className="alert alert-success" style={{ marginBottom: 16 }}>
          {ar
            ? `تم فحص ${lastPublish.scanned}: جديد ${lastPublish.inserted}، محدّث ${lastPublish.updated}، بدون تغيير ${lastPublish.unchanged}. الإجمالي ${lastPublish.total}.`
            : `Scanned ${lastPublish.scanned}: ${lastPublish.inserted} new, ${lastPublish.updated} updated, ${lastPublish.unchanged} unchanged. Total ${lastPublish.total}.`}
        </div>
      )}

      <div style={{
        display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))',
        gap: 16, marginBottom: 18,
      }}>
        <SummaryCard
          title={ar ? 'تم رفعه على السيرفر' : 'Published to server'}
          records={stats?.publishedRecords ?? total}
          files={stats?.publishedFiles ?? 0}
          ar={ar}
          background="linear-gradient(135deg, rgba(16,185,129,.18), rgba(34,197,94,.07))"
          border="rgba(16,185,129,.42)"
          accent="#059669"
        />
        <SummaryCard
          title={ar ? 'لسه محتاج رفع' : 'Still waiting to publish'}
          records={stats?.pendingRecords ?? 0}
          files={stats?.pendingFiles ?? 0}
          ar={ar}
          background="linear-gradient(135deg, rgba(245,158,11,.20), rgba(249,115,22,.07))"
          border="rgba(245,158,11,.48)"
          accent="#d97706"
        />
      </div>

      {!loading && items.length === 0 ? (
        <div className="card" style={{ padding: '2rem', textAlign: 'center' }}>
          {ar ? 'لا توجد بيانات منشورة بعد. اضغط رفع الجديد للسيرفر.' : 'No data has been published yet.'}
        </div>
      ) : (
        <div className="table-wrap card">
          <table className="data">
            <thead>
              <tr>
                <th>{ar ? 'العنوان' : 'Title'}</th>
                <th>{ar ? 'الفريق' : 'Team'}</th>
                <th>{ar ? 'المشروع' : 'Project'}</th>
                <th>{ar ? 'القسم' : 'Department'}</th>
                <th>{ar ? 'المستخدم' : 'User'}</th>
                <th>{ar ? 'المحتوى' : 'Content'}</th>
                <th>{ar ? 'المرفقات' : 'Attachments'}</th>
                <th>{ar ? 'آخر تجهيز' : 'Refreshed'}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => <DatasetTableRow key={row.id} row={row} ar={ar} />)}
            </tbody>
          </table>
        </div>
      )}

      {hasMore && (
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <button className="btn btn-secondary" type="button" disabled={loading || cursor == null}
                  onClick={() => cursor != null && load(cursor)}>
            {loading ? t('common.loading') : (ar ? 'تحميل المزيد' : 'Load more')}
          </button>
        </div>
      )}
    </div>
  );
}

function SummaryCard({ title, records, files, ar, background, border, accent }: {
  title: string;
  records: number;
  files: number;
  ar: boolean;
  background: string;
  border: string;
  accent: string;
}) {
  return (
    <div className="card" style={{ padding: '1.15rem 1.3rem', background, borderColor: border }}>
      <div style={{ color: accent, fontWeight: 700, marginBottom: 10 }}>{title}</div>
      <div style={{ display: 'flex', gap: 28, alignItems: 'end' }}>
        <div>
          <div style={{ fontSize: '1.75rem', lineHeight: 1, fontWeight: 800 }}>{records.toLocaleString()}</div>
          <div className="muted small" style={{ marginTop: 5 }}>{ar ? 'سجل بيانات' : 'data records'}</div>
        </div>
        <div>
          <div style={{ fontSize: '1.75rem', lineHeight: 1, fontWeight: 800 }}>{files.toLocaleString()}</div>
          <div className="muted small" style={{ marginTop: 5 }}>{ar ? 'ملف مرفق' : 'attached files'}</div>
        </div>
      </div>
    </div>
  );
}

function DatasetTableRow({ row, ar }: { row: DatasetRow; ar: boolean }) {
  const user = row.submittedByDisplayName || row.submittedByUsername || '—';
  return (
    <tr>
      <td><strong>#{row.sourceTicketId}</strong> · {row.title || '—'}</td>
      <td>{row.teamName || '—'}</td>
      <td>{row.projectName || '—'}</td>
      <td>{row.departmentName || '—'}</td>
      <td>
        <div>{user}</div>
        {row.submittedByEmail && <div className="muted small">{row.submittedByEmail}</div>}
        {row.submittedByPhone && <div className="muted small">{row.submittedByPhone}</div>}
      </td>
      <td><div className="truncate" style={{ maxWidth: 280 }} title={row.content || ''}>{row.content || '—'}</div></td>
      <td>
        {row.attachments.length === 0 ? '—' : row.attachments.map((doc) => (
          <div key={doc.id}>
            <a href={`${API_BASE}/tickets/${row.sourceTicketId}/documents/${doc.id}`} target="_blank" rel="noreferrer">
              {doc.name || doc.originalFilename}
            </a>
          </div>
        ))}
      </td>
      <td className="muted small">{new Date(row.refreshedAt).toLocaleString(ar ? 'ar-EG' : undefined)}</td>
    </tr>
  );
}

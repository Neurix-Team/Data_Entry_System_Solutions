import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { useReducedMotion } from 'motion/react';
import type { ExplorerStats } from '../../api/super';
import { IconDatabase, IconFolder, IconChart, IconCamera } from '../../components/Icons';
import { useT } from '../../i18n';
import './explorer-analytics.css';

function Count({ value, format }: { value: number; format: (n: number) => string }) {
  const reduced = useReducedMotion();
  const [display, setDisplay] = useState(0);
  const current = useRef(0);
  useEffect(() => {
    if (reduced) { current.current = value; setDisplay(value); return; }
    const start = performance.now(), initial = current.current;
    let frame: number;
    const tick = (now: number) => {
      const progress = Math.min(1, (now - start) / 650);
      current.current = initial + (value - initial) * (1 - Math.pow(1 - progress, 3));
      setDisplay(current.current);
      if (progress < 1) frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value, reduced]);
  return <span><span className="sr-only">{format(value)}</span><span aria-hidden="true">{format(display)}</span></span>;
}

export function ExplorerAnalytics({ stats, loading, filtered }: {
  stats: ExplorerStats | null; loading: boolean; filtered: boolean;
}) {
  const { lang } = useT();
  const ar = lang === 'ar';
  const number = (value: number) => Math.round(value).toLocaleString(ar ? 'ar-EG' : 'en-US');
  const bytes = (value: number) => {
    const unit = value < 1024 ? 0 : Math.min(4, Math.floor(Math.log(value) / Math.log(1024)));
    return `${(value / 1024 ** unit).toLocaleString(ar ? 'ar-EG' : 'en-US', { maximumFractionDigits: 1 })} ${['B', 'KB', 'MB', 'GB', 'TB'][unit]}`;
  };
  const types = [
    { key: 'pdf', label: 'PDF', count: stats?.pdfFiles ?? 0, color: '#e8a0a8' },
    { key: 'word', label: ar ? 'وورد' : 'Word', count: stats?.wordFiles ?? 0, color: '#8ccaf1' },
    { key: 'image', label: ar ? 'صور' : 'Images', count: stats?.imageFiles ?? 0, color: '#c1acf3' },
    { key: 'sheet', label: ar ? 'جداول وExcel' : 'Sheets & Excel', count: stats?.spreadsheetFiles ?? 0, color: '#88cfca' },
    { key: 'slides', label: ar ? 'عروض تقديمية' : 'Presentations', count: stats?.presentationFiles ?? 0, color: '#efc58d' },
    { key: 'other', label: ar ? 'ملفات أخرى' : 'Other files', count: stats?.otherFiles ?? 0, color: '#a5b3c3' },
  ];
  const total = stats?.totalFiles ?? 0;
  const share = (count: number) => total ? Math.round(count / total * 100) : 0;
  const cards = [
    { key: 'all', title: ar ? 'إجمالي الملفات' : 'Total files', value: total, icon: <IconFolder size={21} />, note: ar ? 'كل المرفقات ضمن النطاق الحالي' : 'Every attachment in this view', color: '#a9d6f5' },
    { key: 'pdf', title: ar ? 'ملفات PDF' : 'PDF documents', value: stats?.pdfFiles ?? 0, icon: <span className="ea-file-icon">PDF</span>, note: `${number(share(stats?.pdfFiles ?? 0))}% ${ar ? 'من الملفات' : 'of all files'}`, color: '#e8a0a8' },
    { key: 'word', title: ar ? 'ملفات وورد' : 'Word documents', value: stats?.wordFiles ?? 0, icon: <span className="ea-file-icon">W</span>, note: 'DOC · DOCX · DOCM', color: '#8ccaf1' },
    { key: 'image', title: ar ? 'الصور' : 'Images', value: stats?.imageFiles ?? 0, icon: <IconCamera size={21} />, note: 'JPG · PNG · WEBP +', color: '#c1acf3' },
    { key: 'size', title: ar ? 'حجم الملفات' : 'File volume', value: stats?.totalBytes ?? 0, icon: <IconDatabase size={21} />, note: ar ? 'إجمالي حجم المرفقات المسجل' : 'Combined recorded attachment size', color: '#88cfca' },
    { key: 'records', title: ar ? 'السجلات المطابقة' : 'Matching records', value: stats?.totalTickets ?? 0, icon: <IconChart size={21} />, note: stats ? `${number(stats.ticketsWithFiles)} ${ar ? 'بمرفقات' : 'with files'} · ${number(stats.totalTickets - stats.ticketsWithFiles)} ${ar ? 'بدون مرفقات' : 'without files'}` : '', color: '#efc58d' },
  ];
  return <section className="explorer-analytics" aria-labelledby="ea-heading" aria-busy={loading}>
    <div className="ea-heading">
      <div><span className="ea-eyebrow">{ar ? 'نظرة على مكتبتك' : 'YOUR LIBRARY AT A GLANCE'}</span>
        <h2 id="ea-heading">{ar ? 'الملفات بالأرقام' : 'The story in your files'}</h2>
        <p>{ar ? 'إحصائيات جميع النتائج المطابقة، عبر كل الصفحات.' : 'Insights across every matching result, on every page.'}</p>
      </div>
      <span className="ea-scope"><span />{loading ? (ar ? 'جارٍ تحديث الأرقام' : 'Updating insights') : filtered ? (ar ? 'حسب الفلاتر الحالية' : 'Current filters') : (ar ? 'كل البيانات' : 'All data')}</span>
    </div>
    <div className="ea-grid">
      {cards.map((card, i) => <article key={card.key} className={`ea-card ea-card-${card.key}`} style={{ '--ea-accent': card.color, '--ea-delay': `${i * 45}ms` } as CSSProperties}>
        <div className="ea-card-top"><span>{card.title}</span><span className="ea-icon" aria-hidden="true">{card.icon}</span></div>
        <div className="ea-value">{stats && !loading ? <Count value={card.value} format={card.key === 'size' ? bytes : number} /> : loading ? <span className="ea-skeleton" /> : '—'}</div>
        <div className="ea-note">{stats && !loading ? card.note : loading ? (ar ? 'جارٍ الحساب…' : 'Calculating…') : (ar ? 'الإحصائيات غير متاحة' : 'Insights unavailable')}</div>
      </article>)}
    </div>
    {stats && !loading && <div className="ea-distribution">
      <div className="ea-distribution-title"><strong>{ar ? 'توزيع أنواع الملفات' : 'File type mix'}</strong><span>{total ? (ar ? 'كل ملف محسوب مرة واحدة' : 'Each file counted once') : (ar ? 'لا توجد ملفات مطابقة' : 'No matching files')}</span></div>
      <div className="ea-bar" aria-hidden="true">{types.filter(x => x.count > 0).map(type => <span key={type.key} style={{ width: `${type.count / total * 100}%`, background: type.color }} />)}</div>
      <div className="ea-legend">{types.map(type => <div key={type.key}><span className="ea-dot" style={{ background: type.color }} /><span>{type.label}</span><strong>{number(type.count)}</strong></div>)}</div>
    </div>}
  </section>;
}

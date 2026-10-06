import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { extractError } from '../../api/client';
import { cleanedFilesApi, type CleanedFile, type CleanedMetadata, type CleanedOptions, type CleanedPage, type CleanedStatus } from '../../api/cleanedFiles';
import { IconDatabase, IconDownload, IconPlus, IconFolder } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';
import './cleaned-files.css';

const statuses: CleanedStatus[] = ['READY', 'IN_PROGRESS', 'COMPLETED'];
function today() { return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Cairo', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()); }
function size(value: number) { return value < 1048576 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1048576).toFixed(1)} MB`; }
const emptyMetadata = (): CleanedMetadata => ({ title: '', cleanedOn: today(), dueOn: null, sourceReference: '', notes: '' });

export function SuperCleanedFilesPage() {
  const { lang } = useT(); const ar = lang === 'ar'; const toast = useToast();
  const text = (arabic: string, english: string) => ar ? arabic : english;
  const label = (status: CleanedStatus) => ({ READY: text('جاهز للـ AI', 'Ready for AI'), IN_PROGRESS: text('قيد العمل', 'In progress'), COMPLETED: text('مكتمل', 'Completed') })[status];
  const [options, setOptions] = useState<CleanedOptions | null>(null);
  const [data, setData] = useState<CleanedPage | null>(null);
  const [error, setError] = useState(''); const [loading, setLoading] = useState(true);
  const [projectId, setProjectId] = useState(''); const [departmentId, setDepartmentId] = useState('');
  const [status, setStatus] = useState<CleanedStatus | ''>(''); const [from, setFrom] = useState(''); const [to, setTo] = useState('');
  const [search, setSearch] = useState(''); const [debouncedSearch, setDebouncedSearch] = useState('');
  const [page, setPage] = useState(0); const [revision, setRevision] = useState(0);
  const [uploadOpen, setUploadOpen] = useState(false); const [editing, setEditing] = useState<CleanedFile | null>(null);
  const [metadata, setMetadata] = useState<CleanedMetadata>(emptyMetadata);
  const [uploadProject, setUploadProject] = useState(''); const [uploadDepartment, setUploadDepartment] = useState('');
  const [files, setFiles] = useState<File[]>([]); const [editStatus, setEditStatus] = useState<CleanedStatus>('READY');
  const [busy, setBusy] = useState(false); const [formError, setFormError] = useState(''); const [progress, setProgress] = useState('');

  useEffect(() => { cleanedFilesApi.options().then(setOptions).catch(e => setError(extractError(e))); }, [revision]);
  useEffect(() => { const timer = setTimeout(() => { if (search !== debouncedSearch) { setDebouncedSearch(search); setPage(0); } }, 300); return () => clearTimeout(timer); }, [search, debouncedSearch]);
  const query = useMemo(() => ({ projectId: projectId ? Number(projectId) : undefined, departmentId: departmentId ? Number(departmentId) : undefined,
    status: status || undefined, from: from || undefined, to: to || undefined, search: debouncedSearch || undefined, page }), [projectId, departmentId, status, from, to, debouncedSearch, page]);
  useEffect(() => {
    const controller = new AbortController(); setLoading(true); setError('');
    if (from && to && from > to) { setError(ar ? 'نهاية الفترة لازم تكون بعد بدايتها.' : 'The end date must follow the start date.'); setLoading(false); setData(null); return; }
    cleanedFilesApi.list(query, controller.signal).then(setData).catch(e => { if (!controller.signal.aborted) { setError(extractError(e)); setData(null); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [query, revision, from, to, ar]);
  const projectDepartments = options?.departments.filter(d => !projectId || String(d.projectId) === projectId) || [];
  const uploadDepartments = options?.departments.filter(d => String(d.projectId) === uploadProject) || [];
  const date = (value: string | null, time = false) => value ? (time ? new Date(value).toLocaleString(ar ? 'ar-EG' : 'en-GB', { timeZone: 'Africa/Cairo' }) : value) : '—';

  function openUpload() {
    setEditing(null); setMetadata(emptyMetadata()); setUploadProject(projectId); setUploadDepartment(departmentId);
    setFiles([]); setFormError(''); setProgress(''); setUploadOpen(true);
  }
  function edit(file: CleanedFile) {
    setUploadOpen(false); setEditing(file); setMetadata({ title: file.title, cleanedOn: file.cleanedOn, dueOn: file.dueOn,
      sourceReference: file.sourceReference || '', notes: file.notes || '' }); setEditStatus(file.status); setFormError('');
  }
  function close() { if (!busy) { setUploadOpen(false); setEditing(null); } }
  function chooseFiles(selected: File[]) {
    if (!options) return;
    const invalid = selected.find(f => f.size === 0 || f.size > options.maxFileBytes || !options.extensions.includes(f.name.split('.').pop()?.toLowerCase() || ''));
    if (invalid) { setFiles([]); setFormError(text(`الملف ${invalid.name} غير مدعوم أو حجمه أكبر من ${size(options.maxFileBytes)}.`, `File ${invalid.name} is empty, unsupported, or exceeds ${size(options.maxFileBytes)}.`)); return; }
    setFiles(selected); setFormError('');
  }
  async function submit(event: FormEvent) {
    event.preventDefault(); setFormError('');
    if (metadata.dueOn && metadata.dueOn < metadata.cleanedOn) { setFormError(text('موعد التسليم لازم يكون بعد تاريخ التنظيف.', 'Due date must be on or after the cleaning date.')); return; }
    if (!editing && !files.length) { setFormError(text('اختار ملف واحد على الأقل.', 'Choose at least one file.')); return; }
    setBusy(true);
    try {
      if (editing) {
        await cleanedFilesApi.update(editing, metadata, editStatus); setEditing(null); toast.success(text('تم حفظ التعديلات.', 'Changes saved.'));
      } else {
        const failed: File[] = []; const failures: string[] = []; let successful = 0;
        for (const [index, file] of files.entries()) {
          try {
            setProgress(`${index + 1} / ${files.length} · ${file.name}`);
            await cleanedFilesApi.upload(Number(uploadProject), Number(uploadDepartment),
              { ...metadata, title: files.length === 1 ? metadata.title : `${metadata.title} · ${file.name}`.slice(0, 250) }, file,
              percent => setProgress(`${index + 1} / ${files.length} · ${Math.min(percent, 100)}% · ${file.name}`));
            successful++;
          } catch (e) { failed.push(file); failures.push(`${file.name}: ${extractError(e)}`); }
        }
        setFiles(failed); setProgress('');
        if (successful) {
          toast.success(text(`تم رفع ${successful} ملف.`, `${successful} file(s) uploaded.`));
          setProjectId(uploadProject); setDepartmentId(uploadDepartment); setStatus(''); setFrom(''); setTo(''); setSearch(''); setDebouncedSearch(''); setPage(0);
        }
        if (!failed.length) setUploadOpen(false);
        else setFormError(text(`فشل رفع ${failed.length} ملف؛ إعادة المحاولة هترفع الملفات دي بس.\n`, `${failed.length} file(s) failed; retry uploads only those files.\n`) + failures.join('\n'));
      }
    } catch (e) { setFormError(extractError(e)); }
    finally { setBusy(false); setRevision(v => v + 1); }
  }
  function reset() { setProjectId(''); setDepartmentId(''); setStatus(''); setFrom(''); setTo(''); setSearch(''); setDebouncedSearch(''); setPage(0); }

  return <div className="page super-page cleaned-page">
    <div className="page-header"><div><span className="cleaned-eyebrow">{text('ما بعد المعالجة المسبقة', 'AFTER PREPROCESSING')}</span>
      <h1>{text('الملفات المنظّفة', 'Cleaned files')}</h1><p className="subtitle">{text('ارفع ناتج التنظيف، نظّمه حسب المشروع والقسم، وتابع تجهيز البيانات لشغل الـ AI.', 'Upload cleaned outputs, organize them by project and department, and track your AI work.')}</p></div>
      <button className="btn btn-primary" onClick={openUpload} disabled={!options}><IconPlus size={16}/>{text('رفع ملفات منظّفة', 'Upload cleaned files')}</button></div>
    <div className="cleaned-flow"><IconDatabase size={22}/><div><strong>{text('من البيانات الخام إلى ملفات جاهزة للعمل', 'From raw data to files ready for work')}</strong>
      <p>{text('نزّل الملفات الأصلية ← نظّفها خارج النظام ← ارفع الناتج هنا ← تابع حالة الشغل والمواعيد.', 'Download originals → clean them outside the system → upload outputs here → track work and deadlines.')}</p></div>
      <Link className="btn btn-secondary btn-sm" to="/super/data">{text('استكشاف الملفات الأصلية', 'Browse source files')}</Link></div>
    <div className="cleaned-stats">{[[text('إجمالي الملفات', 'Total files'), data?.total], [label('READY'), data?.ready], [label('IN_PROGRESS'), data?.inProgress], [label('COMPLETED'), data?.completed]].map(([name, count], index) =>
      <div className={`stat-card cleaned-stat cleaned-stat-${index}`} key={String(name)}><span className="stat-card-label">{name}</span><strong className="stat-card-value">{loading ? '—' : count ?? '—'}</strong></div>)}</div>
    <div className="cleaned-filters">
      <label>{text('بحث', 'Search')}<input className="input" type="search" maxLength={250} value={search} onChange={e => setSearch(e.target.value)} placeholder={text('اسم الملف أو مرجع المصدر', 'Filename or source reference')}/></label>
      <label>{text('المشروع', 'Project')}<select aria-label={text('المشروع', 'Project')} className="input" value={projectId} onChange={e => { setProjectId(e.target.value); setDepartmentId(''); setPage(0); }}><option value="">{text('كل المشاريع', 'All projects')}</option>{options?.projects.map(p => <option key={p.id} value={p.id}>{p.name}{p.teamName ? ` · ${p.teamName}` : ''}</option>)}</select></label>
      <label>{text('القسم', 'Department')}<select aria-label={text('القسم', 'Department')} className="input" value={departmentId} onChange={e => { setDepartmentId(e.target.value); setPage(0); }}><option value="">{text('كل الأقسام', 'All departments')}</option>{projectDepartments.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}</select></label>
      <label>{text('الحالة', 'Status')}<select aria-label={text('الحالة', 'Status')} className="input" value={status} onChange={e => { setStatus(e.target.value as CleanedStatus | ''); setPage(0); }}><option value="">{text('كل الحالات', 'All statuses')}</option>{statuses.map(s => <option key={s} value={s}>{label(s)}</option>)}</select></label>
      <label>{text('تاريخ التنظيف من', 'Cleaned from')}<input className="input" type="date" value={from} onChange={e => { setFrom(e.target.value); setPage(0); }}/></label>
      <label>{text('إلى', 'To')}<input className="input" type="date" value={to} onChange={e => { setTo(e.target.value); setPage(0); }}/></label>
      <button className="btn btn-ghost" onClick={reset}>{text('مسح الفلاتر', 'Reset filters')}</button></div>
    {error && <div className="alert alert-error" role="alert">{error}<button className="btn btn-ghost btn-sm" onClick={() => setRevision(v => v + 1)}>{text('إعادة المحاولة', 'Retry')}</button></div>}
    {loading ? <div className="empty-state" role="status">{text('جاري تحميل الملفات…', 'Loading files…')}</div> : data && !data.items.length ?
      <div className="empty-state cleaned-empty"><IconFolder size={34}/><h3>{text('مفيش ملفات في العرض ده', 'No files in this view')}</h3><p>{text('ارفع ملفاتك المنظّفة أو غيّر الفلاتر لعرض ملفات أخرى.', 'Upload cleaned files or change your filters to see other files.')}</p><button className="btn btn-primary" onClick={openUpload} disabled={!options}>{text('رفع ملفات', 'Upload files')}</button></div> :
      <div className="cleaned-list">{data?.items.map(file => <article className="cleaned-card" key={file.id}>
        <div className="cleaned-card-top"><div><span className="cleaned-location">{file.teamName && `${file.teamName} / `}{file.projectName} / {file.departmentName}</span><h3>{file.title}</h3><span className="muted cleaned-filename" dir="auto">{file.originalFilename} · {size(file.sizeBytes)}</span></div>
          <span className={`cleaned-status cleaned-${file.status.toLowerCase()}`}>{label(file.status)}</span></div>
        <div className="cleaned-dates"><div><span>{text('تاريخ التنظيف', 'Cleaned on')}</span><strong>{date(file.cleanedOn)}</strong></div>
          <div><span>{text('موعد التسليم', 'Due date')}</span><strong className={file.dueOn && file.dueOn < today() && file.status !== 'COMPLETED' ? 'cleaned-overdue' : ''}>{date(file.dueOn)}{file.dueOn && file.dueOn < today() && file.status !== 'COMPLETED' ? ` · ${text('متأخر', 'Overdue')}` : ''}</strong></div>
          <div><span>{text('تم الرفع بواسطة', 'Uploaded by')}</span><strong>{file.uploadedBy}</strong><small>{date(file.uploadedAt, true)}</small></div>
          <div><span>{text('آخر تحديث', 'Last updated')}</span><strong>{date(file.updatedAt, true)}</strong></div></div>
        {file.sourceReference && <p className="cleaned-source"><strong>{text('مرجع المصدر / الدفعة:', 'Source / batch:')}</strong> {file.sourceReference}</p>}
        {file.notes && <p className="cleaned-notes" dir="auto">{file.notes}</p>}
        <div className="cleaned-card-footer"><span className="muted">{file.completedAt ? `${text('اكتمل:', 'Completed:')} ${date(file.completedAt, true)}` : file.startedAt ? `${text('بدأ العمل:', 'Started:')} ${date(file.startedAt, true)}` : text('جاهز لبدء الشغل', 'Ready to start work')}</span>
          <div className="cleaned-actions"><button className="btn btn-secondary btn-sm" onClick={() => edit(file)}>{text('تعديل ومتابعة', 'Edit / track')}</button><a className="btn btn-ghost btn-sm" href={cleanedFilesApi.downloadUrl(file.id)}><IconDownload size={15}/>{text('تنزيل', 'Download')}</a></div></div></article>)}</div>}
    {!loading && data && data.totalPages > 1 && <nav className="cleaned-pagination" aria-label={text('صفحات الملفات', 'File pages')}><button className="btn btn-secondary" disabled={!page} onClick={() => setPage(p => p - 1)}>{text('السابق', 'Previous')}</button><span>{page + 1} / {data.totalPages}</span><button className="btn btn-secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>{text('التالي', 'Next')}</button></nav>}
    <Modal open={uploadOpen || !!editing} title={editing ? text('متابعة الملف', 'Track file') : text('رفع ملفات منظّفة', 'Upload cleaned files')} onClose={close}>
      <form className="cleaned-form" onSubmit={submit}><fieldset disabled={busy}>
        {!editing && <><p className="muted">{text('اختار المشروع والقسم الخاص بالملفات. كل ملف بيتحفظ ويتابع بشكل مستقل.', 'Choose the project and department. Each file is stored and tracked independently.')}</p><div className="cleaned-form-grid">
          <label>{text('المشروع', 'Project')}<select aria-label={text('المشروع', 'Project')} className="input" required value={uploadProject} onChange={e => { setUploadProject(e.target.value); setUploadDepartment(''); }}><option value="">{text('اختار المشروع', 'Choose project')}</option>{options?.projects.map(p => <option key={p.id} value={p.id}>{p.name}{p.teamName ? ` · ${p.teamName}` : ''}</option>)}</select></label>
          <label>{text('القسم', 'Department')}<select aria-label={text('القسم', 'Department')} className="input" required value={uploadDepartment} disabled={!uploadProject} onChange={e => setUploadDepartment(e.target.value)}><option value="">{text('اختار القسم', 'Choose department')}</option>{uploadDepartments.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}</select></label></div>
          {uploadProject && !uploadDepartments.length && <p className="alert alert-error">{text('المشروع ده محتاج قسم نشط مرتبط بيه قبل رفع الملفات.', 'This project needs an active department before files can be uploaded.')}</p>}
          <label className="cleaned-upload-zone">{text('الملفات المنظّفة', 'Cleaned files')}<input type="file" multiple accept={options?.extensions.map(e => `.${e}`).join(',')} onChange={e => chooseFiles(Array.from(e.target.files || []))}/><small>{options?.extensions.join(', ').toUpperCase()} · {text('حتى', 'Up to')} {size(options?.maxFileBytes || 52428800)} {text('لكل ملف', 'per file')}</small></label>
          {!!files.length && <div className="cleaned-selected">{files.map((f, i) => <span key={`${f.name}-${i}`} dir="auto">{f.name} · {size(f.size)}</span>)}</div>}</>}
        {editing && <p className="cleaned-source">{editing.projectName} / {editing.departmentName} · {editing.originalFilename}</p>}
        <label>{text('اسم الملف / الدفعة', 'File / batch title')}<input className="input" required maxLength={250} value={metadata.title} onChange={e => setMetadata(m => ({ ...m, title: e.target.value }))}/></label>
        <div className="cleaned-form-grid"><label>{text('تاريخ اكتمال التنظيف', 'Cleaning completed on')}<input className="input" type="date" required max={today()} value={metadata.cleanedOn} onChange={e => setMetadata(m => ({ ...m, cleanedOn: e.target.value }))}/></label>
          <label>{text('موعد التسليم (اختياري)', 'Due date (optional)')}<input className="input" type="date" min={metadata.cleanedOn} value={metadata.dueOn || ''} onChange={e => setMetadata(m => ({ ...m, dueOn: e.target.value || null }))}/></label></div>
        {editing && <label>{text('حالة شغل الـ AI', 'AI work status')}<select aria-label={text('حالة شغل الـ AI', 'AI work status')} className="input" value={editStatus} onChange={e => setEditStatus(e.target.value as CleanedStatus)}>{statuses.map(s => <option key={s} value={s}>{label(s)}</option>)}</select></label>}
        <label>{text('مرجع الملفات الأصلية / الدفعة (اختياري)', 'Source files / batch reference (optional)')}<input className="input" maxLength={500} placeholder={text('مثال: تصدير قسم القانون، الدفعة 03، من 1 إلى 500', 'Example: legal export, batch 03, records 1–500')} value={metadata.sourceReference} onChange={e => setMetadata(m => ({ ...m, sourceReference: e.target.value }))}/></label>
        <label>{text('ملاحظات التنظيف والشغل (اختياري)', 'Cleaning and work notes (optional)')}<textarea className="input" rows={3} maxLength={4000} placeholder={text('خطوات التنظيف، الملفات المستبعدة، مراجعة العينة، المطلوب في شغل الـ AI…', 'Cleaning steps, excluded files, sample review, intended AI work…')} value={metadata.notes} onChange={e => setMetadata(m => ({ ...m, notes: e.target.value }))}/></label>
      </fieldset>{formError && <div className="alert alert-error cleaned-form-error" role="alert">{formError}</div>}
        {progress && <p role="status" aria-live="polite" className="cleaned-progress" dir="auto">{progress}</p>}
        <div className="modal-footer"><button type="button" className="btn btn-ghost" disabled={busy} onClick={close}>{text('إلغاء', 'Cancel')}</button><button className="btn btn-primary" disabled={busy || (!editing && !files.length)}>{busy ? text('جاري الحفظ…', 'Saving…') : editing ? text('حفظ التعديلات', 'Save changes') : text('رفع الملفات', 'Upload files')}</button></div>
      </form></Modal>
  </div>;
}

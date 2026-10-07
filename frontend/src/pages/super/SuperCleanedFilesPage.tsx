import { Fragment, useEffect, useMemo, useState, type FormEvent } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import { extractError } from '../../api/client';
import { cleanedFilesApi, type CleanedFile, type CleanedMetadata, type CleanedOptions, type CleanedPage, type CleanedStatus, type CleanedDeleteRequest } from '../../api/cleanedFiles';
import { IconDatabase, IconDownload, IconPlus, IconFolder, IconTrash, IconChevronDown } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { SkeletonRows } from '../../components/SkeletonRows';
import { DownloadCenter, type DownloadSource } from './DownloadCenter';
import { formatBytes } from './folderDownload';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';
import './cleaned-files.css';

const statuses: CleanedStatus[] = ['READY', 'IN_PROGRESS', 'COMPLETED'];
function today() { return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Cairo', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()); }
function size(value: number) { return value < 1048576 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1048576).toFixed(1)} MB`; }
const emptyMetadata = (): CleanedMetadata => ({ title: '', cleanedOn: today(), dueOn: null, sourceReference: '', notes: '' });

export function SuperCleanedFilesPage() {
  const { lang, t } = useT(); const ar = lang === 'ar'; const toast = useToast();
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
  const [downloadOpen, setDownloadOpen] = useState(false);
  const [selected, setSelected] = useState<Set<number>>(new Set()); const [excluded, setExcluded] = useState<Set<number>>(new Set());
  const [allMatching, setAllMatching] = useState(false); const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [menu, setMenu] = useState<{ file: CleanedFile; top: number; left: number; anchorTop: number } | null>(null);
  const [deleteRequest, setDeleteRequest] = useState<CleanedDeleteRequest | null>(null);
  const [deleteTitle, setDeleteTitle] = useState(''); const [deleteError, setDeleteError] = useState('');
  const [deleting, setDeleting] = useState(false); const [deleteStale, setDeleteStale] = useState(false);

  useEffect(() => { cleanedFilesApi.options().then(setOptions).catch(e => setError(extractError(e))); }, [revision]);
  useEffect(() => { const timer = setTimeout(() => { if (search !== debouncedSearch) { setDebouncedSearch(search); setPage(0); } }, 300); return () => clearTimeout(timer); }, [search, debouncedSearch]);
  const query = useMemo(() => ({ projectId: projectId ? Number(projectId) : undefined, departmentId: departmentId ? Number(departmentId) : undefined,
    status: status || undefined, from: from || undefined, to: to || undefined, search: debouncedSearch || undefined, page }), [projectId, departmentId, status, from, to, debouncedSearch, page]);
  const exportQuery = useMemo(() => ({ projectId: projectId ? Number(projectId) : undefined, departmentId: departmentId ? Number(departmentId) : undefined,
    status: status || undefined, from: from || undefined, to: to || undefined, search: debouncedSearch || undefined }), [projectId, departmentId, status, from, to, debouncedSearch]);
  useEffect(() => { setSelected(new Set()); setExcluded(new Set()); setAllMatching(false); setExpanded(new Set()); setMenu(null); }, [exportQuery, revision]);
  useEffect(() => {
    if (!menu) return;
    const dismiss = (event: PointerEvent) => { if (!(event.target as Element).closest('[data-cleaned-menu]')) setMenu(null); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setMenu(null); document.getElementById(`cleaned-menu-${menu.file.id}`)?.focus(); } };
    const closeMenu = () => setMenu(null);
    const scrollMenu = () => { const anchor = document.getElementById(`cleaned-menu-${menu.file.id}`); if (!anchor || Math.abs(anchor.getBoundingClientRect().top - menu.anchorTop) > 1) setMenu(null); };
    document.addEventListener('pointerdown', dismiss); document.addEventListener('keydown', escape);
    window.addEventListener('resize', closeMenu); window.addEventListener('scroll', scrollMenu, true);
    return () => { document.removeEventListener('pointerdown', dismiss); document.removeEventListener('keydown', escape); window.removeEventListener('resize', closeMenu); window.removeEventListener('scroll', scrollMenu, true); };
  }, [menu]);
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
  const filterLabels = [options?.projects.find(p => String(p.id) === projectId)?.name, options?.departments.find(d => String(d.id) === departmentId)?.name,
    status ? label(status) : '', from && `${text('من', 'From')}: ${from}`, to && `${text('إلى', 'To')}: ${to}`, debouncedSearch && `${text('بحث', 'Search')}: ${debouncedSearch}`].filter(Boolean) as string[];
  const downloadSource = useMemo<DownloadSource>(() => ({
    manifest: includeText => cleanedFilesApi.manifest(exportQuery, includeText),
    archiveUrl: opts => cleanedFilesApi.archiveUrl(exportQuery, opts), fileUrl: entry => cleanedFilesApi.downloadUrl(entry.documentId),
    subtitle: ar ? 'نزّل الملفات المنظّفة المطابقة للفلاتر الحالية، مرتّبة حسب المشروع والقسم.' : 'Download all cleaned files matching the current filters, organized by project and department.',
    summary: manifest => ar ? `${manifest.totalFiles.toLocaleString()} ملف · ${formatBytes(manifest.totalBytes)}` : `${manifest.totalFiles.toLocaleString()} files · ${formatBytes(manifest.totalBytes)}`,
    prefixLabel: ar ? 'إضافة رقم الملف وعنوانه للاسم' : 'Prefix filenames with file ID and title',
    prefixHint: ar ? 'يسهّل ربط الملف بصفّه في الجدول.' : 'Helps identify the corresponding row in the table.',
    textLabel: ar ? 'تضمين بيانات وملاحظات الملفات' : 'Include file metadata and notes',
    textHint: ar ? 'ملفات Markdown للملاحظات وفهرس CSV، بجانب الملفات الأصلية المنظّفة.' : 'Markdown notes and a CSV index alongside the cleaned files.',
  }), [exportQuery, ar]);
  const isSelected = (id: number) => allMatching ? !excluded.has(id) : selected.has(id);
  const selectionCount = allMatching ? Math.max(0, (data?.total || 0) - excluded.size) : selected.size;
  const allPageSelected = !!data?.items.length && data.items.every(file => isSelected(file.id));
  const somePageSelected = data?.items.some(file => isSelected(file.id)) || false;
  function clearSelection() { setSelected(new Set()); setExcluded(new Set()); setAllMatching(false); }
  function toggleSelection(ids: number[], checked: boolean) {
    if (allMatching) setExcluded(previous => { const next = new Set(previous); ids.forEach(id => checked ? next.delete(id) : next.add(id)); return next; });
    else setSelected(previous => { const next = new Set(previous); ids.forEach(id => checked ? next.add(id) : next.delete(id)); return next; });
  }
  function confirmDelete(file?: CleanedFile) {
    setMenu(null); setDeleteError(''); setDeleteStale(false); setDeleteTitle(file?.title || '');
    setDeleteRequest(file ? { ids: [file.id], expectedCount: 1 } : allMatching
      ? { filters: exportQuery, excludedIds: [...excluded], expectedCount: selectionCount }
      : { ids: [...selected], expectedCount: selectionCount });
  }
  async function removeSelected() {
    if (!deleteRequest || deleting) return;
    setDeleting(true); setDeleteError('');
    try {
      const result = await cleanedFilesApi.delete(deleteRequest);
      toast.success(text(`تم مسح ${result.deleted} ملف منظّف.`, `${result.deleted} cleaned file(s) deleted.`));
      setDeleteRequest(null); clearSelection(); setPage(0); setRevision(value => value + 1);
    } catch (e) {
      setDeleteError(extractError(e));
      if ((e as { response?: { status?: number } }).response?.status === 409) setDeleteStale(true);
    } finally { setDeleting(false); }
  }

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
      <div className="cleaned-header-actions"><button className="btn btn-primary" onClick={() => setDownloadOpen(true)} disabled={loading || !data?.total || search !== debouncedSearch} title={downloadSource.subtitle}><IconDownload size={16}/>{t('super.data.download.button')}</button>
      <button className="btn btn-secondary" onClick={openUpload} disabled={!options}><IconPlus size={16}/>{text('رفع ملفات منظّفة', 'Upload cleaned files')}</button></div></div>
    <DownloadCenter open={downloadOpen} onClose={() => setDownloadOpen(false)} query={exportQuery} filterLabels={filterLabels}
      departments={projectDepartments.map(d => ({ ...d, teamId: null }))} onDepartmentChange={id => { setDepartmentId(id); setPage(0); }} source={downloadSource}/>
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
    {!!selectionCount && <div className="cleaned-selection-bar" role="region" aria-label={text('الملفات المحددة', 'Selected files')}>
      <strong aria-live="polite">{text(`تم تحديد ${selectionCount} ملف`, `${selectionCount} file(s) selected`)}</strong>
      {!allMatching && data && data.total > selected.size && <button className="btn btn-ghost btn-sm" disabled={loading} onClick={() => { setAllMatching(true); setSelected(new Set()); setExcluded(new Set()); }}>{text(`تحديد كل ${data.total} ملف المطابقين للفلاتر`, `Select all ${data.total} matching files`)}</button>}
      <button className="btn btn-ghost btn-sm" onClick={clearSelection}>{text('إلغاء التحديد', 'Clear selection')}</button>
      <button className="btn btn-danger btn-sm" disabled={loading || search !== debouncedSearch || excluded.size > 1000 || selected.size > 1000} onClick={() => confirmDelete()}><IconTrash size={15}/>{text('مسح المحدد', 'Delete selected')}</button>
    </div>}
    {loading ? <div className="table-wrap cleaned-table-wrap" role="status" aria-label={text('جاري تحميل الملفات', 'Loading files')}><table className="data"><tbody><SkeletonRows cols={11} rows={6}/></tbody></table></div> : data && !data.items.length ?
      <div className="empty-state cleaned-empty"><IconFolder size={34}/><h3>{text('مفيش ملفات في العرض ده', 'No files in this view')}</h3><p>{text('ارفع ملفاتك المنظّفة أو غيّر الفلاتر لعرض ملفات أخرى.', 'Upload cleaned files or change your filters to see other files.')}</p><button className="btn btn-primary" onClick={openUpload} disabled={!options}>{text('رفع ملفات', 'Upload files')}</button></div> :
      <div className="table-wrap cleaned-table-wrap" tabIndex={0} role="region" aria-label={text('جدول الملفات المنظّفة', 'Cleaned files table')}><table className="data cleaned-table">
        <thead><tr><th className="cleaned-check-cell"><input type="checkbox" aria-label={text('تحديد كل ملفات الصفحة', 'Select all files on this page')} checked={allPageSelected} ref={input => { if (input) input.indeterminate = somePageSelected && !allPageSelected; }} onChange={event => toggleSelection(data?.items.map(file => file.id) || [], event.target.checked)}/></th>
          <th scope="col">#</th><th scope="col">{text('الملف', 'File')}</th><th scope="col">{text('المشروع / الفريق', 'Project / team')}</th><th scope="col">{text('القسم', 'Department')}</th><th scope="col">{text('الحالة', 'Status')}</th>
          <th scope="col">{text('تاريخ التنظيف', 'Cleaned on')}</th><th scope="col">{text('موعد التسليم', 'Due date')}</th><th scope="col">{text('الحجم', 'Size')}</th><th scope="col">{text('تم الرفع بواسطة', 'Uploaded by')}</th><th scope="col">{text('الإجراءات', 'Actions')}</th></tr></thead>
        <tbody>{data?.items.map(file => <Fragment key={file.id}><tr className={`cleaned-row${isSelected(file.id) ? ' is-selected' : ''}`}>
          <td className="cleaned-check-cell"><input type="checkbox" checked={isSelected(file.id)} aria-label={text(`تحديد الملف #${file.id}`, `Select file #${file.id}`)} onChange={event => toggleSelection([file.id], event.target.checked)}/></td>
          <td className="muted">{file.id}</td><td className="cleaned-file-cell"><button className="cleaned-file-toggle" aria-expanded={expanded.has(file.id)} aria-controls={`cleaned-details-${file.id}`} onClick={() => setExpanded(previous => { const next = new Set(previous); next.has(file.id) ? next.delete(file.id) : next.add(file.id); return next; })}><IconChevronDown size={14}/><strong dir="auto">{file.title}</strong></button><span className="muted cleaned-filename" dir="auto">{file.originalFilename}</span></td>
          <td><strong className="cleaned-cell-title" dir="auto">{file.projectName}</strong><small className="muted cleaned-cell-sub" dir="auto">{file.teamName || '—'}</small></td><td dir="auto">{file.departmentName}</td>
          <td><span className={`cleaned-status cleaned-${file.status.toLowerCase()}`}>{label(file.status)}</span></td><td className="cleaned-date-cell">{date(file.cleanedOn)}</td>
          <td className="cleaned-date-cell"><span className={file.dueOn && file.dueOn < today() && file.status !== 'COMPLETED' ? 'cleaned-overdue' : ''}>{date(file.dueOn)}</span>{file.dueOn && file.dueOn < today() && file.status !== 'COMPLETED' && <small className="cleaned-cell-sub cleaned-overdue">{text('متأخر', 'Overdue')}</small>}</td>
          <td className="cleaned-date-cell">{size(file.sizeBytes)}</td><td><span dir="auto">{file.uploadedBy}</span><small className="muted cleaned-cell-sub">{date(file.uploadedAt, true)}</small></td>
          <td><div className="cleaned-actions"><a className="btn btn-ghost btn-sm" href={cleanedFilesApi.downloadUrl(file.id)}><IconDownload size={14}/>{text('تنزيل', 'Download')}</a>
            <button id={`cleaned-menu-${file.id}`} data-cleaned-menu className="btn btn-ghost btn-sm cleaned-menu-trigger" aria-label={text(`إجراءات الملف #${file.id}`, `Actions for file #${file.id}`)} aria-haspopup="menu" aria-expanded={menu?.file.id === file.id} onClick={event => { const bounds = event.currentTarget.getBoundingClientRect(); setMenu(menu?.file.id === file.id ? null : { file, anchorTop: bounds.top, top: bounds.bottom + 172 < window.innerHeight ? bounds.bottom + 6 : Math.max(8, bounds.top - 168), left: Math.max(8, Math.min(window.innerWidth - 208, ar ? bounds.left : bounds.right - 200)) }); }}><span aria-hidden="true">⋮</span></button></div></td>
        </tr>{expanded.has(file.id) && <tr className="cleaned-detail-row" id={`cleaned-details-${file.id}`}><td colSpan={11}><div className="cleaned-row-details">
          <div><strong>{text('مرجع المصدر / الدفعة', 'Source / batch reference')}</strong><p dir="auto">{file.sourceReference || '—'}</p></div><div><strong>{text('ملاحظات التنظيف والشغل', 'Cleaning and work notes')}</strong><p className="cleaned-notes" dir="auto">{file.notes || '—'}</p></div>
          <div><strong>{text('آخر تحديث', 'Last updated')}</strong><p>{date(file.updatedAt, true)}</p><span className="muted">{file.completedAt ? `${text('اكتمل:', 'Completed:')} ${date(file.completedAt, true)}` : file.startedAt ? `${text('بدأ العمل:', 'Started:')} ${date(file.startedAt, true)}` : text('جاهز لبدء الشغل', 'Ready to start work')}</span></div>
          <button className="btn btn-secondary btn-sm" onClick={() => edit(file)}>{text('تعديل ومتابعة', 'Edit / track')}</button></div></td></tr>}</Fragment>)}</tbody></table></div>}
    {!loading && data && data.totalPages > 1 && <nav className="cleaned-pagination" aria-label={text('صفحات الملفات', 'File pages')}><button className="btn btn-secondary" disabled={!page} onClick={() => setPage(p => p - 1)}>{text('السابق', 'Previous')}</button><span>{page + 1} / {data.totalPages}</span><button className="btn btn-secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(p => p + 1)}>{text('التالي', 'Next')}</button></nav>}
    {menu && createPortal(<div data-cleaned-menu className="cleaned-menu" role="menu" aria-label={text('إجراءات الملف', 'File actions')} style={{ top: menu.top, left: menu.left }} onKeyDown={event => { if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) { event.preventDefault(); const items = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('[role="menuitem"]')); const index = items.indexOf(document.activeElement as HTMLElement); items[event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus(); } }}>
      <a role="menuitem" ref={element => element?.focus({ preventScroll: true })} href={cleanedFilesApi.downloadUrl(menu.file.id)} onClick={() => setMenu(null)}><IconDownload size={15}/>{text('تنزيل الملف', 'Download file')}</a>
      <button role="menuitem" onClick={() => { edit(menu.file); setMenu(null); }}>{text('تعديل ومتابعة', 'Edit / track')}</button><button role="menuitem" className="cleaned-menu-delete" onClick={() => confirmDelete(menu.file)}><IconTrash size={15}/>{text('مسح الملف', 'Delete file')}</button></div>, document.body)}
    <Modal open={!!deleteRequest} title={text('تأكيد مسح الملفات', 'Confirm file deletion')} onClose={() => { if (!deleting) setDeleteRequest(null); }}>
      <div className="cleaned-delete-dialog"><p>{text(`هتمسح ${deleteRequest?.expectedCount || 0} ملف منظّف نهائيًا مع بيانات متابعته. الحذف لا يمكن التراجع عنه.`, `Permanently delete ${deleteRequest?.expectedCount || 0} cleaned file(s) and their tracking data. This cannot be undone.`)}</p>{deleteTitle && <strong dir="auto">{deleteTitle}</strong>}
        {deleteRequest?.filters && !!filterLabels.length && <div className="dlc-filters">{filterLabels.map(value => <span className="dlc-filter" key={value}>{value}</span>)}</div>}
        {deleteError && <div className="alert alert-error" role="alert">{deleteError}{deleteStale && <p>{text('اقفل النافذة وراجع التحديد من جديد قبل الحذف.', 'Close this dialog and review the selection before deleting again.')}</p>}</div>}
        <div className="modal-footer"><button className="btn btn-ghost" disabled={deleting} onClick={() => { setDeleteRequest(null); if (deleteStale) setRevision(value => value + 1); }}>{text('إلغاء', 'Cancel')}</button><button className="btn btn-danger" disabled={deleting || deleteStale} onClick={removeSelected}><IconTrash size={15}/>{deleting ? text('جاري المسح…', 'Deleting…') : text('مسح نهائي', 'Delete permanently')}</button></div></div>
    </Modal>
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

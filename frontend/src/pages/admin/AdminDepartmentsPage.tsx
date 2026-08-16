import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, projectsApi, ticketsApi } from '../../api/resources';
import type { Department, Project, Ticket } from '../../api/types';
import { BulkAddModal } from '../../components/BulkAddModal';
import { IconBuilding, IconFolder, IconMembers } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/toast/ToastContext';
import { useConfirm } from '../../components/ConfirmDialog';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';
import { DepartmentDetailModal } from './DepartmentDetailModal';

interface FormState {
  id?: number;
  name: string;
  projectId: number | '';
  active: boolean;
}

const empty: FormState = { name: '', projectId: '', active: true };

export function AdminDepartmentsPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const isAr = lang === 'ar';

  const [items, setItems] = useState<Department[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [bulkOpen, setBulkOpen] = useState(false);
  const [bulkProjectId, setBulkProjectId] = useState<number | ''>('');
  const [form, setForm] = useState<FormState>(empty);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [detail, setDetail] = useState<Department | null>(null);
  const [projectFilter, setProjectFilter] = useState<number | ''>('');

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);

  async function refresh() {
    setLoading(true);
    try {
      const [d, p, page] = await Promise.all([
        departmentsApi.adminList(),
        projectsApi.list(),
        ticketsApi.listAll(0, 200),
      ]);
      setItems(d);
      setProjects(p);
      setTickets(page.items);
      setSelected(new Set());
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); }, []);

  const visibleItems = useMemo(() => (
    projectFilter === ''
      ? items
      : items.filter((d) => d.projectId === projectFilter)
  ), [items, projectFilter]);

  function openCreate() {
    setForm({ ...empty, projectId: projectFilter === '' ? '' : projectFilter });
    setFormError(null);
    setModalOpen(true);
  }
  function openEdit(d: Department) {
    setForm({
      id: d.id,
      name: d.name,
      projectId: d.projectId ?? '',
      active: d.active,
    });
    setFormError(null); setModalOpen(true);
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    const name = form.name.trim();
    if (!name) { setFormError(t('admin.departments.nameRequired')); return; }
    if (form.projectId === '') {
      setFormError(isAr ? 'اختار مشروع للقسم أولاً' : 'Pick a project for this department');
      return;
    }
    setSaving(true);
    try {
      if (form.id) {
        await departmentsApi.update(form.id, {
          name,
          projectId: form.projectId as number,
          active: form.active,
        });
        toast.success(isAr ? 'تم تحديث القسم' : 'Department updated');
      } else {
        await departmentsApi.create({
          name,
          projectId: form.projectId as number,
          active: form.active,
        });
        toast.success(isAr ? 'تم إضافة القسم 🎉' : 'Department added 🎉');
      }
      setModalOpen(false);
      refresh();
    } catch (err) {
      const msg = extractError(err);
      setFormError(msg);
      toast.error(msg);
    } finally {
      setSaving(false);
    }
  }

  async function onDelete(d: Department) {
    const ok = await confirm({
      message: t('common.confirmDelete', { name: d.name }),
      destructive: true,
    });
    if (!ok) return;
    try {
      await departmentsApi.remove(d.id);
      toast.success(isAr ? 'تم حذف القسم' : 'Department deleted');
      refresh();
    } catch (e) {
      toast.error(extractError(e));
    }
  }

  function toggleSelected(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  function toggleSelectAll() {
    if (selected.size === items.length) setSelected(new Set());
    else setSelected(new Set(items.map((d) => d.id)));
  }

  async function onBulkDelete() {
    const ids = Array.from(selected);
    if (ids.length === 0) return;
    const proceed = await confirm({
      message: isAr
        ? `تأكيد حذف ${ids.length} قسم؟ الإجراء غير قابل للتراجع.`
        : `Delete ${ids.length} department${ids.length === 1 ? '' : 's'}? This can't be undone.`,
      destructive: true,
    });
    if (!proceed) return;

    setBulkDeleting(true);
    let ok = 0; let fail = 0;
    // Serial rather than parallel — SQLite only allows one writer, and Promise.allSettled
    // was firing all deletes concurrently which hit SQLITE_BUSY on all but the first request.
    for (const id of ids) {
      try {
        await departmentsApi.remove(id);
        ok++;
      } catch {
        fail++;
      }
    }
    setBulkDeleting(false);
    if (fail === 0) {
      toast.success(isAr ? `تم حذف ${ok} قسم بنجاح` : `Deleted ${ok} department${ok === 1 ? '' : 's'}`);
    } else {
      toast.warning(isAr
        ? `تم حذف ${ok}، فشل ${fail}`
        : `Deleted ${ok}, ${fail} failed`);
    }
    refresh();
  }

  const entriesFor = (id: number) => tickets.filter(tk => tk.departmentId === id).length;
  const membersFor = (id: number) => new Set(tickets.filter(tk => tk.departmentId === id).map(tk => tk.submittedById)).size;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('admin.departments.title')}</h1>
          <p className="subtitle">{t('admin.departments.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={() => setBulkOpen(true)}>
          <IconBuilding size={16} /> {t('admin.departments.newBtn')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Sticky bulk-action bar — appears only when at least one row is selected */}
      {selected.size > 0 && (
        <div className="bulk-action-bar">
          <label className="bulk-action-select-all">
            <input
              type="checkbox"
              checked={selected.size === items.length && items.length > 0}
              onChange={toggleSelectAll}
            />
            {isAr
              ? `${selected.size} من ${items.length} مختار`
              : `${selected.size} of ${items.length} selected`}
          </label>
          <button
            className="btn btn-danger btn-sm"
            onClick={onBulkDelete}
            disabled={bulkDeleting}
          >
            {bulkDeleting
              ? (isAr ? 'جارٍ الحذف…' : 'Deleting…')
              : (isAr ? `حذف المختار (${selected.size})` : `Delete selected (${selected.size})`)}
          </button>
          <button className="btn btn-ghost btn-sm" onClick={() => setSelected(new Set())}>
            {isAr ? 'إلغاء التحديد' : 'Clear'}
          </button>
        </div>
      )}

      {/* Project scope filter — helps admins narrow a long list when many projects exist. */}
      {projects.length > 0 && (
        <div className="card" style={{ padding: '0.75rem 1rem', marginBottom: '1rem', display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
          <label className="field-label" style={{ margin: 0 }}>
            {isAr ? 'المشروع' : 'Project'}
          </label>
          <select
            className="select"
            style={{ maxWidth: 260 }}
            value={projectFilter === '' ? '' : String(projectFilter)}
            onChange={(e) => setProjectFilter(e.target.value === '' ? '' : Number(e.target.value))}
          >
            <option value="">{t('common.all')}</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>{pickLocalized(p, 'name', lang)}</option>
            ))}
          </select>
        </div>
      )}

      {loading ? (
        <div className="card empty-state">{t('common.loading')}</div>
      ) : visibleItems.length === 0 ? (
        <div className="card empty-state">{t('admin.departments.empty')}</div>
      ) : (
        <div className="dept-grid">
          {visibleItems.map((d, idx) => {
            const color = ((idx % 6) + 1);
            const isChecked = selected.has(d.id);
            return (
              <div key={d.id} className={`dept-card${isChecked ? ' is-selected' : ''}`} data-color={color}>
                <label className="dept-card-check" title={isAr ? 'تحديد للحذف' : 'Select'}>
                  <input
                    type="checkbox"
                    checked={isChecked}
                    onChange={() => toggleSelected(d.id)}
                    onClick={(e) => e.stopPropagation()}
                  />
                </label>
                <button
                  type="button"
                  className="dept-card-open"
                  onClick={() => setDetail(d)}
                  title={t('admin.departments.openDetail')}
                  style={{
                    display: 'block', width: '100%', textAlign: 'inherit',
                    background: 'transparent', border: 0, padding: 0, cursor: 'pointer', color: 'inherit',
                  }}
                >
                  <div className="dept-card-head">
                    <div className="dept-card-name">{pickLocalized(d, 'name', lang)}</div>
                    <span className={`badge ${d.active ? 'badge-active' : 'badge-inactive'}`}>
                      {d.active ? t('common.active') : t('common.inactive')}
                    </span>
                  </div>
                  {d.projectName && (
                    <div className="small muted" style={{ marginTop: 4 }}>
                      <IconFolder size={12} /> {d.projectName}
                    </div>
                  )}
                  <div className="dept-card-mini-grid">
                    <div className="dept-mini-stat tint-a">
                      <div className="dept-mini-stat-label">
                        <span style={{ verticalAlign: 'middle', marginInlineEnd: 4, display: 'inline-block' }}>
                          <IconMembers size={12} />
                        </span>
                        {t('admin.departments.members')}
                      </div>
                      <div className="dept-mini-stat-value">{membersFor(d.id)}</div>
                    </div>
                    <div className="dept-mini-stat tint-a" style={{ opacity: 0.9 }}>
                      <div className="dept-mini-stat-label">
                        <span style={{ verticalAlign: 'middle', marginInlineEnd: 4, display: 'inline-block' }}>
                          <IconFolder size={12} />
                        </span>
                        {t('admin.departments.entries')}
                      </div>
                      <div className="dept-mini-stat-value">{entriesFor(d.id)}</div>
                    </div>
                  </div>
                </button>
                <div className="dept-card-actions">
                  <button className="btn btn-secondary btn-sm" onClick={() => openEdit(d)}>{t('common.edit')}</button>
                  <button className="btn btn-danger btn-sm" onClick={() => onDelete(d)}>{t('common.delete')}</button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <Modal
        open={modalOpen}
        title={form.id ? t('admin.departments.editTitle') : t('admin.departments.createTitle')}
        onClose={() => setModalOpen(false)}
        footer={
          <>
            <button className="btn btn-secondary" onClick={() => setModalOpen(false)}>{t('common.cancel')}</button>
            <button className="btn btn-primary" onClick={onSave} disabled={saving}>
              {saving ? <span className="spinner" /> : t('common.save')}
            </button>
          </>
        }
      >
        {formError && <div className="alert alert-error">{formError}</div>}
        <form onSubmit={onSave}>
          <div className="field">
            <label className="field-label">
              {isAr ? 'المشروع' : 'Project'} <span className="req">*</span>
            </label>
            <select
              className="select"
              value={form.projectId === '' ? '' : String(form.projectId)}
              onChange={(e) => setForm({
                ...form,
                projectId: e.target.value === '' ? '' : Number(e.target.value),
              })}
              disabled={projects.length === 0}
            >
              <option value="">
                {projects.length === 0
                  ? (isAr ? 'أضف مشروع الأول قبل ما تكرييت قسم' : 'Create a project first')
                  : (isAr ? 'اختار المشروع' : 'Choose a project')}
              </option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>{pickLocalized(p, 'name', lang)}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label className="field-label">{t('admin.departments.colName')} <span className="req">*</span></label>
            <input
              className="input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder={t('admin.departments.namePlaceholder')}
              autoFocus
            />
          </div>
          <div className="field">
            <label className="field-label" style={{ display: 'flex', alignItems: 'center', gap: 12, textTransform: 'none', letterSpacing: 0, fontSize: 'var(--fs-sm)' }}>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={(e) => setForm({ ...form, active: e.target.checked })}
                />
                <span className="slider" />
              </label>
              {t('admin.departments.activeHint')}
            </label>
          </div>
        </form>
      </Modal>

      <BulkAddModal
        open={bulkOpen}
        title={isAr ? 'إضافة أقسام دفعة واحدة' : 'Add departments in bulk'}
        placeholder={isAr ? 'مبيعات\nتسويق\nخدمة العملاء' : 'Sales\nMarketing\nCustomer service'}
        hint={isAr
          ? 'كل الأقسام هتتضاف تحت المشروع المختار تحت.'
          : 'All departments will be added under the project picked below.'}
        canSubmit={bulkProjectId !== ''}
        headerContent={
          <div className="field">
            <label className="field-label">
              {isAr ? 'المشروع' : 'Project'} <span className="req">*</span>
            </label>
            <select
              className="select"
              value={bulkProjectId === '' ? '' : String(bulkProjectId)}
              onChange={(e) => setBulkProjectId(e.target.value === '' ? '' : Number(e.target.value))}
              disabled={projects.length === 0}
            >
              <option value="">
                {projects.length === 0
                  ? (isAr ? 'أضف مشروع الأول قبل ما تكرييت قسم' : 'Create a project first')
                  : (isAr ? 'اختار المشروع' : 'Choose a project')}
              </option>
              {projects.map((p) => (
                <option key={p.id} value={p.id}>{pickLocalized(p, 'name', lang)}</option>
              ))}
            </select>
          </div>
        }
        onClose={() => { setBulkOpen(false); setBulkProjectId(''); }}
        onCreateEach={async (name) => {
          if (bulkProjectId === '') throw new Error('No project picked');
          await departmentsApi.create({
            name,
            projectId: bulkProjectId as number,
            active: true,
          });
        }}
        onDone={(res) => {
          setBulkOpen(false);
          setBulkProjectId('');
          if (res.failed === 0) {
            toast.success(isAr
              ? `تم إضافة ${res.created} قسم 🎉`
              : `Added ${res.created} department${res.created === 1 ? '' : 's'} 🎉`);
          } else {
            toast.warning(isAr
              ? `تم إضافة ${res.created}، فشل ${res.failed}: ${res.failures.join('، ')}`
              : `Added ${res.created}, ${res.failed} failed: ${res.failures.join(', ')}`);
          }
          refresh();
        }}
      />

      {detail && (
        <DepartmentDetailModal
          department={detail}
          onClose={() => setDetail(null)}
          onChanged={() => { refresh(); }}
        />
      )}
    </div>
  );
}

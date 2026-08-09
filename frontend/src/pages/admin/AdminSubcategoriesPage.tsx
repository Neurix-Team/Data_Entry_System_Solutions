import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, subcategoriesApi } from '../../api/resources';
import type { Department, Subcategory } from '../../api/types';
import { IconFolder } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useT } from '../../i18n';

interface FormState {
  id?: number;
  departmentId: number | '';
  name: string;
  active: boolean;
}

const empty: FormState = { departmentId: '', name: '', active: true };

export function AdminSubcategoriesPage() {
  const { t } = useT();
  const [departments, setDepartments] = useState<Department[]>([]);
  const [items, setItems] = useState<Subcategory[]>([]);
  const [filterDept, setFilterDept] = useState<number | ''>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<FormState>(empty);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      const [dLoad, sLoad] = await Promise.all([
        departmentsApi.adminList(),
        subcategoriesApi.adminList(filterDept === '' ? undefined : filterDept),
      ]);
      setDepartments(dLoad);
      setItems(sLoad);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [filterDept]);

  function openCreate() {
    setForm({ ...empty, departmentId: filterDept === '' ? '' : filterDept });
    setFormError(null);
    setModalOpen(true);
  }
  function openEdit(s: Subcategory) {
    setForm({ id: s.id, departmentId: s.departmentId, name: s.name, active: s.active });
    setFormError(null);
    setModalOpen(true);
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (form.departmentId === '') { setFormError(t('admin.subcategories.deptRequired')); return; }
    const name = form.name.trim();
    if (!name) { setFormError(t('admin.subcategories.nameRequired')); return; }
    setSaving(true);
    try {
      if (form.id) {
        await subcategoriesApi.update(form.id, {
          departmentId: form.departmentId as number,
          name,
          active: form.active,
        });
      } else {
        await subcategoriesApi.create({
          departmentId: form.departmentId as number,
          name,
          active: form.active,
        });
      }
      setModalOpen(false);
      await refresh();
    } catch (err) {
      setFormError(extractError(err));
    } finally {
      setSaving(false);
    }
  }

  async function onDelete(s: Subcategory) {
    if (!confirm(t('common.confirmDelete', { name: s.name }))) return;
    try {
      await subcategoriesApi.remove(s.id);
      refresh();
    } catch (e) {
      alert(extractError(e));
    }
  }

  const grouped = useMemo(() => {
    const map = new Map<number, { dept: string; items: Subcategory[] }>();
    for (const s of items) {
      if (!map.has(s.departmentId)) {
        map.set(s.departmentId, { dept: s.departmentName, items: [] });
      }
      map.get(s.departmentId)!.items.push(s);
    }
    return Array.from(map.entries()).sort((a, b) => a[1].dept.localeCompare(b[1].dept));
  }, [items]);

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('admin.subcategories.title')}</h1>
          <p className="subtitle">{t('admin.subcategories.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={openCreate}>
          <IconFolder size={16} /> {t('admin.subcategories.newBtn')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card" style={{ padding: '0.75rem 1rem', marginBottom: '1rem', display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <label className="field-label" style={{ margin: 0 }}>{t('admin.subcategories.filterDept')}</label>
        <select
          className="select"
          value={filterDept === '' ? '' : String(filterDept)}
          onChange={(e) => setFilterDept(e.target.value === '' ? '' : Number(e.target.value))}
          style={{ maxWidth: 260 }}
        >
          <option value="">{t('common.all')}</option>
          {departments.map(d => (
            <option key={d.id} value={d.id}>{d.name}</option>
          ))}
        </select>
      </div>

      {loading ? (
        <div className="card empty-state">{t('common.loading')}</div>
      ) : items.length === 0 ? (
        <div className="card empty-state">{t('admin.subcategories.empty')}</div>
      ) : (
        grouped.map(([deptId, group], gi) => (
          <div key={deptId} style={{ marginBottom: '1.5rem' }}>
            <h3 style={{ margin: '0 0 0.5rem 0', fontSize: 'var(--fs-md)' }}>{group.dept}</h3>
            <div className="dept-grid">
              {group.items.map((s, idx) => {
                const color = (((gi * 3 + idx) % 6) + 1);
                return (
                  <div key={s.id} className="dept-card" data-color={color}>
                    <div className="dept-card-head">
                      <div className="dept-card-name">{s.name}</div>
                      <span className={`badge ${s.active ? 'badge-active' : 'badge-inactive'}`}>
                        {s.active ? t('common.active') : t('common.inactive')}
                      </span>
                    </div>
                    <div className="dept-card-mini-grid">
                      <div className="dept-mini-stat tint-a">
                        <div className="dept-mini-stat-label">{t('admin.subcategories.entries')}</div>
                        <div className="dept-mini-stat-value">{s.ticketCount}</div>
                      </div>
                      <div className="dept-mini-stat tint-a" style={{ opacity: 0.9 }}>
                        <div className="dept-mini-stat-label">{t('admin.subcategories.fields')}</div>
                        <div className="dept-mini-stat-value">{s.fieldCount}</div>
                      </div>
                    </div>
                    <div className="dept-card-actions">
                      <button className="btn btn-secondary btn-sm" onClick={() => openEdit(s)}>{t('common.edit')}</button>
                      <button className="btn btn-danger btn-sm" onClick={() => onDelete(s)}>{t('common.delete')}</button>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        ))
      )}

      <Modal
        open={modalOpen}
        title={form.id ? t('admin.subcategories.editTitle') : t('admin.subcategories.createTitle')}
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
            <label className="field-label">{t('admin.subcategories.deptLabel')} <span className="req">*</span></label>
            <select
              className="select"
              value={form.departmentId === '' ? '' : String(form.departmentId)}
              onChange={(e) => setForm({ ...form, departmentId: e.target.value === '' ? '' : Number(e.target.value) })}
            >
              <option value="">{t('admin.subcategories.chooseDept')}</option>
              {departments.map(d => (
                <option key={d.id} value={d.id}>{d.name}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label className="field-label">{t('admin.subcategories.colName')} <span className="req">*</span></label>
            <input
              className="input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder={t('admin.subcategories.namePlaceholder')}
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
              {t('admin.subcategories.activeHint')}
            </label>
          </div>
        </form>
      </Modal>
    </div>
  );
}

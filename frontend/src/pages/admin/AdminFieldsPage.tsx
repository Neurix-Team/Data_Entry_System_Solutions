import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, fieldsApi, subcategoriesApi } from '../../api/resources';
import type { CustomField, Department, FieldType, Subcategory } from '../../api/types';
import { IconFolder } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useT } from '../../i18n';

interface FormState {
  id?: number;
  subcategoryId: number | '';
  fieldKey: string;
  label: string;
  type: FieldType;
  required: boolean;
  displayOrder: number;
  options: string;
  placeholder: string;
  active: boolean;
}

const empty: FormState = {
  subcategoryId: '',
  fieldKey: '', label: '', type: 'TEXT',
  required: true, displayOrder: 0, options: '',
  placeholder: '', active: true,
};

const TYPES: FieldType[] = ['TEXT', 'TEXTAREA', 'NUMBER', 'URL', 'EMAIL', 'DATE', 'SELECT'];

function slugify(s: string): string {
  return s.trim().toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .replace(/^([0-9])/, 'f_$1');
}

export function AdminFieldsPage() {
  const { t } = useT();
  const [items, setItems] = useState<CustomField[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [subcategories, setSubcategories] = useState<Subcategory[]>([]);
  const [filterDept, setFilterDept] = useState<number | ''>('');
  const [filterSub, setFilterSub] = useState<number | ''>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<FormState>(empty);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [keyEdited, setKeyEdited] = useState(false);

  const isEdit = form.id !== undefined;

  async function refresh() {
    setLoading(true);
    setError(null);
    try {
      const [fieldList, deptList, subList] = await Promise.all([
        fieldsApi.adminList(filterSub === '' ? undefined : filterSub),
        departmentsApi.adminList(),
        subcategoriesApi.adminList(),
      ]);
      // when filtering by department only, filter the list client-side by subcategory dept
      const filteredByDept = filterDept === ''
        ? fieldList
        : fieldList.filter(f => f.departmentId === filterDept);
      setItems(filteredByDept);
      setDepartments(deptList);
      setSubcategories(subList);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [filterSub, filterDept]);

  const subsForFilter = useMemo(
    () => filterDept === '' ? subcategories : subcategories.filter(s => s.departmentId === filterDept),
    [filterDept, subcategories],
  );

  const formDeptId = useMemo(() => {
    if (form.subcategoryId === '') return '' as number | '';
    const s = subcategories.find(x => x.id === form.subcategoryId);
    return s ? s.departmentId : ('' as number | '');
  }, [form.subcategoryId, subcategories]);

  const subsForForm = useMemo(
    () => formDeptId === '' ? subcategories : subcategories.filter(s => s.departmentId === formDeptId),
    [formDeptId, subcategories],
  );

  function openCreate() {
    setForm({
      ...empty,
      displayOrder: items.length + 1,
      subcategoryId: filterSub === '' ? '' : filterSub,
    });
    setKeyEdited(false); setFormError(null); setModalOpen(true);
  }
  function openEdit(f: CustomField) {
    setForm({
      id: f.id,
      subcategoryId: f.subcategoryId ?? '',
      fieldKey: f.fieldKey, label: f.label, type: f.type,
      required: f.required, displayOrder: f.displayOrder,
      options: f.options ?? '', placeholder: f.placeholder ?? '', active: f.active,
    });
    setKeyEdited(true); setFormError(null); setModalOpen(true);
  }

  function onLabelChange(v: string) {
    setForm((prev) => ({
      ...prev, label: v,
      fieldKey: !isEdit && !keyEdited ? slugify(v) : prev.fieldKey,
    }));
  }

  function onDeptChangeInForm(deptId: number | '') {
    setForm(prev => ({ ...prev, subcategoryId: '' }));
    // Filter subs for the new dept — user must pick a subcategory next
    if (deptId !== '') {
      const first = subcategories.find(s => s.departmentId === deptId);
      if (first) setForm(prev => ({ ...prev, subcategoryId: first.id }));
    }
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (form.subcategoryId === '') { setFormError(t('admin.fields.subcategoryRequired')); return; }
    const key = form.fieldKey.trim();
    if (!form.label.trim()) { setFormError(t('admin.fields.labelRequired')); return; }
    if (!isEdit && !/^[a-zA-Z][a-zA-Z0-9_]*$/.test(key)) {
      setFormError(t('admin.fields.keyInvalid')); return;
    }
    if (form.type === 'SELECT' && !form.options.trim()) {
      setFormError(t('admin.fields.optionsRequired')); return;
    }

    setSaving(true);
    try {
      const payload = {
        subcategoryId: form.subcategoryId as number,
        fieldKey: key,
        label: form.label.trim(),
        type: form.type,
        required: form.required,
        displayOrder: form.displayOrder,
        options: form.type === 'SELECT' ? form.options.trim() : null,
        placeholder: form.placeholder || null,
        active: form.active,
      };
      if (isEdit) await fieldsApi.update(form.id!, payload);
      else await fieldsApi.create(payload);
      setModalOpen(false);
      refresh();
    } catch (err) {
      setFormError(extractError(err));
    } finally {
      setSaving(false);
    }
  }

  async function onDelete(f: CustomField) {
    if (!confirm(t('admin.fields.confirmDelete', { name: f.label }))) return;
    try { await fieldsApi.remove(f.id); refresh(); }
    catch (e) { alert(extractError(e)); }
  }

  const noFilterActive = filterDept === '' && filterSub === '';
  const showEmptyHero = !loading && items.length === 0 && noFilterActive;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('admin.fields.title')}</h1>
          <p className="subtitle">{t('admin.fields.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={openCreate}>
          <IconFolder size={16} /> {t('admin.fields.newBtn')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Purpose banner — always visible so admins never wonder what this page is for */}
      <div className="intro-card">
        <div className="intro-card-icon">💡</div>
        <div className="intro-card-body">
          <p className="intro-card-title">{t('admin.fields.introTitle')}</p>
          <p className="intro-card-text">{t('admin.fields.introBody')}</p>
          <div className="intro-examples">
            <div className="intro-example">
              <div className="intro-example-head">{t('admin.fields.ex1Title')}</div>
              <div className="intro-example-body">{t('admin.fields.ex1Body')}</div>
              <div className="intro-example-tags">
                <span className="intro-example-tag">{t('admin.fields.ex1TagPriority')}</span>
                <span className="intro-example-tag">{t('admin.fields.ex1TagDate')}</span>
              </div>
            </div>
            <div className="intro-example">
              <div className="intro-example-head">{t('admin.fields.ex2Title')}</div>
              <div className="intro-example-body">{t('admin.fields.ex2Body')}</div>
              <div className="intro-example-tags">
                <span className="intro-example-tag">{t('admin.fields.ex2TagContract')}</span>
                <span className="intro-example-tag">{t('admin.fields.ex2TagEmail')}</span>
              </div>
            </div>
            <div className="intro-example">
              <div className="intro-example-head">{t('admin.fields.ex3Title')}</div>
              <div className="intro-example-body">{t('admin.fields.ex3Body')}</div>
              <div className="intro-example-tags">
                <span className="intro-example-tag">{t('admin.fields.ex3TagLevel')}</span>
                <span className="intro-example-tag">{t('admin.fields.ex3TagNotes')}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div className="card" style={{ padding: '0.75rem 1rem', marginBottom: '1rem', display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <label className="field-label" style={{ margin: 0 }}>{t('admin.fields.filterDept')}</label>
        <select
          className="select"
          value={filterDept === '' ? '' : String(filterDept)}
          onChange={(e) => {
            const v = e.target.value === '' ? '' : Number(e.target.value);
            setFilterDept(v);
            setFilterSub('');
          }}
          style={{ maxWidth: 220 }}
        >
          <option value="">{t('common.all')}</option>
          {departments.map(d => (
            <option key={d.id} value={d.id}>{d.name}</option>
          ))}
        </select>
        <label className="field-label" style={{ margin: 0 }}>{t('admin.fields.filterSub')}</label>
        <select
          className="select"
          value={filterSub === '' ? '' : String(filterSub)}
          onChange={(e) => setFilterSub(e.target.value === '' ? '' : Number(e.target.value))}
          style={{ maxWidth: 220 }}
        >
          <option value="">{t('common.all')}</option>
          {subsForFilter.map(s => (
            <option key={s.id} value={s.id}>{s.name}</option>
          ))}
        </select>
      </div>

      {showEmptyHero ? (
        <div className="empty-hero">
          <div className="empty-hero-icon">📝</div>
          <p className="empty-hero-title">{t('admin.fields.emptyHeroTitle')}</p>
          <p className="empty-hero-text">{t('admin.fields.emptyHeroText')}</p>
          <button className="btn btn-primary" onClick={openCreate}>
            <IconFolder size={16} /> {t('admin.fields.newBtn')}
          </button>
        </div>
      ) : (
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th style={{ width: 60 }}>{t('admin.fields.colOrder')}</th>
              <th>{t('admin.fields.colLabel')}</th>
              <th>{t('admin.fields.colKey')}</th>
              <th>{t('admin.fields.colSubcategory')}</th>
              <th>{t('admin.fields.colType')}</th>
              <th>{t('common.required')}</th>
              <th>{t('common.status')}</th>
              <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={8} className="empty-state">{t('common.loading')}</td></tr>
            ) : items.length === 0 ? (
              <tr><td colSpan={8} className="empty-state">{t('admin.fields.empty')}</td></tr>
            ) : items.map((f) => (
              <tr key={f.id}>
                <td className="muted">{f.displayOrder}</td>
                <td style={{ fontWeight: 500 }}>{f.label}</td>
                <td><code className="mono small">{f.fieldKey}</code></td>
                <td className="small muted">
                  {f.departmentName ? `${f.departmentName} · ` : ''}{f.subcategoryName || '—'}
                </td>
                <td className="muted small">{t(`admin.fields.types.${f.type}`)}</td>
                <td>{f.required ? t('common.yes') : <span className="muted">{t('common.no')}</span>}</td>
                <td>
                  <span className={`badge ${f.active ? 'badge-active' : 'badge-inactive'}`}>
                    {f.active ? t('common.active') : t('common.inactive')}
                  </span>
                </td>
                <td className="actions-cell">
                  <button className="btn btn-secondary btn-sm" onClick={() => openEdit(f)}>{t('common.edit')}</button>
                  <button className="btn btn-danger btn-sm" onClick={() => onDelete(f)}>{t('common.delete')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      )}

      <Modal
        open={modalOpen}
        title={isEdit ? t('admin.fields.editTitle') : t('admin.fields.createTitle')}
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
          <div className="row" style={{ gap: '1rem' }}>
            <div className="field" style={{ flex: 1 }}>
              <label className="field-label">{t('admin.fields.formDept')} <span className="req">*</span></label>
              <select
                className="select"
                value={formDeptId === '' ? '' : String(formDeptId)}
                onChange={(e) => onDeptChangeInForm(e.target.value === '' ? '' : Number(e.target.value))}
              >
                <option value="">{t('admin.fields.chooseDept')}</option>
                {departments.map(d => (
                  <option key={d.id} value={d.id}>{d.name}</option>
                ))}
              </select>
            </div>
            <div className="field" style={{ flex: 1 }}>
              <label className="field-label">{t('admin.fields.formSubcategory')} <span className="req">*</span></label>
              <select
                className="select"
                value={form.subcategoryId === '' ? '' : String(form.subcategoryId)}
                onChange={(e) => setForm({ ...form, subcategoryId: e.target.value === '' ? '' : Number(e.target.value) })}
                disabled={formDeptId === ''}
              >
                <option value="">{t('admin.fields.chooseSubcategory')}</option>
                {subsForForm.map(s => (
                  <option key={s.id} value={s.id}>{s.name}</option>
                ))}
              </select>
            </div>
          </div>
          <div className="field">
            <label className="field-label">{t('admin.fields.labelLabel')} <span className="req">*</span></label>
            <input
              className="input"
              value={form.label}
              onChange={(e) => onLabelChange(e.target.value)}
              placeholder={t('admin.fields.labelPlaceholder')}
              autoFocus
            />
          </div>
          <div className="field">
            <label className="field-label">{t('admin.fields.keyLabel')} <span className="req">*</span></label>
            <input
              className="input mono"
              value={form.fieldKey}
              disabled={isEdit}
              onChange={(e) => { setKeyEdited(true); setForm({ ...form, fieldKey: e.target.value }); }}
              placeholder="priority"
              dir="ltr"
            />
            <span className="small muted" style={{ textTransform: 'none', letterSpacing: 0 }}>
              {t('admin.fields.keyHint')}
            </span>
          </div>
          <div className="row" style={{ gap: '1rem' }}>
            <div className="field" style={{ flex: 1 }}>
              <label className="field-label">{t('admin.fields.typeLabel')}</label>
              <select
                className="select"
                value={form.type}
                onChange={(e) => setForm({ ...form, type: e.target.value as FieldType })}
              >
                {TYPES.map((tv) => (
                  <option key={tv} value={tv}>{t(`admin.fields.types.${tv}`)}</option>
                ))}
              </select>
            </div>
            <div className="field" style={{ width: 110 }}>
              <label className="field-label">{t('admin.fields.orderLabel')}</label>
              <input
                type="number"
                className="input"
                value={form.displayOrder}
                onChange={(e) => setForm({ ...form, displayOrder: parseInt(e.target.value) || 0 })}
              />
            </div>
          </div>
          {form.type === 'SELECT' && (
            <div className="field">
              <label className="field-label">{t('admin.fields.optionsLabel')} <span className="req">*</span></label>
              <input
                className="input"
                value={form.options}
                onChange={(e) => setForm({ ...form, options: e.target.value })}
                placeholder={t('admin.fields.optionsPlaceholder')}
              />
            </div>
          )}
          <div className="field">
            <label className="field-label">{t('admin.fields.placeholderLabel')}</label>
            <input
              className="input"
              value={form.placeholder}
              onChange={(e) => setForm({ ...form, placeholder: e.target.value })}
              placeholder={t('admin.fields.placeholderHint')}
            />
          </div>
          <div className="row" style={{ gap: '1.5rem', marginTop: '0.5rem' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }}>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={form.required}
                  onChange={(e) => setForm({ ...form, required: e.target.checked })}
                />
                <span className="slider" />
              </label>
              {t('common.required')}
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 10, cursor: 'pointer' }}>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={(e) => setForm({ ...form, active: e.target.checked })}
                />
                <span className="slider" />
              </label>
              {t('common.active')}
            </label>
          </div>
        </form>
      </Modal>
    </div>
  );
}

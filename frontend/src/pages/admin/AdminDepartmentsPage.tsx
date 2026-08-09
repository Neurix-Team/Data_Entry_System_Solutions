import { FormEvent, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, ticketsApi } from '../../api/resources';
import type { Department, Ticket } from '../../api/types';
import { IconBuilding, IconFolder, IconMembers } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useT } from '../../i18n';
import { DepartmentDetailModal } from './DepartmentDetailModal';

interface FormState {
  id?: number;
  name: string;
  active: boolean;
}

const empty: FormState = { name: '', active: true };

export function AdminDepartmentsPage() {
  const { t } = useT();
  const [items, setItems] = useState<Department[]>([]);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<FormState>(empty);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [detail, setDetail] = useState<Department | null>(null);

  async function refresh() {
    setLoading(true);
    try {
      const [d, page] = await Promise.all([
        departmentsApi.adminList(),
        ticketsApi.listAll(0, 200),
      ]);
      setItems(d);
      setTickets(page.items);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); }, []);

  function openCreate() { setForm(empty); setFormError(null); setModalOpen(true); }
  function openEdit(d: Department) {
    setForm({ id: d.id, name: d.name, active: d.active });
    setFormError(null); setModalOpen(true);
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    const name = form.name.trim();
    if (!name) { setFormError(t('admin.departments.nameRequired')); return; }
    setSaving(true);
    try {
      if (form.id) await departmentsApi.update(form.id, { name, active: form.active });
      else await departmentsApi.create(name, form.active);
      setModalOpen(false);
      refresh();
    } catch (err) {
      setFormError(extractError(err));
    } finally {
      setSaving(false);
    }
  }

  async function onDelete(d: Department) {
    if (!confirm(t('common.confirmDelete', { name: d.name }))) return;
    try { await departmentsApi.remove(d.id); refresh(); }
    catch (e) { alert(extractError(e)); }
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
        <button className="btn btn-primary" onClick={openCreate}>
          <IconBuilding size={16} /> {t('admin.departments.newBtn')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {loading ? (
        <div className="card empty-state">{t('common.loading')}</div>
      ) : items.length === 0 ? (
        <div className="card empty-state">{t('admin.departments.empty')}</div>
      ) : (
        <div className="dept-grid">
          {items.map((d, idx) => {
            const color = ((idx % 6) + 1);
            return (
              <div key={d.id} className="dept-card" data-color={color}>
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
                    <div className="dept-card-name">{d.name}</div>
                    <span className={`badge ${d.active ? 'badge-active' : 'badge-inactive'}`}>
                      {d.active ? t('common.active') : t('common.inactive')}
                    </span>
                  </div>
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

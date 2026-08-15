import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { dashboardApi, subcategoriesApi } from '../../api/resources';
import type { Department, DomainDetail, Subcategory } from '../../api/types';
import { IconClose, IconFolder, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useConfirm } from '../../components/ConfirmDialog';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

interface Props {
  department: Department;
  onClose: () => void;
  onChanged: () => void;
}

interface SubForm {
  id?: number;
  name: string;
  active: boolean;
}

export function DepartmentDetailModal({ department, onClose, onChanged }: Props) {
  const { t } = useT();
  const confirm = useConfirm();
  const toast = useToast();
  const [detail, setDetail] = useState<DomainDetail | null>(null);
  const [subs, setSubs] = useState<Subcategory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [addOpen, setAddOpen] = useState(false);
  const [subForm, setSubForm] = useState<SubForm>({ name: '', active: true });
  const [subError, setSubError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function refresh() {
    setLoading(true);
    try {
      const [d, s] = await Promise.all([
        dashboardApi.domain(department.id),
        subcategoriesApi.adminList(department.id),
      ]);
      setDetail(d);
      setSubs(s);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [department.id]);

  const dailyMax = useMemo(
    () => detail ? Math.max(1, ...detail.last30Days.map((d) => d.count)) : 1,
    [detail]
  );

  function openAddSub() {
    setSubForm({ name: '', active: true });
    setSubError(null);
    setAddOpen(true);
  }
  function openEditSub(s: Subcategory) {
    setSubForm({ id: s.id, name: s.name, active: s.active });
    setSubError(null);
    setAddOpen(true);
  }
  async function onSaveSub(e: FormEvent) {
    e.preventDefault();
    setSubError(null);
    const name = subForm.name.trim();
    if (!name) { setSubError(t('admin.subcategories.nameRequired')); return; }
    setSaving(true);
    try {
      if (subForm.id) {
        await subcategoriesApi.update(subForm.id, {
          departmentId: department.id,
          name,
          active: subForm.active,
        });
      } else {
        await subcategoriesApi.create({
          departmentId: department.id,
          name,
          active: subForm.active,
        });
      }
      setAddOpen(false);
      await refresh();
      onChanged();
    } catch (err) {
      setSubError(extractError(err));
    } finally {
      setSaving(false);
    }
  }
  async function onDeleteSub(s: Subcategory) {
    const ok = await confirm({
      message: t('common.confirmDelete', { name: s.name }),
      destructive: true,
    });
    if (!ok) return;
    try {
      await subcategoriesApi.remove(s.id);
      await refresh();
      onChanged();
    } catch (e) {
      toast.error(extractError(e));
    }
  }

  return (
    <Modal
      open
      title={`${department.name}`}
      onClose={onClose}
      footer={
        <button className="btn btn-secondary" onClick={onClose}>{t('common.close')}</button>
      }
    >
      {error && <div className="alert alert-error">{error}</div>}
      {loading || !detail ? (
        <div className="empty-state">{t('common.loading')}</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          {/* Status pill */}
          <div>
            <span className={`badge ${department.active ? 'badge-active' : 'badge-inactive'}`}>
              {department.active ? t('common.active') : t('common.inactive')}
            </span>
          </div>

          {/* Stat cards */}
          <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
            <div className="stat-card">
              <div className="stat-card-header">
                <span className="stat-card-label">{t('admin.deptDetail.tickets')}</span>
              </div>
              <div className="stat-card-value">{detail.totalTickets}</div>
            </div>
            <div className="stat-card">
              <div className="stat-card-header">
                <span className="stat-card-label">{t('admin.deptDetail.subcats')}</span>
              </div>
              <div className="stat-card-value">{detail.subcategories.length}</div>
            </div>
            <div className="stat-card">
              <div className="stat-card-header">
                <span className="stat-card-label">{t('admin.deptDetail.agents')}</span>
              </div>
              <div className="stat-card-value">{detail.activeAgents}</div>
            </div>
          </div>

          {/* 30-day chart */}
          <div>
            <div style={{
              fontWeight: 600, color: 'var(--text-secondary)',
              fontSize: 'var(--fs-sm)', textTransform: 'uppercase',
              letterSpacing: '0.05em', marginBottom: 8,
            }}>
              {t('admin.deptDetail.last30Days')}
            </div>
            <div className="bar-chart" style={{ height: 100 }}>
              {detail.last30Days.map((row, i) => {
                const h = (row.count / dailyMax) * 100;
                return (
                  <div key={row.date} className="bar-chart-item">
                    <div
                      className="bar-chart-bar"
                      data-color={(i % 6) + 1}
                      style={{ height: `${Math.max(3, h)}%` }}
                      title={`${row.date}: ${row.count}`}
                    />
                  </div>
                );
              })}
            </div>
          </div>

          {/* Subcategories section */}
          <div>
            <div className="row-between" style={{ alignItems: 'center', marginBottom: 8 }}>
              <div style={{
                fontWeight: 600, color: 'var(--text-secondary)',
                fontSize: 'var(--fs-sm)', textTransform: 'uppercase',
                letterSpacing: '0.05em',
              }}>
                {t('admin.deptDetail.subcatsList')} ({subs.length})
              </div>
              <button className="btn btn-primary btn-sm" onClick={openAddSub}>
                <IconPlus size={14} /> {t('admin.subcategories.newBtn')}
              </button>
            </div>
            {subs.length === 0 ? (
              <div className="empty-state">{t('admin.deptDetail.noSubcats')}</div>
            ) : (
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>{t('admin.subcategories.colName')}</th>
                      <th>{t('admin.subcategories.entries')}</th>
                      <th>{t('admin.subcategories.fields')}</th>
                      <th>{t('common.status')}</th>
                      <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {subs.map((s) => (
                      <tr key={s.id}>
                        <td style={{ fontWeight: 500 }}>
                          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                            <IconFolder size={14} /> {s.name}
                          </span>
                        </td>
                        <td>{s.ticketCount}</td>
                        <td>{s.fieldCount}</td>
                        <td>
                          <span className={`badge ${s.active ? 'badge-active' : 'badge-inactive'}`}>
                            {s.active ? t('common.active') : t('common.inactive')}
                          </span>
                        </td>
                        <td className="actions-cell">
                          <button className="btn btn-secondary btn-sm" onClick={() => openEditSub(s)}>
                            {t('common.edit')}
                          </button>
                          <button className="btn btn-danger btn-sm" onClick={() => onDeleteSub(s)}>
                            <IconClose size={14} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Nested add/edit modal for subcategories */}
      <Modal
        open={addOpen}
        title={subForm.id ? t('admin.subcategories.editTitle') : t('admin.subcategories.createTitle')}
        onClose={() => setAddOpen(false)}
        footer={
          <>
            <button className="btn btn-secondary" onClick={() => setAddOpen(false)}>{t('common.cancel')}</button>
            <button className="btn btn-primary" onClick={onSaveSub} disabled={saving}>
              {saving ? <span className="spinner" /> : t('common.save')}
            </button>
          </>
        }
      >
        {subError && <div className="alert alert-error">{subError}</div>}
        <form onSubmit={onSaveSub}>
          <div className="field">
            <label className="field-label">{t('admin.subcategories.colName')} <span className="req">*</span></label>
            <input
              className="input"
              value={subForm.name}
              onChange={(e) => setSubForm({ ...subForm, name: e.target.value })}
              placeholder={t('admin.subcategories.namePlaceholder')}
              autoFocus
            />
          </div>
          <div className="field">
            <label className="field-label" style={{
              display: 'flex', alignItems: 'center', gap: 12,
              textTransform: 'none', letterSpacing: 0, fontSize: 'var(--fs-sm)',
            }}>
              <label className="switch">
                <input
                  type="checkbox"
                  checked={subForm.active}
                  onChange={(e) => setSubForm({ ...subForm, active: e.target.checked })}
                />
                <span className="slider" />
              </label>
              {t('admin.subcategories.activeHint')}
            </label>
          </div>
        </form>
      </Modal>
    </Modal>
  );
}

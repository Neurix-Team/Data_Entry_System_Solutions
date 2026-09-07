import { FormEvent, ReactNode, useEffect, useRef, useState } from 'react';
import { Modal } from './Modal';
import { useT } from '../i18n';

interface Props {
  open: boolean;
  title: string;
  placeholder?: string;
  hint?: string;
  headerContent?: ReactNode;
  canSubmit?: boolean;
  onClose: () => void;
  onCreateEach: (name: string) => Promise<void>;
  onDone: (result: { created: number; failed: number; failures: string[] }) => void;
}

interface Row {
  key: number;
  value: string;
}

let rowKey = 1;
const newRow = (): Row => ({ key: rowKey++, value: '' });

export function BulkAddModal({
  open, title, placeholder, hint, headerContent, canSubmit = true,
  onClose, onCreateEach, onDone,
}: Props) {
  const { lang } = useT();
  const isAr = lang === 'ar';

  const [rows, setRows] = useState<Row[]>(() => [newRow()]);
  const [busy, setBusy] = useState(false);
  const inputRefs = useRef<Record<number, HTMLInputElement | null>>({});

  useEffect(() => {
    if (open) {
      setRows([newRow()]);
      setBusy(false);
    }
  }, [open]);

  const filled = rows.filter((r) => r.value.trim().length > 0);
  const count = filled.length;

  function updateRow(key: number, value: string) {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, value } : r)));
  }

  function addRow() {
    const r = newRow();
    setRows((rs) => [...rs, r]);
    setTimeout(() => inputRefs.current[r.key]?.focus(), 0);
  }

  function removeRow(key: number) {
    setRows((rs) => (rs.length === 1 ? [{ ...rs[0], value: '' }] : rs.filter((r) => r.key !== key)));
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (count === 0) return;
    setBusy(true);
    let created = 0;
    const failures: string[] = [];
    for (const r of filled) {
      try {
        await onCreateEach(r.value.trim());
        created++;
      } catch {
        failures.push(r.value.trim());
      }
    }
    setBusy(false);
    onDone({ created, failed: failures.length, failures });
  }

  return (
    <Modal
      open={open}
      title={title}
      onClose={busy ? () => undefined : onClose}
      footer={
        <>
          <button className="btn btn-secondary" onClick={onClose} disabled={busy}>
            {isAr ? 'إلغاء' : 'Cancel'}
          </button>
          <button
            className="btn btn-primary"
            onClick={onSubmit}
            disabled={busy || count === 0 || !canSubmit}
          >
            {busy
              ? (isAr ? 'جارٍ الحفظ…' : 'Saving…')
              : (isAr ? `حفظ ${count > 0 ? `(${count})` : ''}` : `Save ${count > 0 ? `(${count})` : ''}`).trim()}
          </button>
        </>
      }
    >
      <form onSubmit={onSubmit}>
        {headerContent}
        <div className="repeater">
          {rows.map((r, idx) => (
            <div className="repeater-row" key={r.key}>
              <input
                ref={(el) => { inputRefs.current[r.key] = el; }}
                className="input"
                value={r.value}
                onChange={(e) => updateRow(r.key, e.target.value)}
                placeholder={placeholder ?? (isAr ? 'الاسم' : 'Name')}
                disabled={busy}
                autoFocus={idx === 0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && idx === rows.length - 1) {
                    e.preventDefault();
                    if (r.value.trim().length > 0) addRow();
                  }
                }}
              />
              <button
                type="button"
                className="repeater-remove"
                onClick={() => removeRow(r.key)}
                aria-label={isAr ? 'حذف السطر' : 'Remove row'}
                title={isAr ? 'حذف السطر' : 'Remove row'}
                disabled={busy || (rows.length === 1 && r.value === '')}
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                </svg>
              </button>
            </div>
          ))}
        </div>

        <button
          type="button"
          className="repeater-add-btn"
          onClick={addRow}
          disabled={busy}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 5v14M5 12h14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          {isAr ? 'إضافة سطر جديد' : 'Add another'}
        </button>

        {hint && <p className="small muted repeater-hint">{hint}</p>}
      </form>
    </Modal>
  );
}

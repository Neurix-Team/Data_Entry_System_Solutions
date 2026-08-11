import { FormEvent, useEffect, useRef, useState } from 'react';
import { Modal } from './Modal';
import { useT } from '../i18n';

interface Props {
  open: boolean;
  title: string;
  /** Placeholder shown in every empty input row. */
  placeholder?: string;
  /** Optional helper line under the input list (e.g. "will be added under X department"). */
  hint?: string;
  onClose: () => void;
  /** Called once per non-empty row. Throw on failure — the modal collects errors. */
  onCreateEach: (name: string) => Promise<void>;
  /** Fires after the whole batch is attempted. */
  onDone: (result: { created: number; failed: number; failures: string[] }) => void;
}

interface Row {
  key: number;
  value: string;
}

let rowKey = 1;
const newRow = (): Row => ({ key: rowKey++, value: '' });

/**
 * Repeater "add many" modal. Starts with one text input; a leading + button appends another
 * empty row so the user can enter as many as they want. Each row has a × button to remove
 * it. Save walks the list and creates each non-empty row sequentially.
 */
export function BulkAddModal({
  open, title, placeholder, hint, onClose, onCreateEach, onDone,
}: Props) {
  const { lang } = useT();
  const isAr = lang === 'ar';

  const [rows, setRows] = useState<Row[]>(() => [newRow()]);
  const [busy, setBusy] = useState(false);
  const inputRefs = useRef<Record<number, HTMLInputElement | null>>({});

  // Reset the form each time the modal opens so it starts clean.
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
    // Focus the new row on the next tick.
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
          <button className="btn btn-primary" onClick={onSubmit} disabled={busy || count === 0}>
            {busy
              ? (isAr ? 'جارٍ الحفظ…' : 'Saving…')
              : (isAr ? `حفظ ${count > 0 ? `(${count})` : ''}` : `Save ${count > 0 ? `(${count})` : ''}`).trim()}
          </button>
        </>
      }
    >
      <form onSubmit={onSubmit}>
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
                  // Enter on the last row adds a new one so the user can keep typing.
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

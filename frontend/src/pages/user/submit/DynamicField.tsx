import type { ChangeEvent, ReactNode } from 'react';
import type { CustomField } from '../../../api/types';
import { useT } from '../../../i18n';

interface Props {
  field: CustomField;
  value: string;
  onChange: (v: string) => void;
  error?: string;
}

/** Renders one custom field (input / textarea / select / etc.) based on its type. */
export function DynamicField({ field, value, onChange, error }: Props) {
  const { t } = useT();
  const cls = error ? 'has-error' : '';
  const commonProps = {
    value,
    onChange: (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
      onChange(e.target.value),
    placeholder: field.placeholder ?? '',
  };

  let control: ReactNode;
  switch (field.type) {
    case 'TEXTAREA':
      control = <textarea className={`textarea ${cls}`} {...commonProps} />;
      break;
    case 'NUMBER':
      control = <input type="number" className={`input ${cls}`} {...commonProps} />;
      break;
    case 'URL':
      control = (
        <input
          type="url"
          className={`input ${cls}`}
          {...commonProps}
          placeholder={field.placeholder ?? 'https://…'}
          dir="ltr"
        />
      );
      break;
    case 'EMAIL':
      control = <input type="email" className={`input ${cls}`} {...commonProps} dir="ltr" />;
      break;
    case 'DATE':
      control = <input type="date" className={`input ${cls}`} {...commonProps} />;
      break;
    case 'SELECT': {
      const opts = (field.options ?? '').split(',').map((s) => s.trim()).filter(Boolean);
      control = (
        <select className={`select ${cls}`} value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">{field.placeholder || t('user.submit.chooseDepartment')}</option>
          {opts.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      );
      break;
    }
    default:
      control = <input type="text" className={`input ${cls}`} {...commonProps} />;
  }

  return (
    <div className="field">
      <label className="field-label">
        {field.label}{field.required && <span className="req">*</span>}
      </label>
      {control}
      {error && <span className="field-error">{error}</span>}
    </div>
  );
}

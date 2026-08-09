import type { CustomField } from '../../../api/types';
import { useT } from '../../../i18n';
import { DynamicField } from './DynamicField';

interface Props {
  fields: CustomField[];
  values: Record<string, string>;
  errors: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
}

/** Shared custom fields (rendered once per submission, values applied to every article). */
export function CustomFieldsSection({ fields, values, errors, onChange }: Props) {
  const { t } = useT();
  if (fields.length === 0) return null;

  return (
    <>
      <div className="form-section-heading">{t('user.submit.sharedFields')}</div>
      {fields.map((f) => (
        <DynamicField
          key={f.id}
          field={f}
          value={values[f.fieldKey] ?? ''}
          onChange={(v) => onChange({ ...values, [f.fieldKey]: v })}
          error={errors[f.fieldKey]}
        />
      ))}
    </>
  );
}

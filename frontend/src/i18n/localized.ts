/**
 * Small helper for the "one field, two languages" DTOs the backend exposes: every entity
 * comes with `<field>` (the original text) plus `<field>En` and `<field>Ar` bilingual
 * mirrors. Pages should never read `<field>` directly if a bilingual pair exists — that
 * hardcodes the language and defeats i18n.
 *
 * Usage:
 *   const label = pickLocalized(dept, 'name', lang);
 *   // returns dept.nameAr when lang==='ar' and it's non-empty, else nameEn, else name.
 */
export type Lang = 'ar' | 'en';

type Suffix<L extends Lang> = L extends 'ar' ? 'Ar' : 'En';

type WithLocale<F extends string, L extends Lang> = {
  [K in F | `${F}${Suffix<L>}`]?: string | null;
};

export function pickLocalized<F extends string, L extends Lang>(
  obj: WithLocale<F, L> | null | undefined,
  field: F,
  lang: L,
): string {
  if (!obj) return '';
  const suffix: 'Ar' | 'En' = lang === 'ar' ? 'Ar' : 'En';
  const localized = (obj as Record<string, string | null | undefined>)[`${field}${suffix}`];
  if (localized && localized.trim().length > 0) return localized;
  const opposite: 'Ar' | 'En' = lang === 'ar' ? 'En' : 'Ar';
  const other = (obj as Record<string, string | null | undefined>)[`${field}${opposite}`];
  if (other && other.trim().length > 0) return other;
  const raw = (obj as Record<string, string | null | undefined>)[field];
  return raw ?? '';
}

import { useTheme } from '../context/ThemeContext';
import { useT } from '../i18n';

export function PreferencesToggle() {
  const { theme, toggle } = useTheme();
  const { lang, setLang, t } = useT();
  const isDark = theme === 'dark';

  return (
    <>
      <button
        type="button"
        className="icon-btn"
        onClick={toggle}
        title={isDark ? t('theme.toggleLight') : t('theme.toggleDark')}
        aria-label={isDark ? t('theme.toggleLight') : t('theme.toggleDark')}
      >
        {isDark ? '☀' : '☾'}
      </button>
      <button
        type="button"
        className="icon-btn"
        onClick={() => setLang(lang === 'en' ? 'ar' : 'en')}
        title={t('lang.toggle')}
        aria-label={t('lang.toggle')}
        style={{ width: 'auto', padding: '0 12px', fontSize: '0.8rem', fontWeight: 600, borderRadius: 999 }}
      >
        {lang === 'en' ? 'العربية' : 'EN'}
      </button>
    </>
  );
}

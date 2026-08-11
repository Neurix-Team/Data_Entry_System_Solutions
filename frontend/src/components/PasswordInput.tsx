import { forwardRef, InputHTMLAttributes, useState } from 'react';

/**
 * A password input with a trailing eye toggle that reveals/hides the value.
 * Drop-in for &lt;input type="password" className="input" /&gt; — accepts every native
 * input prop plus an optional inputClassName for cases where the wrapping input
 * needs a bespoke class (e.g. the auth form on the login page).
 */

type BaseProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>;

interface Props extends BaseProps {
  /** Class applied to the actual &lt;input&gt;. Defaults to "input" so it inherits the design-system look. */
  inputClassName?: string;
  /** Optional label for the toggle button; falls back to bilingual defaults. */
  showLabel?: string;
  hideLabel?: string;
}

export const PasswordInput = forwardRef<HTMLInputElement, Props>(function PasswordInput(
  { inputClassName = 'input', showLabel, hideLabel, ...rest },
  ref,
) {
  const [visible, setVisible] = useState(false);
  const label = visible
    ? (hideLabel ?? 'Hide password')
    : (showLabel ?? 'Show password');

  return (
    <span className="password-field">
      <input
        {...rest}
        ref={ref}
        type={visible ? 'text' : 'password'}
        className={`${inputClassName} password-field-input`}
      />
      <button
        type="button"
        className="password-field-toggle"
        onClick={() => setVisible((v) => !v)}
        aria-label={label}
        aria-pressed={visible}
        title={label}
        tabIndex={-1}
      >
        {visible ? (
          // Eye-off — line through the eye
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M3 3l18 18M10.6 10.6a2 2 0 002.8 2.8M9.9 5.1A10.4 10.4 0 0112 5c5 0 9 3.5 10 7-.4 1.4-1.4 2.9-2.8 4.1M6.1 6.1C4 7.3 2.6 9.1 2 12c1 3.5 5 7 10 7 1.8 0 3.4-.5 4.8-1.3"
              stroke="currentColor"
              strokeWidth="1.7"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        ) : (
          // Eye — visible
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"
              stroke="currentColor"
              strokeWidth="1.7"
              strokeLinejoin="round"
            />
            <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.7" />
          </svg>
        )}
      </button>
    </span>
  );
});

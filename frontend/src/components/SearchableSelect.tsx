import { useEffect, useMemo, useRef, useState } from 'react';
import { IconChevronDown } from './Icons';
import { useT } from '../i18n';

export interface SearchableSelectOption {
  value: string;
  label: string;
  /** Matched by search too, shown as a hint under the label — e.g. a department's project. */
  sublabel?: string;
}

interface Props {
  id?: string;
  options: SearchableSelectOption[];
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  placeholder?: string;
  required?: boolean;
  'aria-invalid'?: boolean;
}

/**
 * A `<select>` that filters as you type, for a list too long to scroll through — this
 * project's picker can easily run past a hundred rows for an active team. Typing narrows the
 * list to whatever matches; the arrow keys and Enter still work exactly as a native select
 * would, so nothing about the keyboard-first submit form (see useFastEntry) has to change.
 *
 * <p>It never invents a selection: closing the field with unmatched or partial text snaps
 * back to whatever was last actually chosen, so this can never submit a value that was only
 * ever typed and not picked.</p>
 */
export function SearchableSelect({
  id, options, value, onChange, disabled, placeholder, required, 'aria-invalid': ariaInvalid,
}: Props) {
  const { t, dir } = useT();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [highlight, setHighlight] = useState(0);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listboxId = useRef(`ssel-list-${Math.random().toString(36).slice(2, 9)}`);

  const selected = useMemo(() => options.find((o) => o.value === value) ?? null, [options, value]);

  // The query box shows the chosen label while closed; it only becomes a live filter once the
  // field is open and someone starts typing, so a plain click always shows the full list.
  const displayValue = open ? query : (selected?.label ?? '');

  const filtered = useMemo(() => {
    const term = query.trim().toLowerCase();
    if (!term) return options;
    return options.filter((o) =>
      o.label.toLowerCase().includes(term) || (o.sublabel ?? '').toLowerCase().includes(term));
  }, [options, query]);

  useEffect(() => {
    if (!open) return;
    function onDocMouseDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) close();
    }
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, [open]);

  useEffect(() => {
    if (open) setHighlight(0);
  }, [open, query]);

  function openList() {
    if (disabled) return;
    setQuery('');
    setOpen(true);
  }

  function close() {
    setOpen(false);
    setQuery('');
  }

  function choose(opt: SearchableSelectOption) {
    onChange(opt.value);
    close();
    inputRef.current?.blur();
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (disabled) return;
    if (!open && (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter')) {
      e.preventDefault();
      e.stopPropagation();
      openList();
      return;
    }
    if (!open) return;
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        e.stopPropagation();
        setHighlight((h) => Math.min(h + 1, filtered.length - 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        e.stopPropagation();
        setHighlight((h) => Math.max(h - 1, 0));
        break;
      case 'Enter':
        e.preventDefault();
        e.stopPropagation();
        if (filtered[highlight]) choose(filtered[highlight]);
        break;
      case 'Escape':
        e.preventDefault();
        e.stopPropagation();
        close();
        inputRef.current?.blur();
        break;
      case 'Tab':
        close();
        break;
      default:
        break;
    }
  }

  const activeId = open && filtered[highlight] ? `${listboxId.current}-${highlight}` : undefined;

  return (
    <div ref={rootRef} style={{ position: 'relative' }}>
      <input
        ref={inputRef}
        id={id}
        role="combobox"
        aria-expanded={open}
        aria-controls={listboxId.current}
        aria-activedescendant={activeId}
        aria-autocomplete="list"
        aria-invalid={ariaInvalid || undefined}
        className="input"
        style={{ paddingInlineEnd: '2.25rem', cursor: disabled ? 'not-allowed' : 'text' }}
        value={displayValue}
        placeholder={placeholder}
        disabled={disabled}
        required={required}
        autoComplete="off"
        onFocus={openList}
        onClick={openList}
        onChange={(e) => { setQuery(e.target.value); if (!open) setOpen(true); }}
        onKeyDown={onKeyDown}
        onBlur={() => { if (!disabled) setOpen(false); }}
      />
      <span
        aria-hidden="true"
        style={{
          position: 'absolute', top: '50%', transform: 'translateY(-50%)',
          insetInlineEnd: '0.75rem', pointerEvents: 'none', display: 'flex', color: 'var(--text-tertiary)',
        }}
      >
        <IconChevronDown size={16} />
      </span>

      {open && !disabled && (
        <ul
          id={listboxId.current}
          role="listbox"
          dir={dir}
          className="searchable-select-panel"
          style={{ insetInlineStart: 0, insetInlineEnd: 0 }}
        >
          {filtered.length === 0 ? (
            <li className="searchable-select-empty">{t('common.noMatches')}</li>
          ) : (
            filtered.map((opt, i) => (
              <li
                key={opt.value}
                id={`${listboxId.current}-${i}`}
                role="option"
                aria-selected={opt.value === value}
                // onMouseDown (not onClick) fires before the input's onBlur closes the list.
                onMouseDown={(e) => { e.preventDefault(); choose(opt); }}
                onMouseEnter={() => setHighlight(i)}
                className={
                  'searchable-select-option'
                  + (i === highlight ? ' is-highlighted' : '')
                  + (opt.value === value ? ' is-selected' : '')
                }
              >
                <span>{opt.label}</span>
                {opt.sublabel && <span className="muted small">{opt.sublabel}</span>}
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}

import { RefObject, useEffect } from 'react';

/**
 * Keyboard-first entry for the submit form.
 *
 * <p>The people using this screen fill the same shapes hundreds of times a day, so reaching
 * for the mouse between every field is the slowest part of their work. Enter walks down the
 * form instead of submitting it — which also removes the oldest hazard of a long HTML form,
 * where one stray Enter posted half-finished work — and Ctrl+Enter is the deliberate way to
 * send it.</p>
 *
 * <p>Everything is bound on window rather than on the form node: the form only exists after
 * its data has loaded, and a listener attached on mount would be bound to nothing.</p>
 */

/** What Enter walks through. Buttons, files and toggles are reached with Tab as usual. */
const FIELDS = [
  'input:not([type="hidden"]):not([type="file"]):not([type="checkbox"]):not([type="radio"])',
  'select',
  'textarea',
].join(',');

/** Marks the first field of an article card, so a new card can be typed into at once. */
export const ARTICLE_TITLE_ATTR = 'data-fast-entry';
export const ARTICLE_TITLE_VALUE = 'article-title';

export interface FastEntryActions {
  /** Adds a card; the hook focuses its title once React has rendered it. */
  onAddArticle: () => void;
  onShowShortcuts: () => void;
  /** While a submit is in flight the shortcuts stand down. */
  disabled?: boolean;
}

export function useFastEntry(
  formRef: RefObject<HTMLFormElement | null>,
  { onAddArticle, onShowShortcuts, disabled = false }: FastEntryActions,
) {
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      // An IME composes Arabic and CJK text with Enter; stealing it would eat the word.
      if (e.isComposing || disabled) return;
      const form = formRef.current;
      if (!form) return;
      const target = e.target as HTMLElement | null;
      const inForm = !!target && form.contains(target);

      // "?" opens the list, but only when it is not simply being typed into a field.
      if (e.key === '?' && !typingInto(target)) {
        e.preventDefault();
        onShowShortcuts();
        return;
      }

      if (e.altKey && !e.ctrlKey && !e.metaKey && e.key.toLowerCase() === 'n') {
        e.preventDefault();
        onAddArticle();
        focusNewArticleTitle();
        return;
      }

      if (e.key !== 'Enter' || !inForm) return;

      if (e.ctrlKey || e.metaKey) {
        // The deliberate send, available from any field including a textarea.
        e.preventDefault();
        form.requestSubmit();
        return;
      }

      if (e.shiftKey || e.altKey) return;
      // A textarea keeps Enter for what Enter means there: a new line.
      if (!target || target.tagName === 'TEXTAREA') return;
      if (target.tagName !== 'INPUT' && target.tagName !== 'SELECT') return;

      e.preventDefault();
      focusNext(form, target);
    }

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [formRef, onAddArticle, onShowShortcuts, disabled]);
}

/** True when the key would otherwise be part of what someone is writing. */
function typingInto(target: HTMLElement | null): boolean {
  if (!target) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
}

function fieldsOf(form: HTMLFormElement): HTMLElement[] {
  return Array.from(form.querySelectorAll<HTMLElement>(FIELDS)).filter(isReachable);
}

/** Hidden and disabled fields are skipped: Enter must land somewhere the cursor can go. */
function isReachable(el: HTMLElement): boolean {
  if ((el as HTMLInputElement).disabled || el.getAttribute('aria-hidden') === 'true') return false;
  if ((el as HTMLInputElement).readOnly) return false;
  return el.offsetParent !== null || el.getClientRects().length > 0;
}

function focusNext(form: HTMLFormElement, current: HTMLElement) {
  const fields = fieldsOf(form);
  const index = fields.indexOf(current);
  const next = index >= 0 ? fields[index + 1] : undefined;
  if (next) {
    next.focus();
    if (next instanceof HTMLInputElement && next.type !== 'date') next.select();
    return;
  }
  // Past the last field: offer the send button rather than swallowing the key silently.
  form.querySelector<HTMLButtonElement>('button[type="submit"]')?.focus();
}

/** The card does not exist until React has rendered it, hence the frame of patience. */
function focusNewArticleTitle() {
  requestAnimationFrame(() => {
    const titles = document.querySelectorAll<HTMLElement>(
      `[${ARTICLE_TITLE_ATTR}="${ARTICLE_TITLE_VALUE}"]`,
    );
    const last = titles[titles.length - 1];
    last?.focus();
    last?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  });
}

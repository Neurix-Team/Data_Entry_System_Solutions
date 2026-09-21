import { useCallback, useState } from 'react';

export interface DraftArticle {
  title: string;
  content: string;
  resources: Array<{ name: string; link: string }>;
}

export interface SubmitDraft {
  savedAt: string;
  projectId: string | number | null;
  departmentId: string | number | null;
  subcategoryId: string | number | null;
  customValues: Record<string, string>;
  articles: DraftArticle[];
}

/**
 * Autosave for the submit form (B1): localStorage-backed, per user. Files and
 * extracted images cannot be serialized — the draft restores every text field,
 * the selections and the resource links, and the banner is honest about files.
 */
export function useSubmitDraft(userId: number | undefined) {
  const key = `dems.draft.submit.${userId ?? 'anon'}`;
  const [savedAt, setSavedAt] = useState<string | null>(null);

  const read = useCallback((): SubmitDraft | null => {
    try {
      const raw = localStorage.getItem(key);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as SubmitDraft;
      if (!parsed || !Array.isArray(parsed.articles) || parsed.articles.length === 0) return null;
      return parsed;
    } catch {
      return null;
    }
  }, [key]);

  const save = useCallback((draft: Omit<SubmitDraft, 'savedAt'>): string | null => {
    try {
      const full: SubmitDraft = { ...draft, savedAt: new Date().toISOString() };
      localStorage.setItem(key, JSON.stringify(full));
      setSavedAt(full.savedAt);
      return full.savedAt;
    } catch {
      return null; // private mode / quota exceeded — silently keep going
    }
  }, [key]);

  const clear = useCallback(() => {
    try { localStorage.removeItem(key); } catch { /* ignore */ }
    setSavedAt(null);
  }, [key]);

  return { read, save, clear, savedAt, key };
}
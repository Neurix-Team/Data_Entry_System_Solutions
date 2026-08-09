import { useCallback, useRef, useState } from 'react';
import type { ArticleRow } from './ArticleCard';

function newRow(id: number): ArticleRow {
  return { id, title: '', content: '', websiteName: '', websiteLink: '' };
}

/** Manages the list of articles being submitted. Keeps a stable ID counter across renders. */
export function useArticles() {
  const nextId = useRef(1);
  const [articles, setArticles] = useState<ArticleRow[]>(() => [newRow(0)]);

  const add = useCallback(() => {
    const id = nextId.current++;
    setArticles((prev) => [...prev, newRow(id)]);
  }, []);

  const remove = useCallback((id: number) => {
    setArticles((prev) => (prev.length === 1 ? prev : prev.filter((a) => a.id !== id)));
  }, []);

  const update = useCallback((id: number, patch: Partial<ArticleRow>) => {
    setArticles((prev) => prev.map((a) => (a.id === id ? { ...a, ...patch } : a)));
  }, []);

  const reset = useCallback(() => {
    nextId.current = 1;
    setArticles([newRow(0)]);
  }, []);

  return { articles, add, remove, update, reset };
}

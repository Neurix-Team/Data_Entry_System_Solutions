import { useCallback, useRef, useState } from 'react';
import type { ArticleRow, DocumentRow, ResourceRow } from './ArticleCard';

function newRow(id: number, resourceId: number, documentId: number): ArticleRow {
  return {
    id,
    title: '',
    content: '',
    resources: [{ id: resourceId, name: '', link: '' }],
    documents: [{ id: documentId, name: '', file: null }],
  };
}

/** Manages the list of articles (and each article's resources & documents) being submitted.
 *  Keeps stable ID counters across renders so React keys stay stable. */
export function useArticles() {
  const nextArticleId = useRef(1);
  const nextResourceId = useRef(2);
  const nextDocumentId = useRef(2);
  const [articles, setArticles] = useState<ArticleRow[]>(() => [newRow(0, 0, 0)]);

  const add = useCallback(() => {
    const id = nextArticleId.current++;
    const rid = nextResourceId.current++;
    const did = nextDocumentId.current++;
    setArticles((prev) => [...prev, newRow(id, rid, did)]);
  }, []);

  const remove = useCallback((id: number) => {
    setArticles((prev) => (prev.length === 1 ? prev : prev.filter((a) => a.id !== id)));
  }, []);

  const update = useCallback((id: number, patch: Partial<ArticleRow>) => {
    setArticles((prev) => prev.map((a) => (a.id === id ? { ...a, ...patch } : a)));
  }, []);

  const addResource = useCallback((articleId: number) => {
    const rid = nextResourceId.current++;
    setArticles((prev) => prev.map((a) => a.id === articleId
      ? { ...a, resources: [...a.resources, { id: rid, name: '', link: '' }] }
      : a));
  }, []);

  const removeResource = useCallback((articleId: number, resourceId: number) => {
    setArticles((prev) => prev.map((a) => a.id === articleId
      ? { ...a, resources: a.resources.filter((r) => r.id !== resourceId) }
      : a));
  }, []);

  const updateResource = useCallback(
    (articleId: number, resourceId: number, patch: Partial<ResourceRow>) => {
      setArticles((prev) => prev.map((a) => a.id === articleId
        ? {
            ...a,
            resources: a.resources.map((r) => (r.id === resourceId ? { ...r, ...patch } : r)),
          }
        : a));
    },
    []
  );

  const addDocument = useCallback((articleId: number) => {
    const did = nextDocumentId.current++;
    setArticles((prev) => prev.map((a) => a.id === articleId
      ? { ...a, documents: [...a.documents, { id: did, name: '', file: null }] }
      : a));
  }, []);

  const removeDocument = useCallback((articleId: number, documentId: number) => {
    setArticles((prev) => prev.map((a) => a.id === articleId
      ? { ...a, documents: a.documents.filter((d) => d.id !== documentId) }
      : a));
  }, []);

  const updateDocument = useCallback(
    (articleId: number, documentId: number, patch: Partial<DocumentRow>) => {
      setArticles((prev) => prev.map((a) => a.id === articleId
        ? {
            ...a,
            documents: a.documents.map((d) => (d.id === documentId ? { ...d, ...patch } : d)),
          }
        : a));
    },
    []
  );

  const reset = useCallback(() => {
    nextArticleId.current = 1;
    nextResourceId.current = 2;
    nextDocumentId.current = 2;
    setArticles([newRow(0, 0, 0)]);
  }, []);

  return {
    articles,
    add, remove, update,
    addResource, removeResource, updateResource,
    addDocument, removeDocument, updateDocument,
    reset,
  };
}

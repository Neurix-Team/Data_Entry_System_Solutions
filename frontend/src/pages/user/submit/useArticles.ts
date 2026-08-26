import { useCallback, useRef, useState } from 'react';
import type { ArticleRow, DocumentRow, ExtractedImageRow, ResourceRow } from './ArticleCard';
import type { ExtractedImage } from '../../../api/types';

function newRow(id: number, resourceId: number, documentId: number): ArticleRow {
  return {
    id,
    title: '',
    content: '',
    resources: [{ id: resourceId, name: '', link: '' }],
    documents: [{ id: documentId, name: '', file: null }],
    extractedImages: [],
  };
}

/** Manages the list of articles (and each article's resources & documents) being submitted.
 *  Keeps stable ID counters across renders so React keys stay stable. */
export function useArticles() {
  const nextArticleId = useRef(1);
  const nextResourceId = useRef(2);
  const nextDocumentId = useRef(2);
  const nextImageId = useRef(1);
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

  /** Merge a batch of freshly-extracted images into an article. Called from
   *  SubmitTicketPage when the user inserts a PDF extraction result. */
  const appendExtractedImages = useCallback(
    (articleId: number, extractionId: string, images: ExtractedImage[], suggestedNamePrefix: string) => {
      if (images.length === 0) return;
      const rows: ExtractedImageRow[] = images.map((img, idx) => ({
        id: nextImageId.current++,
        name: `${suggestedNamePrefix} — ${idx + 1}`.slice(0, 250),
        url: img.url,
        extractionId,
        filename: img.filename,
        page: img.page,
        width: img.width,
        height: img.height,
      }));
      setArticles((prev) => prev.map((a) => a.id === articleId
        ? { ...a, extractedImages: [...a.extractedImages, ...rows] }
        : a));
    },
    []
  );

  const removeExtractedImage = useCallback((articleId: number, rowId: number) => {
    setArticles((prev) => prev.map((a) => a.id === articleId
      ? { ...a, extractedImages: a.extractedImages.filter((r) => r.id !== rowId) }
      : a));
  }, []);

  const updateExtractedImage = useCallback(
    (articleId: number, rowId: number, patch: Partial<ExtractedImageRow>) => {
      setArticles((prev) => prev.map((a) => a.id === articleId
        ? {
            ...a,
            extractedImages: a.extractedImages.map((r) => (r.id === rowId ? { ...r, ...patch } : r)),
          }
        : a));
    },
    []
  );

  /**
     * Bulk-add multiple files as new articles in one go. Each file becomes its own
     * article with the title auto-extracted from the filename (extension stripped and
     * separators normalised) and a pre-populated document row carrying the file itself.
     * Powers the "Upload multiple files" button on the submit form — the user picks N
     * files, gets N titled article rows they can still edit before submitting.
     */
  const addArticlesFromFiles = useCallback((files: File[]) => {
    if (files.length === 0) return;
    const rows: ArticleRow[] = files.map((file) => {
      const id = nextArticleId.current++;
      const rid = nextResourceId.current++;
      const did = nextDocumentId.current++;
      const rawName = file.name.replace(/\.[^./\\]+$/, '');
      const title = rawName.replace(/[_\-.]+/g, ' ').replace(/\s+/g, ' ').trim() || file.name;
      return {
        id,
        title,
        content: '',
        resources: [{ id: rid, name: '', link: '' }],
        documents: [{ id: did, name: title, file }],
        extractedImages: [],
      };
    });
    setArticles((prev) => {
      // If the only existing row is the default blank one, replace it; otherwise append.
      const isBlank = prev.length === 1
        && prev[0].title === ''
        && prev[0].content === ''
        && prev[0].documents.every((d) => d.file == null)
        && prev[0].resources.every((r) => r.link === '' && r.name === '')
        && prev[0].extractedImages.length === 0;
      return isBlank ? rows : [...prev, ...rows];
    });
  }, []);

  const reset = useCallback(() => {
    nextArticleId.current = 1;
    nextResourceId.current = 2;
    nextDocumentId.current = 2;
    nextImageId.current = 1;
    setArticles([newRow(0, 0, 0)]);
  }, []);

  return {
    articles,
    add, remove, update,
    addResource, removeResource, updateResource,
    addDocument, removeDocument, updateDocument,
    appendExtractedImages, removeExtractedImage, updateExtractedImage,
    addArticlesFromFiles,
    reset,
  };
}

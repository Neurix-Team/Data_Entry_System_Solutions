import { api, API_BASE } from './client';
import type { ExplorerManifest, ExplorerArchiveOptions } from './super';

export type CleanedStatus = 'READY' | 'IN_PROGRESS' | 'COMPLETED';
export interface CleanedMetadata {
  title: string; cleanedOn: string; dueOn: string | null; sourceReference: string; notes: string;
}
export interface CleanedFile extends CleanedMetadata {
  id: number; originalFilename: string; sizeBytes: number; sha256: string;
  teamId: number | null; teamName: string | null; projectId: number; projectName: string;
  departmentId: number; departmentName: string; status: CleanedStatus; uploadedBy: string;
  uploadedAt: string; updatedAt: string; startedAt: string | null; completedAt: string | null; version: number;
}
export interface CleanedOptions {
  projects: { id: number; name: string; teamId: number | null; teamName: string | null }[];
  departments: { id: number; name: string; projectId: number }[];
  maxFileBytes: number; extensions: string[];
}
export interface CleanedPage {
  items: CleanedFile[]; total: number; page: number; totalPages: number;
  ready: number; inProgress: number; completed: number;
}
export interface CleanedQuery {
  projectId?: number; departmentId?: number; status?: CleanedStatus; from?: string; to?: string; search?: string; page: number;
}
export type CleanedFilters = Omit<CleanedQuery, 'page'>;
export interface CleanedDeleteRequest { ids?: number[]; filters?: CleanedFilters; excludedIds?: number[]; expectedCount: number; }
function exportParams(query: CleanedFilters) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) if (value !== undefined && value !== '') params.set(key, String(value));
  return params;
}
export const cleanedFilesApi = {
  options: () => api.get<CleanedOptions>('/super/cleaned-files/options').then(r => r.data),
  list: (query: CleanedQuery, signal: AbortSignal) => api.get<CleanedPage>('/super/cleaned-files', { params: query, signal }).then(r => r.data),
  upload: (projectId: number, departmentId: number, metadata: CleanedMetadata, file: File, progress: (value: number) => void) => {
    const form = new FormData();
    form.append('projectId', String(projectId)); form.append('departmentId', String(departmentId));
    form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' })); form.append('file', file);
    return api.post<CleanedFile>('/super/cleaned-files', form, {
      onUploadProgress: event => progress(Math.round(100 * event.loaded / (event.total || file.size))),
    }).then(r => r.data);
  },
  update: (file: CleanedFile, metadata: CleanedMetadata, status: CleanedStatus) =>
    api.put<CleanedFile>(`/super/cleaned-files/${file.id}`, { metadata, status, version: file.version }).then(r => r.data),
  downloadUrl: (id: number) => `${API_BASE}/super/cleaned-files/${id}/download`,
  manifest: (query: CleanedFilters, includeText = false) => api.get<ExplorerManifest>('/super/cleaned-files/manifest', { params: { ...query, includeText } }).then(r => r.data),
  archiveUrl: (query: CleanedFilters, options: ExplorerArchiveOptions) => {
    const params = exportParams(query);
    params.set('fileType', options.fileType || 'all');
    params.set('prefixNames', String(options.prefixNames)); params.set('includeText', String(options.includeText));
    return `${API_BASE}/super/cleaned-files/archive?${params}`;
  },
  delete: (request: CleanedDeleteRequest) => api.post<{ deleted: number }>('/super/cleaned-files/bulk-delete', request).then(r => r.data),
};

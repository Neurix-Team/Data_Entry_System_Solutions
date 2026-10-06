import { api, API_BASE } from './client';

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
};

import { api, API_BASE } from './client';
import type {
  AdminStats,
  AdminUser,
  AiCheckResponse,
  ArticleInput,
  BulkCreateResponse,
  CustomField,
  Department,
  DomainDetail,
  DomainStats,
  ExtractedPdf,
  LeaderboardResponse,
  MyDashboard,
  NotificationFeed,
  NotificationItem,
  Project,
  ProjectFolderDetail,
  ProjectFolderSummary,
  ProjectStatus,
  QuickUploadResult,
  ReportData,
  Subcategory,
  SubcategoryStats,
  Ticket,
  TicketDocument,
  TicketPage,
  TicketStatus,
  UserActivity,
} from './types';

// Users (admin)
export const usersApi = {
  list: () => api.get<AdminUser[]>('/admin/users').then(r => r.data),
  create: (payload: {
    username: string; password: string;
    displayName?: string; email?: string; phone?: string;
    role: 'ADMIN' | 'USER';
  }) => api.post<AdminUser>('/admin/users', payload).then(r => r.data),
  update: (id: number, payload: {
    displayName?: string; email?: string; phone?: string;
    password?: string; active?: boolean;
  }) => api.patch<AdminUser>(`/admin/users/${id}`, payload).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/users/${id}`).then(() => undefined),
};

// Departments
export const departmentsApi = {
  adminList: () => api.get<Department[]>('/admin/departments').then(r => r.data),
  /** projectId → only departments in that project (cascading dropdown in submit form). */
  userList: (projectId?: number | null, signal?: AbortSignal) =>
    api.get<Department[]>('/departments', {
      params: projectId ? { projectId } : {},
      signal,
    }).then(r => r.data),
  create: (payload: { name: string; projectId: number; active?: boolean }) =>
    api.post<Department>('/admin/departments', {
      name: payload.name,
      projectId: payload.projectId,
      active: payload.active ?? true,
    }).then(r => r.data),
  update: (id: number, payload: { name: string; projectId: number; active?: boolean }) =>
    api.patch<Department>(`/admin/departments/${id}`, payload).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/departments/${id}`).then(() => undefined),
};

// Subcategories
export const subcategoriesApi = {
  adminList: (departmentId?: number) =>
    api.get<Subcategory[]>('/admin/subcategories', {
      params: departmentId ? { departmentId } : {},
    }).then(r => r.data),
  /**
   * User-visible list. Pass {@code departmentId} to filter to one department, or
   * {@code projectId} to get every active subcategory under a project (across all
   * of that project's departments). With no filter the server scopes to the
   * caller's assigned projects.
   */
  userList: (
    filter?: { departmentId?: number | null; projectId?: number | null },
    signal?: AbortSignal,
  ) => {
    const params: Record<string, number> = {};
    if (filter?.departmentId) params.departmentId = filter.departmentId;
    if (filter?.projectId) params.projectId = filter.projectId;
    return api.get<Subcategory[]>('/subcategories', { params, signal }).then(r => r.data);
  },
  create: (payload: { departmentId: number; name: string; active?: boolean }) =>
    api.post<Subcategory>('/admin/subcategories', payload).then(r => r.data),
  update: (id: number, payload: { departmentId: number; name: string; active?: boolean }) =>
    api.patch<Subcategory>(`/admin/subcategories/${id}`, payload).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/subcategories/${id}`).then(() => undefined),
};

// Custom fields
export const fieldsApi = {
  adminList: (subcategoryId?: number) =>
    api.get<CustomField[]>('/admin/fields', {
      params: subcategoryId ? { subcategoryId } : {},
    }).then(r => r.data),
  activeList: (subcategoryId?: number, signal?: AbortSignal) =>
    api.get<CustomField[]>('/fields', {
      params: subcategoryId ? { subcategoryId } : {},
      signal,
    }).then(r => r.data),
  create: (payload: Omit<CustomField, 'id' | 'departmentId' | 'departmentName' | 'subcategoryName'>) =>
    api.post<CustomField>('/admin/fields', payload).then(r => r.data),
  update: (id: number, payload: Omit<CustomField, 'id' | 'departmentId' | 'departmentName' | 'subcategoryName'>) =>
    api.patch<CustomField>(`/admin/fields/${id}`, payload).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/fields/${id}`).then(() => undefined),
};

// Tickets
export const ticketsApi = {
  submit: (payload: {
    departmentId: number;
    subcategoryId: number;
    projectId?: number | null;
    title: string;
    content: string;
    websiteName?: string;
    websiteLink?: string;
    customValues: Record<string, string>;
  }) => api.post<Ticket>('/user/tickets', payload).then(r => r.data),
  submitBulk: (payload: {
    departmentId?: number | null;
    subcategoryId?: number | null;
    projectId?: number | null;
    articles: ArticleInput[];
    customValues: Record<string, string>;
  }) => api.post<BulkCreateResponse>('/user/tickets/bulk', payload).then(r => r.data),
  listMine: (page = 0, size = 20) =>
    api.get<TicketPage>('/user/tickets', { params: { page, size } }).then(r => r.data),
  listAll: (page = 0, size = 20) =>
    api.get<TicketPage>('/admin/tickets', { params: { page, size } }).then(r => r.data),
  getOne: (id: number) => api.get<Ticket>(`/tickets/${id}`).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/tickets/${id}`).then(() => undefined),
  /** User-facing delete for a ticket the caller owns — server enforces ownership. */
  removeMine: (id: number) => api.delete(`/user/tickets/${id}`).then(() => undefined),
  updateStatus: (id: number, status: TicketStatus) =>
    api.patch<Ticket>(`/admin/tickets/${id}/status`, { status }).then(r => r.data),
  stats: () => api.get<AdminStats>('/admin/stats').then(r => r.data),
  reports: () => api.get<ReportData>('/admin/reports').then(r => r.data),

  // Attachments (per-ticket file uploads)
  uploadDocument: (ticketId: number, name: string, file: File, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file);
    form.append('name', name);
    // Let the browser add the multipart boundary — manual Content-Type strips it.
    return api.post<TicketDocument>(`/tickets/${ticketId}/documents`, form, {
      signal,
    }).then(r => r.data);
  },
  documentDownloadUrl: (ticketId: number, docId: number) =>
    `${API_BASE}/tickets/${ticketId}/documents/${docId}`,
  removeDocument: (ticketId: number, docId: number) =>
    api.delete(`/tickets/${ticketId}/documents/${docId}`).then(() => undefined),

  /** Admin-only approve — thin wrapper around the status endpoint that always sets COMPLETED. */
  approve: (id: number) =>
    api.post<Ticket>(`/admin/tickets/${id}/approve`).then(r => r.data),
  approveMany: (ticketIds: number[]) =>
    api.post<{ approved: number; tickets: Ticket[] }>('/admin/tickets/approve-bulk', { ticketIds })
      .then(r => r.data),
};

// Project Folders — projects rendered as folders that group every ticket branched from them.
export const projectFoldersApi = {
  list: () => api.get<ProjectFolderSummary[]>('/project-folders').then(r => r.data),
  detail: (projectId: number) =>
    api.get<ProjectFolderDetail>(`/project-folders/${projectId}`).then(r => r.data),
  /**
   * Multi-file quick-upload. Files and titles travel positionally as parallel multipart
   * parts — the backend zips them by index. Blank/missing titles fall back to a
   * filename-derived title server-side, so the client can also just skip the titles
   * field entirely for a "no rename needed" case.
   *
   * <p>{@code departmentId} is optional: when the caller picked a specific department in
   * the modal, every ticket in this batch lands under it; when null, the server picks
   * the project's default department (auto-creating one if the project is brand-new).
   */
  quickUpload: (
    projectId: number,
    entries: Array<{ file: File; title: string }>,
    departmentId?: number | null,
    signal?: AbortSignal,
    onProgress?: (fraction: number) => void,
  ) => {
    const form = new FormData();
    for (const e of entries) form.append('files', e.file, e.file.name);
    for (const e of entries) form.append('titles', e.title);
    if (departmentId != null) form.append('departmentId', String(departmentId));
    return api.post<QuickUploadResult>(`/project-folders/${projectId}/quick-upload`, form, {
      signal,
      onUploadProgress: onProgress
        ? (e) => { if (e.total) onProgress(e.loaded / e.total); }
        : undefined,
    }).then(r => r.data);
  },
};

// Notifications — in-app feed powering the bell widget on the topbar.
export const notificationsApi = {
  list: () => api.get<NotificationFeed>('/notifications').then(r => r.data),
  markRead: (id: number) =>
    api.post<NotificationItem>(`/notifications/${id}/read`).then(r => r.data),
  markAllRead: () =>
    api.post<{ updated: number }>('/notifications/read-all').then(r => r.data),
};

// Dashboard
export const dashboardApi = {
  domains: () => api.get<DomainStats[]>('/admin/dashboard/domains').then(r => r.data),
  domain: (id: number) => api.get<DomainDetail>(`/admin/dashboard/domains/${id}`).then(r => r.data),
  subcategories: (departmentId: number) =>
    api.get<SubcategoryStats[]>('/admin/dashboard/subcategories', {
      params: { departmentId },
    }).then(r => r.data),
  users: (range: 'day' | 'week' | 'month' = 'week') =>
    api.get<LeaderboardResponse>('/admin/dashboard/users', { params: { range } }).then(r => r.data),
  user: (id: number, days = 30) =>
    api.get<UserActivity>(`/admin/dashboard/users/${id}`, { params: { days } }).then(r => r.data),
};

// User's own analytics dashboard (self-view)
export const myDashboardApi = {
  fetch: (days = 30) =>
    api.get<MyDashboard>('/user/dashboard/me', { params: { days } }).then(r => r.data),
};

// Unified document extraction: PDF, Word, Excel, PowerPoint, images, plain text
export const documentsApi = {
  extract: (file: File, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<ExtractedPdf>('/user/documents/extract', form, { signal }).then(r => r.data);
  },
};

// AI check
export const aiApi = {
  check: (content: string) =>
    api.post<AiCheckResponse>('/ai/check', { content }).then(r => r.data),
};

// Projects
export interface UpsertProjectPayload {
  name: string;
  subtitle?: string;
  /** Departments in this project. At least one required. */
  departmentIds: number[];
  memberIds?: number[];
  startDate?: string | null;
  endDate?: string | null;
  progress?: number;
  status?: ProjectStatus;
}

export const projectsApi = {
  /** Admin listing (full CRUD access). */
  list: () => api.get<Project[]>('/admin/projects').then(r => r.data),
  /** Read-only listing available to any authenticated user — used by the ticket submit form. */
  userList: (signal?: AbortSignal) =>
    api.get<Project[]>('/projects', { signal }).then(r => r.data),
  create: (payload: UpsertProjectPayload) =>
    api.post<Project>('/admin/projects', payload).then(r => r.data),
  update: (id: number, payload: UpsertProjectPayload) =>
    api.patch<Project>(`/admin/projects/${id}`, payload).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/projects/${id}`).then(() => undefined),
};

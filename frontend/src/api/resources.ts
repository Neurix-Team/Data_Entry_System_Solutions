import { api, API_BASE } from './client';
import type {
  AdminStats,
  AdminUser,
  AiCheckResponse,
  Announcement,
  ArticleInput,
  Assignment,
  AssignmentList,
  BulkCreateResponse,
  CustomField,
  Department,
  DomainDetail,
  DomainStats,
  ExtractedPdf,
  GoalsData,
  ImportResult,
  LeaderboardResponse,
  MyDashboard,
  NotificationFeed,
  NotificationItem,
  Project,
  ProjectFolderDetail,
  ProjectFolderSummary,
  ProjectStatus,
  PushKeyResponse,
  QualityRow,
  QuickUploadResult,
  RecycleBinItem,
  RecycleBinPage,
  ReportData,
  SearchHits,
  Subcategory,
  SubcategoryStats,
  Ticket,
  TicketDocument,
  TicketPage,
  TicketStatus,
  UpdateTicketPayload,
  UploadChunkAck,
  UploadCompleteResponse,
  UploadSession,
  UploadSessionCreateRequest,
  UserActivity,
  WeeklyReport,
  WorkloadData,
} from './types';

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

export const departmentsApi = {
  adminList: () => api.get<Department[]>('/admin/departments').then(r => r.data),
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

export const subcategoriesApi = {
  adminList: (departmentId?: number) =>
    api.get<Subcategory[]>('/admin/subcategories', {
      params: departmentId ? { departmentId } : {},
    }).then(r => r.data),
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
  removeMine: (id: number) => api.delete(`/user/tickets/${id}`).then(() => undefined),
  updateStatus: (id: number, status: TicketStatus) =>
    api.patch<Ticket>(`/admin/tickets/${id}/status`, { status }).then(r => r.data),
  updateAdmin: (id: number, payload: UpdateTicketPayload) =>
    api.patch<Ticket>(`/admin/tickets/${id}`, payload).then(r => r.data),
  stats: () => api.get<AdminStats>('/admin/stats').then(r => r.data),
  reports: () => api.get<ReportData>('/admin/reports').then(r => r.data),

  uploadDocument: (ticketId: number, name: string, file: File, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file);
    form.append('name', name);
    return api.post<TicketDocument>(`/tickets/${ticketId}/documents`, form, {
      signal,
    }).then(r => r.data);
  },
  documentDownloadUrl: (ticketId: number, docId: number) =>
    `${API_BASE}/tickets/${ticketId}/documents/${docId}`,
  removeDocument: (ticketId: number, docId: number) =>
    api.delete(`/tickets/${ticketId}/documents/${docId}`).then(() => undefined),

  approve: (id: number) =>
    api.post<Ticket>(`/admin/tickets/${id}/approve`).then(r => r.data),
  approveMany: (ticketIds: number[]) =>
    api.post<{ approved: number; tickets: Ticket[] }>('/admin/tickets/approve-bulk', { ticketIds })
      .then(r => r.data),
};

export const projectFoldersApi = {
  list: () => api.get<ProjectFolderSummary[]>('/project-folders').then(r => r.data),
  detail: (projectId: number) =>
    api.get<ProjectFolderDetail>(`/project-folders/${projectId}`).then(r => r.data),
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

export const notificationsApi = {
  list: () => api.get<NotificationFeed>('/notifications').then(r => r.data),
  markRead: (id: number) =>
    api.post<NotificationItem>(`/notifications/${id}/read`).then(r => r.data),
  markAllRead: () =>
    api.post<{ updated: number }>('/notifications/read-all').then(r => r.data),
};

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

export const myDashboardApi = {
  fetch: (days = 30) =>
    api.get<MyDashboard>('/user/dashboard/me', { params: { days } }).then(r => r.data),
};

export const documentsApi = {
  extract: (file: File, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<ExtractedPdf>('/user/documents/extract', form, { signal }).then(r => r.data);
  },
};

export const aiApi = {
  check: (content: string) =>
    api.post<AiCheckResponse>('/ai/check', { content }).then(r => r.data),
};

export interface UpsertProjectPayload {
  name: string;
  subtitle?: string;
  departmentIds: number[];
  memberIds?: number[];
  startDate?: string | null;
  endDate?: string | null;
  progress?: number;
  status?: ProjectStatus;
}

export const projectsApi = {
  list: () => api.get<Project[]>('/admin/projects').then(r => r.data),
  userList: (signal?: AbortSignal) =>
    api.get<Project[]>('/projects', { signal }).then(r => r.data),
  create: (payload: UpsertProjectPayload) =>
    api.post<Project>('/admin/projects', payload).then(r => r.data),
  update: (id: number, payload: UpsertProjectPayload) =>
    api.patch<Project>(`/admin/projects/${id}`, payload).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/projects/${id}`).then(() => undefined),
};

export const uploadsApi = {
  createSession: (req: UploadSessionCreateRequest, signal?: AbortSignal) =>
    api.post<UploadSession>('/uploads/sessions', req, { signal }).then(r => r.data),
  status: (id: string, signal?: AbortSignal) =>
    api.get<UploadSession>(`/uploads/sessions/${id}`, { signal }).then(r => r.data),
  putChunk: (
    id: string,
    index: number,
    chunk: Blob,
    onLoaded: (bytes: number) => void,
    signal?: AbortSignal,
  ) =>
    api.put<UploadChunkAck>(`/uploads/sessions/${id}/chunks/${index}`, chunk, {
      headers: { 'Content-Type': 'application/octet-stream' },
      signal,
      onUploadProgress: (e) => onLoaded(e.loaded),
    }).then(r => r.data),
  complete: (id: string, signal?: AbortSignal) =>
    api.post<UploadCompleteResponse>(`/uploads/sessions/${id}/complete`, undefined, { signal })
      .then(r => r.data),
  abort: (id: string) => api.delete(`/uploads/sessions/${id}`).then(() => undefined),
};


export interface AssignmentCreatePayload {
  assigneeId: number;
  title: string;
  description?: string | null;
  dueDate?: string | null;
}

export interface AssignmentUpdatePayload {
  assigneeId?: number;
  title?: string;
  description?: string | null;
  dueDate?: string | null;
  clearDueDate?: boolean;
}

export const assignmentsApi = {
  listAll: (signal?: AbortSignal) =>
    api.get<AssignmentList>('/admin/assignments', { signal }).then(r => r.data),
  create: (payload: AssignmentCreatePayload) =>
    api.post<Assignment>('/admin/assignments', payload).then(r => r.data),
  update: (id: number, payload: AssignmentUpdatePayload) =>
    api.patch<Assignment>(`/admin/assignments/${id}`, payload).then(r => r.data),
  reopen: (id: number) =>
    api.post<Assignment>(`/admin/assignments/${id}/reopen`).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/assignments/${id}`).then(() => undefined),

  listMine: (signal?: AbortSignal) =>
    api.get<AssignmentList>('/user/assignments', { signal }).then(r => r.data),
  markDone: (id: number) =>
    api.post<Assignment>(`/user/assignments/${id}/done`).then(r => r.data),
  reopenMine: (id: number) =>
    api.post<Assignment>(`/user/assignments/${id}/reopen`).then(r => r.data),
};

// ─── Recycle bin ─────────────────────────────────────────────────────────────
// Soft-deleted entries and projects: admins see the whole team's bin (plus
// projects), users get their own entries back — the personal safety net.

export const recycleBinApi = {
  adminList: (type: 'tickets' | 'projects', page = 0, size = 20, signal?: AbortSignal) =>
    api.get<RecycleBinPage>('/admin/recycle-bin', {
      params: { type, page, size },
      signal,
    }).then(r => r.data),
  myList: (page = 0, size = 20, signal?: AbortSignal) =>
    api.get<RecycleBinPage>('/user/recycle-bin', { params: { page, size }, signal }).then(r => r.data),
  restoreTicket: (id: number, admin = false) =>
    api.post<void>(`/${admin ? 'admin' : 'user'}/recycle-bin/tickets/${id}/restore`)
      .then(() => undefined),
  restoreProject: (id: number) =>
    api.post<void>(`/admin/recycle-bin/projects/${id}/restore`).then(() => undefined),
  purgeTicket: (id: number) =>
    api.delete<void>(`/admin/recycle-bin/tickets/${id}`).then(() => undefined),
  purgeProject: (id: number) =>
    api.delete<void>(`/admin/recycle-bin/projects/${id}`).then(() => undefined),
};

// ─── Browser push (Web Push) ─────────────────────────────────────────────────

export interface PushSubscribePayload {
  endpoint: string;
  p256dh: string;
  auth: string;
  userAgent?: string;
}

export const pushApi = {
  key: () => api.get<PushKeyResponse>('/notifications/push/key').then(r => r.data),
  subscribe: (payload: PushSubscribePayload) =>
    api.post<void>('/notifications/push/subscribe', payload).then(() => undefined),
  unsubscribe: (endpoint: string) =>
    api.post<void>('/notifications/push/unsubscribe', { endpoint }).then(() => undefined),
};

// ─── Global search (Ctrl+K) ──────────────────────────────────────────────────

export const searchApi = {
  query: (q: string, signal?: AbortSignal) =>
    api.get<SearchHits>('/search', { params: { q }, signal }).then(r => r.data),
};

// ─── Personal goals + streaks (B5) ────────────────────────────────────────────

export const goalsApi = {
  get: (signal?: AbortSignal) => api.get<GoalsData>('/user/goals', { signal }).then(r => r.data),
  update: (dailyGoal: number) =>
    api.patch<GoalsData>('/user/goals', { dailyGoal }).then(r => r.data),
};

// ─── Admin feature pack (C1-C5) ───────────────────────────────────────────────

export const adminFeaturesApi = {
  listAnnouncements: () => api.get<Announcement[]>('/admin/announcements').then(r => r.data),
  announce: (payload: { title: string; body: string; audience: 'ALL' | 'USERS' | 'ADMINS' }) =>
    api.post<Announcement>('/admin/announcements', payload).then(r => r.data),
  workload: () => api.get<WorkloadData>('/admin/workload').then(r => r.data),
  quality: () => api.get<{ rows: QualityRow[] }>('/admin/quality').then(r => r.data),
  weekly: () => api.get<WeeklyReport>('/admin/reports/weekly').then(r => r.data),
  dispatchWeekly: () =>
    api.post<{ notified: number }>('/admin/reports/weekly/dispatch').then(r => r.data),
  importCsv: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<ImportResult>('/admin/import/tickets', form).then(r => r.data);
  },
};

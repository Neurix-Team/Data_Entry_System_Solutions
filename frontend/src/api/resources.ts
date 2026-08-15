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
  Project,
  ProjectStatus,
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
  create: (name: string, active = true) =>
    api.post<Department>('/admin/departments', { name, active }).then(r => r.data),
  update: (id: number, payload: { name: string; active?: boolean }) =>
    api.patch<Department>(`/admin/departments/${id}`, payload).then(r => r.data),
  remove: (id: number) => api.delete(`/admin/departments/${id}`).then(() => undefined),
};

// Subcategories
export const subcategoriesApi = {
  adminList: (departmentId?: number) =>
    api.get<Subcategory[]>('/admin/subcategories', {
      params: departmentId ? { departmentId } : {},
    }).then(r => r.data),
  userList: (departmentId?: number, signal?: AbortSignal) =>
    api.get<Subcategory[]>('/subcategories', {
      params: departmentId ? { departmentId } : {},
      signal,
    }).then(r => r.data),
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
    departmentId: number;
    subcategoryId: number;
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

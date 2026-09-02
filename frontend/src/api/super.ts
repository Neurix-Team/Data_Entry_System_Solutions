import { api } from './client';

export interface TeamSummary {
  id: number;
  slug: string;
  name: string;
  nameEn?: string | null;
  nameAr?: string | null;
  description?: string | null;
  color?: string | null;
  active: boolean;
  createdAt: string;
  userCount: number;
  adminCount: number;
  projectCount: number;
  departmentCount: number;
  ticketCount: number;
  ticketsThisWeek: number;
}

export interface OverviewStats {
  totalTeams: number;
  activeTeams: number;
  totalUsers: number;
  totalAdmins: number;
  totalProjects: number;
  totalDepartments: number;
  totalTickets: number;
  ticketsToday: number;
  ticketsThisWeek: number;
  teams: TeamSummary[];
}

export interface CreateTeamRequest {
  slug: string;
  name: string;
  description?: string;
  color?: string;
}

export interface UpdateTeamRequest {
  name: string;
  description?: string;
  color?: string;
  active?: boolean;
}

export interface SuperAdminRow {
  id: number;
  username: string;
  displayName?: string | null;
  email?: string | null;
  active: boolean;
  createdAt: string;
}

export interface CreateSuperAdminRequest {
  username: string;
  password: string;
  displayName?: string;
  email?: string;
}

export interface EnterTeamResponse {
  teamId: number;
  teamSlug: string;
  teamName: string;
  header: string;
}

export interface TeamAdminRow {
  id: number;
  username: string;
  displayName?: string | null;
  email?: string | null;
  role: 'ADMIN' | 'USER';
  active: boolean;
  createdAt: string;
}

export interface CreateTeamAdminRequest {
  username: string;
  password: string;
  displayName?: string;
  email?: string;
}

/** One-shot: create an admin AND their fresh workspace team in a single call.
 *  Preferred over the two-step (createTeam → createTeamAdmin) flow because every
 *  admin now owns their own isolated team. */
export interface CreateAdminWithTeamRequest {
  username: string;
  password: string;
  displayName?: string;
  email?: string;
  teamName?: string;
  teamSlug?: string;
  teamDescription?: string;
  teamColor?: string;
}

export interface AdminWithTeamResponse {
  team: TeamSummary;
  admin: TeamAdminRow;
}

export interface PersonRef {
  id: number;
  username: string;
  displayName?: string | null;
}

export interface ProjectBreakdown {
  projectId: number;
  projectName: string;
  projectNameEn?: string | null;
  projectNameAr?: string | null;
  teamId: number | null;
  teamName: string | null;
  teamColor?: string | null;
  teamAdmins: PersonRef[];
  projectMembers: PersonRef[];
  ticketCount: number;
  ticketsThisWeek: number;
  status: string;
}

// ---------- Data explorer ----------

export interface ExplorerDocument {
  id: number;
  name: string;
  originalFilename: string;
  contentType: string | null;
  sizeBytes: number;
  uploadedAt: string;
  downloadUrl?: string | null;
}

export interface ExplorerFieldValue {
  fieldId: number | null;
  fieldName: string | null;
  value: string | null;
}

export interface ExplorerRow {
  id: number;
  teamId: number | null;
  teamName: string | null;
  projectId: number | null;
  projectName: string | null;
  departmentId: number | null;
  departmentName: string | null;
  subcategoryId: number | null;
  subcategoryName: string | null;
  submittedByUserId: number | null;
  submittedByUsername: string | null;
  submittedByDisplayName: string | null;
  submittedByEmail: string | null;
  submittedByPhone: string | null;
  submittedByRole: string | null;
  title: string | null;
  content: string | null;
  websiteName: string | null;
  websiteLink: string | null;
  status: string | null;
  submittedAt: string;
  documents: ExplorerDocument[];
  customFields: ExplorerFieldValue[];
}

export interface ExplorerPage {
  items: ExplorerRow[];
  nextCursor: number | null;
  hasMore: boolean;
  total: number;
}

export interface ExplorerNamed { id: number; name: string; }
export interface ExplorerFacets {
  teams: ExplorerNamed[];
  projects: ExplorerNamed[];
  users: ExplorerNamed[];
}

export interface ExplorerQuery {
  teamId?: number;
  projectId?: number;
  userId?: number;
  from?: string;
  to?: string;
  search?: string;
  cursor?: number;
  size?: number;
}

// ---------- Published dataset ----------

export interface DatasetRow extends Omit<ExplorerRow, 'id' | 'documents'> {
  id: number;
  sourceTicketId: number;
  attachments: ExplorerDocument[];
  publishedAt: string;
  refreshedAt: string;
}

export interface DatasetPage {
  items: DatasetRow[];
  nextCursor: number | null;
  hasMore: boolean;
  total: number;
}

export interface DatasetPublishResult {
  scanned: number;
  inserted: number;
  updated: number;
  unchanged: number;
  total: number;
}

export interface DatasetStats {
  publishedRecords: number;
  pendingRecords: number;
  totalRecords: number;
  publishedFiles: number;
  pendingFiles: number;
  totalFiles: number;
}

// ---------- API tokens ----------

export interface ApiTokenRow {
  id: number;
  name: string;
  prefix: string;
  createdByUserId: number | null;
  createdByUsername: string | null;
  createdAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
  lastUsedAt: string | null;
  active: boolean;
}

export interface CreateApiTokenRequest {
  name: string;
  /** Days until the token expires. 0 or null → never expires. */
  expiresInDays?: number | null;
}

export interface CreateApiTokenResponse {
  token: ApiTokenRow;
  /** Full plaintext token — shown once, never returned again. */
  plaintext: string;
}

export const superApi = {
  overview: () => api.get<OverviewStats>('/super/overview').then((r) => r.data),
  teams: () => api.get<TeamSummary[]>('/super/teams').then((r) => r.data),
  createTeam: (req: CreateTeamRequest) =>
    api.post<TeamSummary>('/super/teams', req).then((r) => r.data),
  updateTeam: (id: number, req: UpdateTeamRequest) =>
    api.put<TeamSummary>(`/super/teams/${id}`, req).then((r) => r.data),
  deleteTeam: (id: number) => api.delete<void>(`/super/teams/${id}`).then(() => undefined),
  enterTeam: (id: number) =>
    api.post<EnterTeamResponse>(`/super/teams/${id}/enter`).then((r) => r.data),
  admins: () => api.get<SuperAdminRow[]>('/super/admins').then((r) => r.data),
  createAdmin: (req: CreateSuperAdminRequest) =>
    api.post<SuperAdminRow>('/super/admins', req).then((r) => r.data),

  teamMembers: (teamId: number) =>
    api.get<TeamAdminRow[]>(`/super/teams/${teamId}/members`).then((r) => r.data),
  createTeamAdmin: (teamId: number, req: CreateTeamAdminRequest) =>
    api.post<TeamAdminRow>(`/super/teams/${teamId}/admins`, req).then((r) => r.data),
  /** Canonical admin onboarding: fresh team + admin in one call. */
  createAdminWithNewTeam: (req: CreateAdminWithTeamRequest) =>
    api.post<AdminWithTeamResponse>('/super/admins-with-team', req).then((r) => r.data),

  projectsBreakdown: () =>
    api.get<ProjectBreakdown[]>('/super/projects-breakdown').then((r) => r.data),

  // Data explorer
  explorerFacets: () =>
    api.get<ExplorerFacets>('/super/data/facets').then((r) => r.data),
  explorerTickets: (q: ExplorerQuery) =>
    api.get<ExplorerPage>('/super/data/tickets', { params: q }).then((r) => r.data),
  explorerTicket: (id: number) =>
    api.get<ExplorerRow>(`/super/data/tickets/${id}`).then((r) => r.data),

  dataset: (cursor?: number, size = 50) =>
    api.get<DatasetPage>('/super/dataset', { params: { cursor, size } }).then((r) => r.data),
  publishDataset: () =>
    api.post<DatasetPublishResult>('/super/dataset/publish').then((r) => r.data),
  datasetStats: () =>
    api.get<DatasetStats>('/super/dataset/stats').then((r) => r.data),

  // API tokens
  apiTokens: () =>
    api.get<ApiTokenRow[]>('/super/api-tokens').then((r) => r.data),
  createApiToken: (req: CreateApiTokenRequest) =>
    api.post<CreateApiTokenResponse>('/super/api-tokens', req).then((r) => r.data),
  revokeApiToken: (id: number) =>
    api.post<ApiTokenRow>(`/super/api-tokens/${id}/revoke`).then((r) => r.data),
  deleteApiToken: (id: number) =>
    api.delete<void>(`/super/api-tokens/${id}`).then(() => undefined),
};

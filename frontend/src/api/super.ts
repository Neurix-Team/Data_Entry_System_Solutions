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
};

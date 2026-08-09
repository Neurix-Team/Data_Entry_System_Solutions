export type Role = 'ADMIN' | 'USER';

export type FieldType = 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'URL' | 'EMAIL' | 'DATE' | 'SELECT';

export type TicketStatus = 'IN_PROGRESS' | 'REVIEW' | 'COMPLETED';

export interface User {
  id: number;
  username: string;
  displayName?: string | null;
  role: Role;
}

export interface AdminUser extends User {
  email?: string | null;
  phone?: string | null;
  active: boolean;
  createdAt: string;
}

export interface LoginResponse {
  token: string;
  expiresInMs: number;
  user: User;
}

export interface Department {
  id: number;
  name: string;
  active: boolean;
}

export interface Subcategory {
  id: number;
  departmentId: number;
  departmentName: string;
  name: string;
  active: boolean;
  ticketCount: number;
  fieldCount: number;
}

export interface CustomField {
  id: number;
  subcategoryId: number | null;
  subcategoryName?: string | null;
  departmentId?: number | null;
  departmentName?: string | null;
  fieldKey: string;
  label: string;
  type: FieldType;
  required: boolean;
  displayOrder: number;
  options?: string | null;
  placeholder?: string | null;
  active: boolean;
}

export interface CustomValue {
  fieldId: number;
  fieldKey: string;
  label: string;
  value: string;
}

export interface Ticket {
  id: number;
  departmentId: number;
  departmentName: string;
  subcategoryId?: number | null;
  subcategoryName?: string | null;
  title?: string | null;
  content: string;
  websiteName?: string | null;
  websiteLink?: string | null;
  status: TicketStatus;
  submittedAt: string;
  submittedById: number;
  submittedByUsername: string;
  submittedByDisplayName?: string | null;
  customValues: CustomValue[];
}

export interface ArticleInput {
  title: string;
  content: string;
  websiteName?: string;
  websiteLink?: string;
}

export interface BulkCreateResponse {
  created: number;
  tickets: Ticket[];
}

export interface TicketPage {
  items: Ticket[];
  totalItems: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface AiCheckResponse {
  original: string;
  corrected: string;
  notes: string[];
}

export interface AdminStats {
  totalTickets: number;
  totalDepartments: number;
  activeFields: number;
  totalUsers: number;
  inProgress: number;
  review: number;
  completed: number;
  completedToday: number;
}

export interface TopPerformer {
  userId: number;
  username: string;
  displayName: string;
  completed: number;
}

export interface ReportData {
  byDay: Record<string, number>;
  topPerformers: TopPerformer[];
  completedThisWeek: number;
}

export type ProjectStatus = 'ON_TRACK' | 'DELAYED' | 'COMPLETED';

export interface ProjectMember {
  id: number;
  username: string;
  displayName?: string | null;
}

export interface Project {
  id: number;
  name: string;
  subtitle?: string | null;
  departmentId: number;
  departmentName: string;
  members: ProjectMember[];
  startDate?: string | null;
  endDate?: string | null;
  daysLeft?: number | null;
  progress: number;
  status: ProjectStatus;
}

// --- dashboard ---

export interface DailyCount {
  date: string;
  count: number;
}

export interface StatusMap {
  IN_PROGRESS?: number;
  REVIEW?: number;
  COMPLETED?: number;
  [key: string]: number | undefined;
}

export interface DomainStats {
  departmentId: number;
  departmentName: string;
  totalTickets: number;
  subcategoryCount: number;
  activeAgents: number;
  byStatus: StatusMap;
  last7Days: DailyCount[];
}

export interface SubcategoryStats {
  subcategoryId: number;
  subcategoryName: string;
  departmentId: number;
  departmentName: string;
  totalTickets: number;
  byStatus: StatusMap;
  last7Days: DailyCount[];
}

export interface DomainDetail {
  departmentId: number;
  departmentName: string;
  totalTickets: number;
  activeAgents: number;
  byStatus: StatusMap;
  last30Days: DailyCount[];
  subcategories: SubcategoryStats[];
}

export interface AgentLeaderboardRow {
  userId: number;
  username: string;
  displayName: string;
  totalTickets: number;
  todayCount: number;
  last7DaysCount: number;
  avgPerDay: number;
}

export interface LeaderboardResponse {
  range: 'day' | 'week' | 'month' | string;
  activeAgents: number;
  rows: AgentLeaderboardRow[];
}

export interface UserBreakdownRow {
  id: number;
  name: string;
  count: number;
}

export interface UserActivity {
  userId: number;
  username: string;
  displayName: string;
  totalTickets: number;
  daysWindow: number;
  daily: DailyCount[];
  byDepartment: UserBreakdownRow[];
  bySubcategory: UserBreakdownRow[];
  byStatus: StatusMap;
}

export interface RecentTicket {
  id: number;
  title: string | null;
  departmentName: string;
  subcategoryName: string | null;
  status: TicketStatus;
  submittedAt: string;
}

export interface BestDay {
  date: string;
  count: number;
}

export interface MyDashboard {
  userId: number;
  username: string;
  displayName: string;
  totalAllTime: number;
  todayCount: number;
  thisWeekCount: number;
  thisMonthCount: number;
  currentStreak: number;
  longestStreak: number;
  averagePerDay: number;
  bestDay: BestDay;
  daysWindow: number;
  daily: DailyCount[];
  byStatus: StatusMap;
  byDepartment: UserBreakdownRow[];
  bySubcategory: UserBreakdownRow[];
  recent: RecentTicket[];
}

// --- pdf ---

export interface ExtractedPdf {
  filename: string;
  text: string;
  markdown: string;
  characters: number;
  truncated: boolean;
  extractedAt: string;
  warnings: string[];
}

export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'USER';

export type FieldType = 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'URL' | 'EMAIL' | 'DATE' | 'SELECT';

export type TicketStatus = 'IN_PROGRESS' | 'REVIEW' | 'COMPLETED';

export interface TeamRef {
  id: number;
  slug: string;
  name: string;
  nameEn?: string | null;
  nameAr?: string | null;
  color?: string | null;
}

export interface User {
  id: number;
  username: string;
  displayName?: string | null;
  displayNameEn?: string | null;
  displayNameAr?: string | null;
  role: Role;
  email?: string | null;
  phone?: string | null;
  avatarUpdatedAt?: string | null;
  createdAt?: string | null;
  team?: TeamRef | null;
  impersonating?: boolean;
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
  nameEn?: string | null;
  nameAr?: string | null;
  active: boolean;
  projectId?: number | null;
  projectName?: string | null;
}

export interface Subcategory {
  id: number;
  departmentId: number;
  departmentName: string;
  departmentNameEn?: string | null;
  departmentNameAr?: string | null;
  name: string;
  nameEn?: string | null;
  nameAr?: string | null;
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
  labelEn?: string | null;
  labelAr?: string | null;
  type: FieldType;
  required: boolean;
  displayOrder: number;
  options?: string | null;
  optionsEn?: string | null;
  optionsAr?: string | null;
  placeholder?: string | null;
  placeholderEn?: string | null;
  placeholderAr?: string | null;
  active: boolean;
}

export interface CustomValue {
  fieldId: number;
  fieldKey: string;
  label: string;
  labelEn?: string | null;
  labelAr?: string | null;
  value: string;
  valueEn?: string | null;
  valueAr?: string | null;
}

export interface Ticket {
  id: number;
  departmentId: number;
  departmentName: string;
  departmentNameEn?: string | null;
  departmentNameAr?: string | null;
  subcategoryId?: number | null;
  subcategoryName?: string | null;
  subcategoryNameEn?: string | null;
  subcategoryNameAr?: string | null;
  projectId?: number | null;
  projectName?: string | null;
  title?: string | null;
  titleEn?: string | null;
  titleAr?: string | null;
  content: string;
  contentEn?: string | null;
  contentAr?: string | null;
  websiteName?: string | null;
  websiteNameEn?: string | null;
  websiteNameAr?: string | null;
  websiteLink?: string | null;
  status: TicketStatus;
  submittedAt: string;
  submittedById: number;
  submittedByUsername: string;
  submittedByDisplayName?: string | null;
  submittedByDisplayNameEn?: string | null;
  submittedByDisplayNameAr?: string | null;
  customValues: CustomValue[];
  resources?: TicketResource[];
  documents?: TicketDocument[];
}

export interface ResourceInput {
  name?: string;
  url: string;
}

export interface ExtractedImageRefInput {
  name: string;
  extractionId: string;
  filename: string;
}

export interface ArticleInput {
  title: string;
  content: string;
  websiteName?: string;
  websiteLink?: string;
  resources?: ResourceInput[];
  extractedImages?: ExtractedImageRefInput[];
}

export interface UpdateTicketPayload {
  title: string;
  content: string;
  websiteName?: string;
  websiteLink?: string;
  resources: ResourceInput[];
}

export interface TicketResource {
  id: number;
  name: string | null;
  nameEn?: string | null;
  nameAr?: string | null;
  url: string;
  displayOrder: number;
}

export interface TicketDocument {
  id: number;
  name: string;
  originalFilename: string;
  contentType: string | null;
  sizeBytes: number;
  uploadedAt: string;
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
  displayNameEn?: string | null;
  displayNameAr?: string | null;
}

export interface ProjectDepartment {
  id: number;
  name: string;
  nameEn?: string | null;
  nameAr?: string | null;
}

export interface Project {
  id: number;
  name: string;
  nameEn?: string | null;
  nameAr?: string | null;
  subtitle?: string | null;
  subtitleEn?: string | null;
  subtitleAr?: string | null;
  departmentId: number | null;
  departmentName: string | null;
  departmentNameEn?: string | null;
  departmentNameAr?: string | null;
  departments: ProjectDepartment[];
  members: ProjectMember[];
  startDate?: string | null;
  endDate?: string | null;
  daysLeft?: number | null;
  progress: number;
  status: ProjectStatus;
}


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


export interface ExtractedImage {
  filename: string;
  url: string;
  contentType: string;
  sizeBytes: number;
  page: number;
  width: number;
  height: number;
}


export interface ProjectFolderSummary {
  projectId: number;
  projectName: string;
  projectNameEn?: string | null;
  projectNameAr?: string | null;
  subtitle?: string | null;
  subtitleEn?: string | null;
  subtitleAr?: string | null;
  total: number;
  pending: number;
  approved: number;
  status: ProjectStatus;
}

export interface ProjectFolderDetail {
  projectId: number;
  projectName: string;
  projectNameEn?: string | null;
  projectNameAr?: string | null;
  tickets: Ticket[];
}

export interface QuickUploadFailure {
  filename: string;
  reason: string;
}

export interface QuickUploadResult {
  created: number;
  failed: number;
  tickets: Ticket[];
  failures: QuickUploadFailure[];
}


export interface NotificationItem {
  id: number;
  type: string;
  message: string;
  refType: string | null;
  refId: number | null;
  projectId: number | null;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationFeed {
  items: NotificationItem[];
  unread: number;
}

export interface ExtractedPdf {
  filename: string;
  text: string;
  markdown: string;
  characters: number;
  truncated: boolean;
  extractedAt: string;
  warnings: string[];
  extractionId: string | null;
  images: ExtractedImage[];
}


export type UploadTarget = 'QUICK_UPLOAD' | 'TICKET_DOCUMENT';

export interface UploadSessionCreateRequest {
  filename: string;
  size: number;
  contentType?: string | null;
  target: UploadTarget;
  projectId?: number | null;
  departmentId?: number | null;
  ticketId?: number | null;
  title?: string | null;
}

export interface UploadSession {
  id: string;
  filename: string;
  size: number;
  chunkBytes: number;
  totalChunks: number;
  received: number[];
  expiresAt: string;
}

export interface UploadChunkAck {
  index: number;
  bytes: number;
}

export interface UploadCompleteResponse {
  target: UploadTarget;
  ticket: Ticket | null;
  document: TicketDocument | null;
}

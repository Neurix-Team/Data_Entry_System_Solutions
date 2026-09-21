import { api, API_BASE } from './client';

export interface ChatAttachmentItem {
  id: number;
  filename: string;
  contentType: string | null;
  sizeBytes: number;
  kind: 'IMAGE' | 'PDF' | 'DOC';
}

export interface ChatMessageItem {
  /** Null for the message id of a system line the server wrote itself (group only). */
  id: number | null;
  /** Set for a 1:1 message; null for a group message (see groupId). */
  conversationId: number | null;
  /** Null for a group system line ("X added Y") — nobody sent it. */
  senderId: number | null;
  senderName: string | null;
  senderRole: string | null;
  body: string | null;
  createdAt: string;
  readAt: string | null;
  attachments: ChatAttachmentItem[];
  /** Set for a group message; null for a 1:1 message. */
  groupId?: number | null;
  /** TEXT | SYSTEM — SYSTEM renders centered and muted, with no sender. */
  kind?: 'TEXT' | 'SYSTEM' | null;
  groupName?: string | null;
}

export interface ChatConversationItem {
  id: number;
  otherUserId: number;
  otherUsername: string;
  otherName: string;
  otherNameEn?: string | null;
  otherNameAr?: string | null;
  otherRole: string;
  otherTeam?: string | null;
  otherAvatarUpdatedAt?: string | null;
  lastMessagePreview?: string | null;
  lastMessageAt?: string | null;
  unreadCount: number;
}

export interface ChatConversationList {
  items: ChatConversationItem[];
  totalUnread: number;
}

export interface ChatContact {
  id: number;
  username: string;
  displayName: string;
  displayNameEn?: string | null;
  displayNameAr?: string | null;
  role: 'SUPER_ADMIN' | 'ADMIN' | 'USER';
  team?: string | null;
  avatarUpdatedAt?: string | null;
  conversationId?: number | null;
}

export interface ChatContactList {
  items: ChatContact[];
}

export interface ChatMessagesPage {
  conversationId: number;
  messages: ChatMessageItem[];
}

export interface ChatWsOut {
  type: 'MESSAGE' | 'READ' | 'TYPING' | 'ERROR' | 'CONNECTED' | 'GROUP_UPDATED' | 'GROUP_REMOVED';
  conversationId?: number | null;
  message?: ChatMessageItem | null;
  readerId?: number | null;
  fromUserId?: number | null;
  error?: string | null;
  clientMsgId?: string | null;
  /** Set on a group frame; null on a 1:1 or connection-level one. */
  groupId?: number | null;
}

// ─── Groups ──────────────────────────────────────────────────────────────────

export interface ChatGroupItem {
  id: number;
  name: string;
  memberCount: number;
  /** True when the caller is an admin of this group. */
  isAdmin: boolean;
  lastMessagePreview?: string | null;
  lastMessageAt?: string | null;
  unreadCount: number;
}

export interface ChatGroupMember {
  userId: number;
  username: string;
  displayName: string;
  displayNameEn?: string | null;
  displayNameAr?: string | null;
  role: 'ADMIN' | 'MEMBER';
  avatarUpdatedAt?: string | null;
  joinedAt: string;
}

export interface ChatGroupDetail {
  id: number;
  name: string;
  isAdmin: boolean;
  members: ChatGroupMember[];
}

export const chatApi = {
  async conversations(): Promise<ChatConversationList> {
    const { data } = await api.get<ChatConversationList>('/chat/conversations');
    return data;
  },

  async start(userId: number): Promise<ChatConversationItem> {
    const { data } = await api.post<ChatConversationItem>('/chat/conversations', { userId });
    return data;
  },

  async messages(conversationId: number, afterId?: number): Promise<ChatMessagesPage> {
    const params = afterId != null ? { afterId } : undefined;
    const { data } = await api.get<ChatMessagesPage>(`/chat/conversations/${conversationId}/messages`, { params });
    return data;
  },

  async sendText(conversationId: number, body: string): Promise<ChatMessageItem> {
    const { data } = await api.post<ChatMessageItem>(`/chat/conversations/${conversationId}/messages`, { body });
    return data;
  },

  async sendFiles(conversationId: number, files: File[], body?: string): Promise<ChatMessageItem> {
    const fd = new FormData();
    if (body && body.trim()) fd.append('body', body.trim());
    for (const f of files) fd.append('files', f);
    const { data } = await api.post<ChatMessageItem>(
      `/chat/conversations/${conversationId}/attachments`, fd,
      { headers: { 'Content-Type': 'multipart/form-data' } });
    return data;
  },

  async markRead(conversationId: number): Promise<void> {
    await api.post(`/chat/conversations/${conversationId}/read`);
  },

  async contacts(): Promise<ChatContactList> {
    const { data } = await api.get<ChatContactList>('/chat/contacts');
    return data;
  },
};

export const chatGroupApi = {
  async list(): Promise<ChatGroupItem[]> {
    const { data } = await api.get<{ items: ChatGroupItem[] }>('/chat/groups');
    return data.items;
  },

  async create(name: string, memberIds: number[]): Promise<ChatGroupItem> {
    const { data } = await api.post<ChatGroupItem>('/chat/groups', { name, memberIds });
    return data;
  },

  async detail(groupId: number): Promise<ChatGroupDetail> {
    const { data } = await api.get<ChatGroupDetail>(`/chat/groups/${groupId}`);
    return data;
  },

  async rename(groupId: number, name: string): Promise<void> {
    await api.patch(`/chat/groups/${groupId}`, { name });
  },

  async addMembers(groupId: number, userIds: number[]): Promise<void> {
    await api.post(`/chat/groups/${groupId}/members`, { userIds });
  },

  /** A member passing their own id is how "leave the group" works. */
  async removeMember(groupId: number, userId: number): Promise<void> {
    await api.delete(`/chat/groups/${groupId}/members/${userId}`);
  },

  async setAdmin(groupId: number, userId: number, admin: boolean): Promise<void> {
    if (admin) await api.post(`/chat/groups/${groupId}/members/${userId}/admin`);
    else await api.delete(`/chat/groups/${groupId}/members/${userId}/admin`);
  },

  async messages(groupId: number, afterId?: number): Promise<{ groupId: number; messages: ChatMessageItem[] }> {
    const params = afterId != null ? { afterId } : undefined;
    const { data } = await api.get<{ groupId: number; messages: ChatMessageItem[] }>(
      `/chat/groups/${groupId}/messages`, { params });
    return data;
  },

  async sendText(groupId: number, body: string): Promise<ChatMessageItem> {
    const { data } = await api.post<ChatMessageItem>(`/chat/groups/${groupId}/messages`, { body });
    return data;
  },

  async sendFiles(groupId: number, files: File[], body?: string): Promise<ChatMessageItem> {
    const fd = new FormData();
    if (body && body.trim()) fd.append('body', body.trim());
    for (const f of files) fd.append('files', f);
    const { data } = await api.post<ChatMessageItem>(
      `/chat/groups/${groupId}/attachments`, fd,
      { headers: { 'Content-Type': 'multipart/form-data' } });
    return data;
  },

  async markRead(groupId: number): Promise<void> {
    await api.post(`/chat/groups/${groupId}/read`);
  },
};

export function groupAttachmentUrl(attachmentId: number): string {
  return `${API_BASE}/chat/groups/attachments/${attachmentId}`;
}

/** Absolute WS URL for /ws/chat with optional JWT auth (cookie fallback). */
export function chatSocketUrl(token?: string | null): string {
  const base = API_BASE.replace(/\/api\/?$/, '');
  const wsProto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const auth = token ? `?token=${encodeURIComponent(token)}` : '';
  return `${wsProto}://${window.location.host}${base}/ws/chat${auth}`;
}

export function attachmentUrl(attachmentId: number): string {
  return `${API_BASE}/chat/attachments/${attachmentId}`;
}

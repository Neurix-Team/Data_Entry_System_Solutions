import { api, API_BASE } from './client';

export interface ChatAttachmentItem {
  id: number;
  filename: string;
  contentType: string | null;
  sizeBytes: number;
  kind: 'IMAGE' | 'PDF' | 'DOC';
}

export interface ChatMessageItem {
  id: number;
  conversationId: number;
  senderId: number;
  senderName: string;
  senderRole: string;
  body: string | null;
  createdAt: string;
  readAt: string | null;
  attachments: ChatAttachmentItem[];
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
  type: 'MESSAGE' | 'READ' | 'TYPING' | 'ERROR' | 'CONNECTED';
  conversationId?: number | null;
  message?: ChatMessageItem | null;
  readerId?: number | null;
  fromUserId?: number | null;
  error?: string | null;
  clientMsgId?: string | null;
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

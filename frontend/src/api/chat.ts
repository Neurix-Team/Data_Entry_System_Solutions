import { api } from './client';

export interface ChatAction {
  type: 'navigate';
  path: string;
  label: string;
}

export interface ChatResponse {
  reply: string;
  actions?: ChatAction[];
}

export async function sendChatMessage(
  message: string,
  currentPath: string,
  lang: 'ar' | 'en'
): Promise<ChatResponse> {
  const { data } = await api.post<ChatResponse>('/chat', {
    message,
    currentPath,
    lang,
  });
  return data;
}

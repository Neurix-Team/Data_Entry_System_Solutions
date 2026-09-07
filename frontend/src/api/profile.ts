import { api } from './client';

export interface AvatarInfo {
  userId: number;
  updatedAt: string;
}

export function avatarUrl(userId: number, updatedAt?: string | null): string | null {
  if (!updatedAt) return null;
  const v = encodeURIComponent(updatedAt);
  return `/api/users/${userId}/avatar?v=${v}`;
}

export const profileApi = {
  async upload(file: File): Promise<AvatarInfo> {
    const fd = new FormData();
    fd.append('file', file);
    const { data } = await api.post<AvatarInfo>('/user/me/avatar', fd);
    return data;
  },

  async remove(): Promise<void> {
    await api.delete('/user/me/avatar');
  },
};

import { api } from './client';
import type { LoginResponse, User } from './types';

export interface UpdateProfileRequest {
  displayName?: string;
  email?: string | null;
  phone?: string | null;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export const authApi = {
  login: (username: string, password: string) =>
    api.post<LoginResponse>('/auth/login', { username, password }).then(r => r.data),

  me: (signal?: AbortSignal) => api.get<User>('/auth/me', { signal }).then(r => r.data),

  logout: () => api.post('/auth/logout').then(() => undefined),

  updateMe: (req: UpdateProfileRequest) =>
    api.patch<User>('/auth/me', req).then(r => r.data),

  changePassword: (req: ChangePasswordRequest) =>
    api.post('/auth/me/password', req).then(() => undefined),
};

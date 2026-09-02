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

  /** Clears the httpOnly auth cookie on the server. */
  logout: () => api.post('/auth/logout').then(() => undefined),

  /** Update the caller's own display name / email / phone. Returns the fresh User. */
  updateMe: (req: UpdateProfileRequest) =>
    api.patch<User>('/auth/me', req).then(r => r.data),

  /** Change the caller's own password. Server verifies the current password. */
  changePassword: (req: ChangePasswordRequest) =>
    api.post('/auth/me/password', req).then(() => undefined),
};

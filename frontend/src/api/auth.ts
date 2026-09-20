import { api } from './client';
import type { LoginResponse, MfaEnrollResponse, MfaRecoveryCodes, MfaStatus, User } from './types';

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

/**
 * Second-factor (TOTP) calls. During a sign-in challenge the pending ticket rides along
 * as the Bearer credential (AuthContext drops it into the token store); the backend only
 * honours it on these routes.
 */
export const mfaApi = {
  verify: (ticket: string, code: string) =>
    api.post<LoginResponse>('/auth/mfa/verify', { ticket, code }).then(r => r.data),

  status: () => api.get<MfaStatus>('/auth/mfa/status').then(r => r.data),

  enroll: (password: string) =>
    api.post<MfaEnrollResponse>('/auth/mfa/enroll', { password }).then(r => r.data),

  confirmEnrollment: (code: string) =>
    api.post<MfaRecoveryCodes>('/auth/mfa/enroll/confirm', { code }).then(r => r.data),

  disable: (code: string) =>
    api.post<MfaStatus>('/auth/mfa/disable', { code }).then(r => r.data),

  regenerateRecoveryCodes: (password: string) =>
    api.post<MfaRecoveryCodes>('/auth/mfa/recovery-codes', { password }).then(r => r.data),
};

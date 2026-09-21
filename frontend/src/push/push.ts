import { pushApi } from '../api/resources';

/**
 * Browser push (Web Push) client. Everything is opt-in from a user gesture —
 * browsers only show the permission prompt on one — and degrades silently on
 * browsers/contexts without service workers (Safari iOS older versions, private
 * windows, insecure origins).
 */

export interface PushStatus {
  supported: boolean;
  permission: NotificationPermission | 'unsupported';
  subscribed: boolean;
  /** False when the backend has no VAPID keys configured — hide the toggle then. */
  serverEnabled: boolean;
}

function b64UrlToBytes(b64url: string): Uint8Array {
  const padding = '='.repeat((4 - (b64url.length % 4)) % 4);
  const b64 = (b64url + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(b64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function bytesToB64Url(bytes: Uint8Array): string {
  const raw = String.fromCharCode(...bytes);
  return btoa(raw).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function pushSupported(): boolean {
  return typeof window !== 'undefined'
    && 'serviceWorker' in navigator
    && 'PushManager' in window
    && 'Notification' in window;
}

async function currentSubscription(): Promise<PushSubscription | null> {
  if (!pushSupported()) return null;
  try {
    const reg = await navigator.serviceWorker.getRegistration();
    return reg ? await reg.pushManager.getSubscription() : null;
  } catch {
    return null;
  }
}

export async function getPushStatus(): Promise<PushStatus> {
  if (!pushSupported()) {
    return { supported: false, permission: 'unsupported', subscribed: false, serverEnabled: false };
  }
  let serverEnabled = false;
  try {
    serverEnabled = (await pushApi.key()).enabled;
  } catch {
    serverEnabled = false;
  }
  const sub = await currentSubscription();
  return {
    supported: true,
    permission: Notification.permission,
    subscribed: serverEnabled && sub != null,
    serverEnabled,
  };
}

/** Opt this browser in. Call from a click handler — that is what browsers require. */
export async function enablePush(): Promise<PushStatus> {
  if (!pushSupported()) throw new Error('unsupported');
  const key = await pushApi.key();
  if (!key.enabled || !key.publicKey) throw new Error('disabled');
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error(permission);
  await navigator.serviceWorker.register('/sw.js');
  const reg = await navigator.serviceWorker.ready;
  const sub = (await reg.pushManager.getSubscription())
    ?? (await reg.pushManager.subscribe({
      userVisibleOnly: true,
      // Fresh exact-sized buffer, so byteOffset is 0 and byteLength matches.
      applicationServerKey: b64UrlToBytes(key.publicKey).buffer as ArrayBuffer,
    }));
  const json = sub.toJSON() as { endpoint?: string; keys?: Record<string, string> };
  const endpoint = json.endpoint;
  const p256dh = json.keys?.p256dh;
  const auth = json.keys?.auth;
  if (!endpoint || !p256dh || !auth) throw new Error('bad-subscription');
  await pushApi.subscribe({
    endpoint,
    p256dh: p256dh.includes('+') || p256dh.includes('/') ? bytesToB64Url(b64UrlToBytes(p256dh)) : p256dh,
    auth: auth.includes('+') || auth.includes('/') ? bytesToB64Url(b64UrlToBytes(auth)) : auth,
    userAgent: navigator.userAgent,
  });
  return getPushStatus();
}

/** Opt out everywhere: server row and the browser subscription itself. */
export async function disablePush(): Promise<PushStatus> {
  const sub = await currentSubscription();
  if (sub) {
    try { await pushApi.unsubscribe(sub.endpoint); } catch { /* server row may already be gone */ }
    try { await sub.unsubscribe(); } catch { /* ignore */ }
  }
  return getPushStatus();
}
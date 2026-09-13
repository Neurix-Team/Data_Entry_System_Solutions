/** Untrusted stored links may only navigate to absolute HTTP(S) URLs. */
export function safeExternalUrl(value: string | null | undefined): string | undefined {
  if (!value || /[\\\u0000-\u0020]/.test(value)) return undefined;
  try {
    const url = new URL(value);
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) return undefined;
    return url.href;
  } catch { return undefined; }
}

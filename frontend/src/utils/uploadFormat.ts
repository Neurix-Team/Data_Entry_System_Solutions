/** Human-readable byte counts, throughput and durations for the upload meters. */

export function formatBytes(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '0 B';
  if (n < 1024) return `${Math.round(n)} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function formatSpeed(bytesPerSecond: number, lang: string): string {
  return `${formatBytes(bytesPerSecond)}${lang === 'ar' ? '/ث' : '/s'}`;
}

export function formatEta(seconds: number, lang: string): string {
  const s = Math.max(0, Math.round(seconds));
  const m = Math.floor(s / 60);
  const rem = s % 60;
  if (lang === 'ar') {
    if (s < 1) return 'لحظات';
    if (s < 60) return `${s} ث متبقية`;
    return rem > 0 ? `${m} د ${rem} ث متبقية` : `${m} د متبقية`;
  }
  if (s < 1) return 'almost done';
  if (s < 60) return `${s}s left`;
  return rem > 0 ? `${m}m ${rem}s left` : `${m}m left`;
}

export function formatDuration(ms: number, lang: string): string {
  const s = Math.max(0, ms) / 1000;
  if (s < 60) return lang === 'ar' ? `${s.toFixed(1)} ث` : `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  const rem = Math.round(s % 60);
  return lang === 'ar' ? `${m} د ${rem} ث` : `${m}m ${rem}s`;
}

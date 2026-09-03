import type { UploadProgress } from '../api/chunkedUpload';
import { formatBytes, formatEta, formatSpeed } from '../utils/uploadFormat';
import { ProgressRing, type ProgressRingState } from './ProgressRing';

interface Props {
  lang: string;
  title: string;
  subtitle?: string;
  progress: UploadProgress | null;
  state: ProgressRingState;
  size?: number;
  /** Closing line once the work is over, e.g. "Done in 4.2 s". */
  note?: string;
}

/**
 * The big upload meter: a progress ring beside a title, the bytes moved so far, the
 * current speed and the time left. Used above the file list in the quick-upload modal
 * and above the submit button on the New Entry form.
 */
export function UploadHud({ lang, title, subtitle, progress, state, size = 64, note }: Props) {
  const ar = lang === 'ar';
  const meta: string[] = [];
  if (progress && state === 'uploading') {
    meta.push(`${formatBytes(progress.loaded)} / ${formatBytes(progress.total)}`);
    if (progress.bytesPerSecond > 0) meta.push(formatSpeed(progress.bytesPerSecond, lang));
    if (progress.etaSeconds != null) meta.push(formatEta(progress.etaSeconds, lang));
  } else if (state === 'finalizing') {
    meta.push(ar ? 'وصل كله — جارٍ المعالجة على السيرفر…' : 'All bytes landed — finalizing on the server…');
  }

  return (
    <div className={`upload-hud is-${state}`} role="status" aria-live="polite">
      <ProgressRing
        value={progress?.fraction ?? 0}
        size={size}
        stroke={Math.max(4, Math.round(size / 11))}
        state={state}
        ariaLabel={title}
      />
      <div className="upload-hud-body">
        <div className="upload-hud-title">{title}</div>
        {subtitle && <div className="upload-hud-sub" dir="auto">{subtitle}</div>}
        {meta.length > 0 && (
          <div className="upload-hud-meta">
            {meta.map((m, i) => <span key={i}>{m}</span>)}
          </div>
        )}
        {note && <div className="upload-hud-note">{note}</div>}
      </div>
    </div>
  );
}

import axios from 'axios';
import { uploadsApi } from './resources';
import type { UploadCompleteResponse, UploadSessionCreateRequest } from './types';

/**
 * Chunked, parallel, resumable file upload.
 *
 * A scanned book is hundreds of megabytes. Pushed as one multipart request it crawls
 * through a single TCP stream and two reverse proxies, and any hiccup throws the whole
 * transfer away. Here the file is cut into fixed-size chunks (the server picks the size),
 * several chunks travel at once so the pipe stays full even on high-latency links, a
 * failed chunk is retried on its own, and the server assembles nothing — each chunk is
 * written straight into its slot. Progress is real: acknowledged bytes plus what is on
 * the wire right now, with a smoothed throughput and time-left estimate.
 */

export type UploadPhase = 'starting' | 'uploading' | 'finalizing' | 'done';

export interface UploadProgress {
  phase: UploadPhase;
  /** Bytes the server has acknowledged plus bytes currently on the wire. */
  loaded: number;
  total: number;
  /** 0..1 */
  fraction: number;
  /** Smoothed throughput in bytes per second; 0 until the meter has enough samples. */
  bytesPerSecond: number;
  /** Seconds until the last byte lands, or null while the rate is still unknown. */
  etaSeconds: number | null;
}

export type ChunkedUploadTarget =
  | { kind: 'QUICK_UPLOAD'; projectId: number; departmentId?: number | null; title: string }
  | { kind: 'TICKET_DOCUMENT'; ticketId: number; name: string };

export interface ChunkedUploadOptions {
  file: File;
  target: ChunkedUploadTarget;
  onProgress?: (p: UploadProgress) => void;
  signal?: AbortSignal;
  /** Chunk requests in flight at once for this file. */
  parallel?: number;
}

/** Thrown when the backend predates the session endpoints — callers fall back to multipart. */
export class ChunkedUploadUnsupportedError extends Error {
  constructor() {
    super('Chunked upload is not available on this server');
    this.name = 'ChunkedUploadUnsupportedError';
  }
}

/** Chunk requests per file. Four streams fill a fast link; a modal uploading two files
 *  at once therefore holds eight connections, just past the browser's HTTP/1.1 cap of six,
 *  so the queue always has something ready the moment a socket frees up. */
export const DEFAULT_CHUNK_PARALLELISM = 4;

const MAX_ATTEMPTS = 4;
const RETRY_DELAYS_MS = [400, 1200, 3000];
const COMPLETE_ATTEMPTS = 3;
const PROGRESS_INTERVAL_MS = 80;
const SPEED_WINDOW_MS = 4000;

/**
 * Sliding-window throughput meter. Compares the current byte count against the sample
 * from ~4 s ago, then eases toward that rate so the number on screen doesn't jitter
 * with every TCP burst. Shared by the per-file and the whole-batch displays.
 */
export class SpeedMeter {
  private samples: Array<{ t: number; loaded: number }> = [];
  private smoothed = 0;

  /** Feed the latest cumulative byte count; returns the smoothed bytes/second. */
  push(loaded: number, now: number = performance.now()): number {
    this.samples.push({ t: now, loaded });
    while (this.samples.length > 2 && now - this.samples[0].t > SPEED_WINDOW_MS) {
      this.samples.shift();
    }
    const first = this.samples[0];
    const dt = (now - first.t) / 1000;
    if (dt < 0.25) return this.smoothed;
    const instant = Math.max(0, (loaded - first.loaded) / dt);
    this.smoothed = this.smoothed === 0 ? instant : this.smoothed * 0.6 + instant * 0.4;
    return this.smoothed;
  }

  get bytesPerSecond(): number {
    return this.smoothed;
  }
}

export function etaFor(loaded: number, total: number, bytesPerSecond: number): number | null {
  if (bytesPerSecond <= 0) return null;
  return Math.max(0, total - loaded) / bytesPerSecond;
}

/** Progress reporter with rate limiting, so a 6-stream upload doesn't re-render at 300 Hz. */
function makeEmitter(total: number, onProgress?: (p: UploadProgress) => void) {
  const meter = new SpeedMeter();
  let phase: UploadPhase = 'starting';
  let loaded = 0;
  let lastEmit = 0;

  function emit(force: boolean) {
    if (!onProgress) return;
    const now = performance.now();
    if (!force && now - lastEmit < PROGRESS_INTERVAL_MS) return;
    lastEmit = now;
    const speed = phase === 'uploading' ? meter.push(loaded, now) : meter.bytesPerSecond;
    onProgress({
      phase,
      loaded,
      total,
      fraction: total > 0 ? Math.min(1, loaded / total) : 1,
      bytesPerSecond: speed,
      etaSeconds: phase === 'uploading' ? etaFor(loaded, total, speed) : phase === 'done' ? 0 : null,
    });
  }

  return {
    setLoaded(n: number) {
      loaded = Math.min(total, Math.max(loaded, n));
      emit(loaded >= total);
    },
    /** Same as setLoaded but allowed to go backwards — a chunk being retried from zero. */
    resetLoaded(n: number) {
      loaded = Math.min(total, Math.max(0, n));
      emit(true);
    },
    setPhase(p: UploadPhase) {
      phase = p;
      if (p === 'done' || p === 'finalizing') loaded = total;
      emit(true);
    },
  };
}

function isRetryable(err: unknown): boolean {
  if (axios.isCancel(err)) return false;
  if (!axios.isAxiosError(err)) return false;
  const status = err.response?.status;
  // No response at all: the socket dropped or the proxy timed out — worth another go.
  if (status == null) return true;
  return status === 408 || status === 425 || status === 429 || status >= 500;
}

function isEndpointMissing(err: unknown): boolean {
  if (!axios.isAxiosError(err)) return false;
  const status = err.response?.status;
  if (status === 405) return true;
  if (status !== 404) return false;
  // Our 404s carry a reason; a bare "Resource not found" is the router, not the app.
  const message = (err.response?.data as { message?: string } | undefined)?.message;
  return message === 'Resource not found';
}

function cancelledError(): Error {
  const e = new Error('Upload cancelled');
  e.name = 'CanceledError';
  (e as Error & { code?: string }).code = 'ERR_CANCELED';
  return e;
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(cancelledError()); return; }
    const id = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve(); }, ms);
    function onAbort() { clearTimeout(id); reject(cancelledError()); }
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

function toCreateRequest(file: File, target: ChunkedUploadTarget): UploadSessionCreateRequest {
  const base = {
    filename: file.name,
    size: file.size,
    contentType: file.type || null,
  };
  if (target.kind === 'QUICK_UPLOAD') {
    return {
      ...base,
      target: 'QUICK_UPLOAD',
      projectId: target.projectId,
      departmentId: target.departmentId ?? null,
      title: target.title || null,
    };
  }
  return {
    ...base,
    target: 'TICKET_DOCUMENT',
    ticketId: target.ticketId,
    title: target.name || null,
  };
}

/**
 * Upload one file. Resolves with the server's finalize response (the new ticket for a
 * quick upload, the document row for a ticket attachment). Rejects with the axios error
 * of the first non-retryable failure, a cancellation error if {@code signal} fired, or
 * {@link ChunkedUploadUnsupportedError} when the backend has no session endpoints.
 */
export async function uploadFileChunked(opts: ChunkedUploadOptions): Promise<UploadCompleteResponse> {
  const { file, target, signal } = opts;
  const parallel = Math.max(1, opts.parallel ?? DEFAULT_CHUNK_PARALLELISM);
  const emitter = makeEmitter(file.size, opts.onProgress);
  emitter.setPhase('starting');

  let session;
  try {
    session = await uploadsApi.createSession(toCreateRequest(file, target), signal);
  } catch (err) {
    if (isEndpointMissing(err)) throw new ChunkedUploadUnsupportedError();
    throw err;
  }

  const { id, chunkBytes, totalChunks } = session;
  const chunkLength = (i: number) => Math.min(chunkBytes, file.size - i * chunkBytes);

  // Bytes accounting: acknowledged chunks are final; in-flight chunks report through
  // XHR progress events and are re-summed on every tick.
  const received = new Set(session.received);
  let ackedBytes = 0;
  for (const i of received) ackedBytes += chunkLength(i);
  const inflight = new Map<number, number>();
  const pending: number[] = [];
  for (let i = 0; i < totalChunks; i++) if (!received.has(i)) pending.push(i);

  const tick = () => {
    let sum = ackedBytes;
    for (const v of inflight.values()) sum += v;
    emitter.setLoaded(sum);
  };

  // One controller for every chunk of this file: the first terminal failure (or the
  // caller's signal) cancels the siblings instead of letting them finish for nothing.
  const ctl = new AbortController();
  const onOuterAbort = () => ctl.abort();
  if (signal?.aborted) ctl.abort();
  signal?.addEventListener('abort', onOuterAbort, { once: true });

  let failure: unknown = null;

  async function sendChunk(index: number): Promise<void> {
    const start = index * chunkBytes;
    const blob = file.slice(start, start + chunkLength(index));
    for (let attempt = 1; ; attempt++) {
      try {
        await uploadsApi.putChunk(id, index, blob, (loaded) => {
          inflight.set(index, Math.min(loaded, blob.size));
          tick();
        }, ctl.signal);
        inflight.delete(index);
        ackedBytes += blob.size;
        tick();
        return;
      } catch (err) {
        inflight.delete(index);
        if (ctl.signal.aborted || !isRetryable(err) || attempt >= MAX_ATTEMPTS) throw err;
        // Show the retry honestly: the bytes of this chunk go back to zero.
        let sum = ackedBytes;
        for (const v of inflight.values()) sum += v;
        emitter.resetLoaded(sum);
        await sleep(RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)], ctl.signal);
      }
    }
  }

  async function worker(): Promise<void> {
    while (pending.length > 0 && !ctl.signal.aborted) {
      const index = pending.shift() as number;
      try {
        await sendChunk(index);
      } catch (err) {
        if (failure == null) failure = err;
        ctl.abort();
        return;
      }
    }
  }

  emitter.setPhase('uploading');
  tick();
  const workerCount = Math.min(parallel, Math.max(1, pending.length));
  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  signal?.removeEventListener('abort', onOuterAbort);

  if (failure != null || signal?.aborted) {
    // Free the server's disk right away; nobody is going to resume this one.
    void uploadsApi.abort(id).catch(() => undefined);
    throw failure ?? cancelledError();
  }

  emitter.setPhase('finalizing');
  for (let attempt = 1; ; attempt++) {
    try {
      const result = await uploadsApi.complete(id, signal);
      emitter.setPhase('done');
      return result;
    } catch (err) {
      // A 4xx here is terminal (duplicate, type refused, over quota) and the server has
      // already dropped the session. A 5xx keeps it, so completing again is cheap.
      if (!isRetryable(err) || attempt >= COMPLETE_ATTEMPTS) throw err;
      await sleep(RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)], signal);
    }
  }
}

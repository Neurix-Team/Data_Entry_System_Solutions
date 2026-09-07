import axios from 'axios';
import { uploadsApi } from './resources';
import type { UploadCompleteResponse, UploadSessionCreateRequest } from './types';


export type UploadPhase = 'starting' | 'uploading' | 'finalizing' | 'done';

export interface UploadProgress {
  phase: UploadPhase;
  loaded: number;
  total: number;
  fraction: number;
  bytesPerSecond: number;
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
  parallel?: number;
}

export class ChunkedUploadUnsupportedError extends Error {
  constructor() {
    super('Chunked upload is not available on this server');
    this.name = 'ChunkedUploadUnsupportedError';
  }
}

export const DEFAULT_CHUNK_PARALLELISM = 4;

const MAX_ATTEMPTS = 4;
const RETRY_DELAYS_MS = [400, 1200, 3000];
const COMPLETE_ATTEMPTS = 3;
const PROGRESS_INTERVAL_MS = 80;
const SPEED_WINDOW_MS = 4000;

export class SpeedMeter {
  private samples: Array<{ t: number; loaded: number }> = [];
  private smoothed = 0;

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
  if (status == null) return true;
  return status === 408 || status === 425 || status === 429 || status >= 500;
}

function isEndpointMissing(err: unknown): boolean {
  if (!axios.isAxiosError(err)) return false;
  const status = err.response?.status;
  if (status === 405) return true;
  if (status !== 404) return false;
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
      if (!isRetryable(err) || attempt >= COMPLETE_ATTEMPTS) throw err;
      await sleep(RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)], signal);
    }
  }
}

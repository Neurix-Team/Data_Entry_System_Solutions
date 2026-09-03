// Muxes the Playwright recording with the narration track and the ducked music bed into an MP4.
// usage: node assemble.mjs <video.webm> <timeline.json> <out.mp4> [music.wav]
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
const ff = createRequire(import.meta.url)('ffmpeg-static');

const [,, videoIn, timelinePath, outPath, musicPath = 'music.wav'] = process.argv;
const timeline = JSON.parse(fs.readFileSync(timelinePath, 'utf8'));
const meta = JSON.parse(fs.readFileSync('narration_meta.json', 'utf8'));

function run(args, label) {
  const r = spawnSync(ff, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (r.status !== 0) { console.error(r.stderr.slice(-2500)); throw new Error(label + ' failed'); }
  return r.stderr;
}
function durationOf(file) {
  const r = spawnSync(ff, ['-i', file, '-f', 'null', '-'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const m = r.stderr.match(/time=(\d+):(\d+):(\d+\.\d+)/g);
  const last = m ? m[m.length - 1].match(/(\d+):(\d+):(\d+\.\d+)/) : null;
  if (!last) throw new Error('no duration for ' + file);
  return (+last[1]) * 3600 + (+last[2]) * 60 + (+last[3]);
}

const rawD = durationOf(videoIn);
// The recorder's clock runs ~2% fast relative to wall time (measured against the chapter
// cards), so the picture is stretched by STRETCH to line up with the tour clock; whatever
// is still missing at the tail is covered by freezing the last frame.
const offset = Number(process.env.OFFSET ?? 0);
const stretch = Number(process.env.STRETCH || 1);
// Piecewise-linear map from tour clock → video time, measured from the chapter cards
// (tour seconds, video seconds). Past the last anchor the slope is 1.
const ANCHORS = JSON.parse(process.env.ANCHORS || '[[0,0]]');
function videoTime(t) {
  for (let i = 1; i < ANCHORS.length; i++) {
    const [t0, v0] = ANCHORS[i - 1], [t1, v1] = ANCHORS[i];
    if (t <= t1) return v0 + (t - t0) * (v1 - v0) / (t1 - t0);
  }
  const [tl, vl] = ANCHORS[ANCHORS.length - 1];
  return vl + (t - tl);
}
const stretched = rawD * stretch;
const need = videoTime(timeline.total) * stretch;
const pad = Math.max(0, need - stretched + 0.6);
const D = stretched + pad;
console.log(`video ${rawD.toFixed(2)}s, tour clock ${timeline.total.toFixed(2)}s → mapped ${need.toFixed(2)}s, stretch ${stretch.toFixed(4)}, offset ${offset.toFixed(2)}s, tail pad ${pad.toFixed(2)}s`);

// ---- build narration track (48k mono s16le) ----
const SR = 48000;
const N = Math.ceil(D * SR) + SR;
const buf = Buffer.alloc(N * 2);
for (const s of timeline.scenes) {
  const m = meta[s.id]; if (!m) { console.warn('no audio for', s.id); continue; }
  const raw = fs.readFileSync(m.raw);
  const at = Math.max(0, Math.round((videoTime(s.start) * stretch - offset + 0.15) * SR));
  console.log(`  ${s.id.padEnd(13)} tour ${s.start.toFixed(1)}s → video ${(videoTime(s.start) * stretch).toFixed(1)}s`);
  const n = Math.min(raw.length / 2, N - at);
  raw.copy(buf, at * 2, 0, n * 2);
}
fs.writeFileSync('narration.raw', buf);
run(['-y', '-f', 's16le', '-ar', String(SR), '-ac', '1', '-i', 'narration.raw', '-af', 'volume=1.7,alimiter=limit=0.97', 'narration.wav'], 'narration');

// ---- mux ----
const fadeOut = Math.max(0, D - 1.4);
const filter = [
  `[0:v]setpts=${stretch.toFixed(5)}*PTS,tpad=stop_mode=clone:stop_duration=${pad.toFixed(2)},fps=30,format=yuv420p,fade=t=in:st=0:d=0.9,fade=t=out:st=${fadeOut.toFixed(2)}:d=1.4[v]`,
  `[2:a]atrim=0:${D.toFixed(2)},asetpts=PTS-STARTPTS,afade=t=in:st=0:d=2,afade=t=out:st=${Math.max(0, D - 6).toFixed(2)}:d=6,volume=0.30[m]`,
  `[1:a]aformat=sample_fmts=fltp:channel_layouts=stereo,asplit=2[n1][n2]`,
  `[m][n2]sidechaincompress=threshold=0.012:ratio=12:attack=30:release=900:makeup=1:level_sc=1[md]`,
  `[md][n1]amix=inputs=2:duration=first:dropout_transition=0:normalize=0,alimiter=limit=0.95[a]`,
].join(';');
const log = run(['-y', '-i', videoIn, '-i', 'narration.wav', '-i', musicPath,
  '-filter_complex', filter, '-map', '[v]', '-map', '[a]',
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-profile:v', 'high', '-movflags', '+faststart',
  '-c:a', 'aac', '-b:a', '192k', '-ar', '48000', '-shortest', outPath], 'mux');
console.log(log.split('\n').filter(l => /video:|Lsize/.test(l)).join('\n'));
// poster frame
run(['-y', '-ss', '5', '-i', outPath, '-frames:v', '1', outPath.replace(/\.mp4$/, '-poster.jpg')], 'poster');
console.log('done', outPath, (fs.statSync(outPath).size / 1e6).toFixed(1), 'MB');

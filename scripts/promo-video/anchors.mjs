import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { SCENES } from './narration.mjs';
const ff = createRequire(import.meta.url)('ffmpeg-static');

const [,, videoIn = 'rec/tour.webm', timelinePath = 'timeline.json'] = process.argv;
const timeline = JSON.parse(fs.readFileSync(timelinePath, 'utf8'));
const CAPTION = Object.fromEntries(SCENES.map(s => [s.id, s.caption]));

function stats(filter, fps) {
  const r = spawnSync(ff, ['-i', videoIn, '-vf', `fps=${fps},${filter},signalstats,metadata=print:file=-`, '-f', 'null', '-'],
    { encoding: 'utf8', maxBuffer: 1024 * 1024 * 1024 });
  if (r.status !== 0) { console.error(r.stderr.slice(-2000)); process.exit(1); }
  const frames = [];
  let cur = null;
  for (const line of r.stdout.split('\n')) {
    const f = line.match(/^frame:\d+\s+pts:\d+\s+pts_time:([\d.]+)/);
    if (f) { cur = { t: +f[1] }; frames.push(cur); continue; }
    const m = line.match(/lavfi\.signalstats\.([YUV])AVG=([\d.]+)/);
    if (m && cur) cur[m[1].toLowerCase()] = +m[2];
  }
  return frames;
}
function runsOf(frames, pred, minLen) {
  const runs = [];
  for (const f of frames) {
    const hit = pred(f), last = runs[runs.length - 1];
    if (hit && last && last.open) { last.end = f.t; continue; }
    if (hit) runs.push({ start: f.t, end: f.t, open: true });
    else if (last && last.open) last.open = false;
  }
  return runs.filter(x => x.end - x.start >= minLen);
}

const CARD_FADE_LEAD = 0.35; // the card fades in over 0.7 s; the classifier trips about this long after the request
const full = stats('scale=192:108', 10);
const cardRuns = runsOf(full, f => f.y < 90 && f.u > 133 && f.v < 126, 1.5);
console.log(`pass 1: ${full.length} frames, card-like runs: ${cardRuns.map(x => `${x.start.toFixed(1)}–${x.end.toFixed(1)}`).join(', ')}`);
const anchors = [[0, 0]];
for (const id of ['ch1', 'ch2', 'ch3', 'platform']) {
  const s = timeline.scenes.find(x => x.id === id);
  if (!s) continue;
  let best = null;
  for (const run of cardRuns) {
    const d = Math.abs(run.start - s.start);
    if (d < s.start * 0.08 + 2 && (!best || d < best.d)) best = { d, run };
  }
  if (!best) { console.warn(`  no card run near ${id} @ ${s.start.toFixed(1)}s`); continue; }
  anchors.push([+s.start.toFixed(2), +Math.max(0, best.run.start - CARD_FADE_LEAD).toFixed(2), id]);
}
const coarse = (t) => {
  const a = anchors.filter(x => x.length).sort((p, q) => p[0] - q[0]);
  for (let i = 1; i < a.length; i++) if (t <= a[i][0]) return a[i - 1][1] + (t - a[i - 1][0]) * (a[i][1] - a[i - 1][1]) / (a[i][0] - a[i - 1][0]);
  const l = a[a.length - 1]; return l[1] + (t - l[0]);
};

const SWAP_MID = 0.4;
const box = stats('crop=240:44:840:992', 20);
for (let i = 1; i < timeline.scenes.length; i++) {
  const s = timeline.scenes[i], prev = timeline.scenes[i - 1];
  if (!CAPTION[s.id] || !CAPTION[prev.id]) continue; // only a caption→caption change blinks
  const exp = coarse(s.start);
  const win = box.filter(f => f.t > exp - 6 && f.t < exp + 6);
  if (win.length < 20) continue;
  const ys = win.map(f => f.y).sort((a, b) => a - b);
  const dark = ys[Math.floor(ys.length * 0.2)], bright = ys[ys.length - 1]; // the blink lasts a few frames, so compare against the window maximum
  if (bright - dark < 25) { console.warn(`  ${s.id}: caption region has no contrast near ${exp.toFixed(1)}s (skipped)`); continue; }
  const thr = dark + (bright - dark) * 0.5;
  const bumps = runsOf(win, f => f.y > thr, 0.1).filter(b => b.end - b.start <= 1.8);
  let best = null;
  for (const b of bumps) { const mid = (b.start + b.end) / 2, d = Math.abs(mid - exp); if (!best || d < best.d) best = { d, mid }; }
  if (!best) { console.warn(`  ${s.id}: no caption blink near ${exp.toFixed(1)}s (skipped)`); continue; }
  anchors.push([+(s.start + SWAP_MID).toFixed(2), +best.mid.toFixed(2), s.id]);
}

anchors.sort((a, b) => a[0] - b[0]);
const clean = [anchors[0]];
for (const a of anchors.slice(1)) {
  const p = clean[clean.length - 1];
  const slope = (a[1] - p[1]) / (a[0] - p[0]);
  if (a[0] - p[0] < 0.5 || slope < 0.85 || slope > 1.15) { console.warn(`  dropping ${a[2]} [${a[0]}, ${a[1]}] (slope ${slope.toFixed(3)})`); continue; }
  clean.push(a);
}
for (const a of clean.slice(1)) console.log(`  ${String(a[2]).padEnd(12)} tour ${a[0].toFixed(2).padStart(7)}s → video ${a[1].toFixed(2).padStart(7)}s  drift ${(a[1] - a[0]).toFixed(2).padStart(6)}s`);
const out = JSON.stringify(clean.map(a => [a[0], a[1]]));
fs.writeFileSync('anchors.json', out);
console.log('ANCHORS=' + out);

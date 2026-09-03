// Generates one MP3 per scene with Edge neural TTS (cached by text hash) and measures durations.
import { MsEdgeTTS, OUTPUT_FORMAT } from 'msedge-tts';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { SCENES, VOICE } from './narration.mjs';
const ff = createRequire(import.meta.url)('ffmpeg-static');

fs.mkdirSync('tts', { recursive: true });
const metaPath = 'narration_meta.json';
const meta = fs.existsSync(metaPath) ? JSON.parse(fs.readFileSync(metaPath, 'utf8')) : {};

let tts = null;
async function synth(text, out) {
  if (!tts) { tts = new MsEdgeTTS(); await tts.setMetadata(VOICE, OUTPUT_FORMAT.AUDIO_24KHZ_96KBITRATE_MONO_MP3); }
  const { audioStream } = tts.toStream(text, { rate: '+3%' });
  const chunks = [];
  for await (const c of audioStream) chunks.push(c);
  fs.writeFileSync(out, Buffer.concat(chunks));
}

for (const s of SCENES) {
  const hash = crypto.createHash('md5').update(VOICE + '|' + s.text).digest('hex').slice(0, 10);
  const mp3 = `tts/${s.id}.mp3`, raw = `tts/${s.id}.raw`;
  if (!meta[s.id] || meta[s.id].hash !== hash || !fs.existsSync(raw)) {
    let ok = false;
    for (let attempt = 0; attempt < 3 && !ok; attempt++) {
      try { await synth(s.text, mp3); ok = true; } catch (e) { console.error('retry', s.id, e.message); tts = null; }
    }
    if (!ok) throw new Error('TTS failed for ' + s.id);
    // decode to raw PCM s16le 48k mono for exact placement later
    const r = spawnSync(ff, ['-y', '-i', mp3, '-f', 's16le', '-ac', '1', '-ar', '48000', raw], { encoding: 'utf8' });
    if (r.status !== 0) throw new Error(r.stderr.slice(-300));
    const duration = fs.statSync(raw).size / (48000 * 2);
    meta[s.id] = { hash, mp3, raw, duration };
    console.log(s.id.padEnd(14), duration.toFixed(2) + 's');
  } else {
    console.log(s.id.padEnd(14), meta[s.id].duration.toFixed(2) + 's (cached)');
  }
}
fs.writeFileSync(metaPath, JSON.stringify(meta, null, 2));
const total = Object.values(meta).reduce((a, b) => a + b.duration, 0);
console.log('total narration', total.toFixed(1), 's');

// Procedural royalty-free "modern tech / corporate" music bed. Writes music.wav (stereo 44.1k 16-bit).
const fs = require('fs');
const SR = 44100, BPM = 100, BEAT = 60 / BPM, BAR = BEAT * 4, SIXTEENTH = BEAT / 4;
const BARS = 172; // ~6:53 at 100 BPM — comfortably longer than the tour
const N = Math.ceil(BARS * BAR * SR) + SR * 4;
const L = new Float32Array(N), R = new Float32Array(N);
const sendL = new Float32Array(N), sendR = new Float32Array(N); // reverb send
const arpL = new Float32Array(N), arpR = new Float32Array(N);   // delay bus

// ---- wavetables ----
const TBL = 4096;
function table(fn) { const t = new Float32Array(TBL); for (let i = 0; i < TBL; i++) t[i] = fn(i / TBL * 2 * Math.PI); return t; }
const tSine = table(x => Math.sin(x));
const tSoftSaw = table(x => { let s = 0; for (let n = 1; n <= 10; n++) s += Math.sin(n * x) / n * Math.exp(-n * 0.25); return s * 0.9; });
const tPluck = table(x => Math.sin(x) + 0.35 * Math.sin(2 * x) + 0.12 * Math.sin(3 * x));
function midi(m) { return 440 * Math.pow(2, (m - 69) / 12); }

// Add a note: tbl, freq, start(s), dur(s), envelope {a,d,s,r}, gain, pan(-1..1), dest arrays, detune (cents)
function note(tbl, freq, start, dur, env, gain, pan, dl, dr, detune = 0) {
  const f = freq * Math.pow(2, detune / 1200);
  const inc = f * TBL / SR;
  let phase = Math.random() * TBL;
  const a = env.a * SR, d = env.d * SR, s = env.s, r = env.r * SR;
  const holdN = Math.floor(dur * SR), totalN = holdN + Math.floor(r);
  const i0 = Math.floor(start * SR);
  const gl = gain * Math.cos((pan + 1) * Math.PI / 4), gr = gain * Math.sin((pan + 1) * Math.PI / 4);
  for (let i = 0; i < totalN; i++) {
    const idx = i0 + i; if (idx >= N) break;
    let e;
    if (i < a) e = i / a;
    else if (i < a + d) e = 1 - (1 - s) * ((i - a) / d);
    else if (i < holdN) e = s;
    else e = s * (1 - (i - holdN) / r);
    if (e <= 0) continue;
    const p = phase | 0, frac = phase - p;
    const v = tbl[p] + (tbl[(p + 1) & (TBL - 1)] - tbl[p]) * frac;
    dl[idx] += v * e * gl; dr[idx] += v * e * gr;
    phase += inc; if (phase >= TBL) phase -= TBL;
  }
}
function kick(start, gain) {
  const i0 = Math.floor(start * SR); let ph = 0;
  for (let i = 0; i < SR * 0.45; i++) {
    const t = i / SR; const f = 45 + 110 * Math.exp(-t * 28);
    ph += 2 * Math.PI * f / SR;
    const e = Math.exp(-t * 7) * (t < 0.004 ? t / 0.004 : 1);
    const v = Math.tanh(Math.sin(ph) * 2.2) * e * gain;
    const idx = i0 + i; if (idx >= N) break; L[idx] += v; R[idx] += v;
  }
}
function hat(start, gain, dur = 0.05) {
  const i0 = Math.floor(start * SR); let prev = 0, y = 0;
  for (let i = 0; i < SR * dur * 3; i++) {
    const t = i / SR; const n = Math.random() * 2 - 1;
    y = 0.6 * (y + n - prev); prev = n; // crude highpass
    const e = Math.exp(-t / dur);
    const idx = i0 + i; if (idx >= N) break;
    L[idx] += y * e * gain * 0.9; R[idx] += y * e * gain * 1.1;
  }
}
function clap(start, gain) {
  const i0 = Math.floor(start * SR); let lp = 0, prev = 0, y = 0;
  for (let i = 0; i < SR * 0.35; i++) {
    const t = i / SR; const n = Math.random() * 2 - 1;
    lp += 0.25 * (n - lp); y = 0.8 * (y + lp - prev); prev = lp; // band-ish
    const bursts = (t < 0.01 || (t > 0.012 && t < 0.022) || (t > 0.025)) ? 1 : 0.2;
    const e = Math.exp(-t * 14) * bursts;
    const idx = i0 + i; if (idx >= N) break;
    const v = y * e * gain * 3;
    L[idx] += v; R[idx] += v; sendL[idx] += v * 0.5; sendR[idx] += v * 0.5;
  }
}

// ---- harmony: Am F C G (vi IV I V) ----
const chords = [
  { root: 45, tones: [45, 52, 57, 60, 64] }, // A2 E3 A3 C4 E4
  { root: 41, tones: [41, 48, 53, 57, 60] }, // F2 C3 F3 A3 C4
  { root: 48, tones: [48, 55, 60, 64, 67] }, // C3 G3 C4 E4 G4
  { root: 43, tones: [43, 50, 55, 59, 62] }, // G2 D3 G3 B3 D4
];
function section(bar) {
  if (bar < 4) return { pad: 1, arp: 0, hat: 0, bass: 0, kick: 0, clap: 0, lead: 0 };
  if (bar < 8) return { pad: 1, arp: 1, hat: 1, bass: 1, kick: 0, clap: 0, lead: 0 };
  if (bar < 64) return { pad: 1, arp: 1, hat: 1, bass: 1, kick: 1, clap: 1, lead: bar >= 16 ? 1 : 0 };
  if (bar < 72) return { pad: 1, arp: 1, hat: 1, bass: 1, kick: 0, clap: 0, lead: 0 }; // breakdown
  if (bar < 120) return { pad: 1, arp: 1, hat: 1, bass: 1, kick: 1, clap: 1, lead: 1 };
  if (bar < 128) return { pad: 1, arp: 1, hat: 1, bass: 1, kick: 0, clap: 0, lead: 0 }; // second breath
  if (bar < 156) return { pad: 1, arp: 1, hat: 1, bass: 1, kick: 1, clap: 1, lead: 1 };
  if (bar < 164) return { pad: 1, arp: 1, hat: 1, bass: 1, kick: 0, clap: 0, lead: 0 };
  return { pad: 1, arp: bar < 168 ? 1 : 0, hat: 0, bass: 0, kick: 0, clap: 0, lead: 0 };
}
const padEnv = { a: 1.4, d: 0.5, s: 0.85, r: 2.0 };
const arpEnv = { a: 0.004, d: 0.22, s: 0.0, r: 0.15 };
const bassEnv = { a: 0.01, d: 0.3, s: 0.6, r: 0.12 };
const leadEnv = { a: 0.05, d: 0.4, s: 0.5, r: 0.5 };

for (let bar = 0; bar < BARS; bar++) {
  const ch = chords[bar % 4]; const t0 = bar * BAR; const sec = section(bar);
  // pads: two detuned soft saws per tone, wide stereo
  if (sec.pad) for (const m of ch.tones) {
    const g = 0.028 * (m < 50 ? 0.7 : 1);
    note(tSoftSaw, midi(m), t0, BAR - 0.05, padEnv, g, -0.55, L, R, -6);
    note(tSoftSaw, midi(m), t0, BAR - 0.05, padEnv, g, 0.55, L, R, +6);
    note(tSoftSaw, midi(m), t0, BAR - 0.05, padEnv, g * 0.9, 0, sendL, sendR, 0);
  }
  // bass
  if (sec.bass) {
    const f = midi(ch.root - 12);
    for (const b of [0, 1.5, 2, 3.5]) {
      const dur = b === 1.5 || b === 3.5 ? BEAT * 0.45 : BEAT * 0.9;
      note(tSine, f, t0 + b * BEAT, dur, bassEnv, 0.22, 0, L, R);
      note(tPluck, f * 2, t0 + b * BEAT, dur, bassEnv, 0.05, 0, L, R);
    }
  }
  // arpeggio: 16ths over the upper chord tones
  if (sec.arp) {
    const seq = [ch.tones[1] + 12, ch.tones[2] + 12, ch.tones[3] + 12, ch.tones[4] + 12, ch.tones[3] + 12, ch.tones[2] + 12, ch.tones[4] + 12, ch.tones[1] + 24];
    for (let s = 0; s < 16; s++) {
      const m = seq[s % seq.length];
      const acc = (s % 4 === 0) ? 1 : 0.7;
      const g = 0.075 * acc * (bar < 8 ? 0.6 : 1);
      note(tPluck, midi(m), t0 + s * SIXTEENTH, SIXTEENTH * 0.6, arpEnv, g, (s % 2 ? 0.35 : -0.35), arpL, arpR);
    }
  }
  // lead: sparse melodic phrase every other bar
  if (sec.lead && bar % 2 === 0) {
    const phrases = [[[0, 76, 1], [1.5, 79, 0.5], [2, 81, 2]], [[0, 79, 1], [1, 76, 0.5], [1.5, 77, 0.5], [2, 79, 2]], [[0, 84, 1.5], [1.5, 83, 0.5], [2, 79, 2]], [[0, 83, 0.5], [0.5, 81, 0.5], [1, 79, 1], [2, 81, 2]]];
    for (const [b, m, len] of phrases[(bar / 2) % 4 | 0]) {
      note(tSine, midi(m), t0 + b * BEAT, len * BEAT * 0.9, leadEnv, 0.06, 0.1, sendL, sendR);
      note(tSine, midi(m), t0 + b * BEAT, len * BEAT * 0.9, leadEnv, 0.05, -0.1, L, R);
    }
  }
  // drums
  if (sec.kick) { for (const b of [0, 1, 2, 3]) kick(t0 + b * BEAT, 0.5); if (bar % 4 === 3) kick(t0 + 3.5 * BEAT, 0.35); }
  if (sec.clap) for (const b of [1, 3]) clap(t0 + b * BEAT, 0.07);
  if (sec.hat) for (let s = 0; s < 16; s++) { const off = s % 2 === 1; hat(t0 + s * SIXTEENTH, off ? 0.06 : 0.025, off ? 0.07 : 0.03); }
}

// ---- ping-pong delay on arp bus ----
{
  const D = Math.round(3 * SIXTEENTH * SR); const fb = 0.42, wet = 0.35;
  const bufL = new Float32Array(D), bufR = new Float32Array(D); let w = 0;
  for (let i = 0; i < N; i++) {
    const dl = bufL[w], dr = bufR[w];
    const outL = arpL[i] + dl * wet, outR = arpR[i] + dr * wet;
    bufL[w] = arpR[i] * 0.6 + dr * fb; bufR[w] = arpL[i] * 0.6 + dl * fb; // cross-feed
    w = (w + 1) % D;
    L[i] += outL; R[i] += outR; sendL[i] += outL * 0.4; sendR[i] += outR * 0.4;
  }
}
// ---- reverb (Freeverb-lite) on send bus ----
function reverb(input, output, seedOffset) {
  const combs = [1557, 1617, 1491, 1422, 1277, 1356].map(d => ({ d: d + seedOffset, buf: new Float32Array(d + seedOffset), i: 0, f: 0 }));
  const aps = [225, 556, 441].map(d => ({ d, buf: new Float32Array(d), i: 0 }));
  const fbk = 0.84, damp = 0.3;
  for (let n = 0; n < N; n++) {
    const x = input[n]; let y = 0;
    for (const c of combs) { const out = c.buf[c.i]; c.f = out * (1 - damp) + c.f * damp; c.buf[c.i] = x + c.f * fbk; c.i = (c.i + 1) % c.d; y += out; }
    y /= combs.length;
    for (const a of aps) { const bo = a.buf[a.i]; const v = y + bo * 0.5; a.buf[a.i] = v; a.i = (a.i + 1) % a.d; y = bo - 0.5 * v; }
    output[n] += y * 0.55;
  }
}
reverb(sendL, L, 0); reverb(sendR, R, 23);

// ---- master: gentle fade-in, soft clip, normalize ----
let peak = 0; for (let i = 0; i < N; i++) { peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i])); }
const norm = 0.85 / peak;
const out = Buffer.alloc(44 + N * 4);
out.write('RIFF', 0); out.writeUInt32LE(36 + N * 4, 4); out.write('WAVE', 8); out.write('fmt ', 12);
out.writeUInt32LE(16, 16); out.writeUInt16LE(1, 20); out.writeUInt16LE(2, 22); out.writeUInt32LE(SR, 24); out.writeUInt32LE(SR * 4, 28); out.writeUInt16LE(4, 32); out.writeUInt16LE(16, 34);
out.write('data', 36); out.writeUInt32LE(N * 4, 40);
for (let i = 0; i < N; i++) {
  const fade = Math.min(1, i / (SR * 2.5));
  const l = Math.tanh(L[i] * norm * 1.15) * fade, r = Math.tanh(R[i] * norm * 1.15) * fade;
  out.writeInt16LE(Math.max(-32767, Math.min(32767, l * 32767)) | 0, 44 + i * 4);
  out.writeInt16LE(Math.max(-32767, Math.min(32767, r * 32767)) | 0, 46 + i * 4);
}
fs.writeFileSync('music.wav', out);
console.log('music.wav', (N / SR).toFixed(1), 's, peak', peak.toFixed(3));

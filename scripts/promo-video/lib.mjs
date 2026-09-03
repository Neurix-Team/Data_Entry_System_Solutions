// Recording framework: in-page overlay (cursor, ripple, lower-third captions, chapter cards),
// camera zoom, human-like mouse/typing, and scene timing bookkeeping.
import fs from 'node:fs';

export const BASE = 'http://localhost:8082';
// SPEED<1 shortens every pause (dry runs); DRY=1 also skips narration holds.
const SPEED = Number(process.env.SPEED) || 1;
export const DRY = !!process.env.DRY;
export const sleep = (ms) => new Promise(r => setTimeout(r, ms * SPEED));

const OVERLAY = `
(() => {
  if (window.__nxOverlay) return;
  window.__nxOverlay = true;
  const style = document.createElement('style');
  style.id = 'nx-style';
  style.textContent = \`
    #nx-cursor{position:fixed;left:0;top:0;width:30px;height:30px;z-index:2147483000;pointer-events:none;opacity:0;transition:opacity .25s;filter:drop-shadow(0 2px 3px rgba(0,0,0,.35));}
    #nx-cursor.on{opacity:1}
    #nx-ripple{position:fixed;left:0;top:0;width:44px;height:44px;margin:-22px 0 0 -22px;border-radius:50%;border:3px solid #22c3d9;z-index:2147482999;pointer-events:none;opacity:0;transform:scale(.3)}
    #nx-ripple.go{animation:nxr .55s ease-out forwards}
    @keyframes nxr{0%{opacity:.9;transform:scale(.3)}100%{opacity:0;transform:scale(1.4)}}
    #nx-caption{position:fixed;left:50%;bottom:34px;transform:translate(-50%,24px);z-index:2147482990;pointer-events:none;display:flex;align-items:center;gap:16px;padding:15px 30px 15px 22px;border-radius:16px;background:rgba(9,20,45,.88);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);color:#fff;font:600 24px/1.25 Inter,'Segoe UI',system-ui,sans-serif;letter-spacing:.1px;box-shadow:0 14px 40px rgba(6,14,32,.35);opacity:0;transition:opacity .45s ease,transform .45s cubic-bezier(.2,.8,.2,1);max-width:82vw;white-space:nowrap}
    #nx-caption.show{opacity:1;transform:translate(-50%,0)}
    #nx-caption .bar{width:7px;height:32px;border-radius:4px;background:linear-gradient(180deg,#22c3d9,#0f5fd1);flex:none}
    #nx-caption .muted{color:#9fb3d9;font-weight:500}
    #nx-ring{position:fixed;z-index:2147482980;pointer-events:none;border:3px solid #22c3d9;border-radius:14px;box-shadow:0 0 0 6px rgba(34,195,217,.18),0 0 30px rgba(34,195,217,.35);opacity:0;transition:opacity .3s,left .5s cubic-bezier(.2,.8,.2,1),top .5s cubic-bezier(.2,.8,.2,1),width .5s cubic-bezier(.2,.8,.2,1),height .5s cubic-bezier(.2,.8,.2,1)}
    #nx-ring.show{opacity:1;animation:nxpulse 1.6s ease-in-out infinite}
    @keyframes nxpulse{0%,100%{box-shadow:0 0 0 6px rgba(34,195,217,.18),0 0 30px rgba(34,195,217,.35)}50%{box-shadow:0 0 0 10px rgba(34,195,217,.10),0 0 44px rgba(34,195,217,.55)}}
    #nx-card{position:fixed;inset:0;z-index:2147483100;opacity:0;transition:opacity .7s ease;pointer-events:none;overflow:hidden;font-family:Inter,'Segoe UI',system-ui,sans-serif;color:#fff;background:radial-gradient(1100px 700px at 18% 28%,rgba(15,95,209,.55),transparent 60%),radial-gradient(900px 600px at 85% 80%,rgba(34,195,217,.35),transparent 60%),linear-gradient(135deg,#061633 0%,#0a2f6e 55%,#0b5d86 100%)}
    #nx-card.show{opacity:1;pointer-events:auto}
    #nx-card .net{position:absolute;inset:-5%;width:110%;height:110%;opacity:.55;animation:nxdrift 26s ease-in-out infinite alternate}
    @keyframes nxdrift{0%{transform:translate(0,0) scale(1)}100%{transform:translate(-2.5%,-1.5%) scale(1.06)}}
    #nx-card .wrap{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:0 8vw}
    #nx-card .logo{width:520px;max-width:60vw;opacity:0;transform:scale(.86) translateY(10px);transition:opacity 1.1s ease,transform 1.3s cubic-bezier(.2,.8,.2,1)}
    #nx-card .logo.small{width:260px}
    #nx-card.anim .logo{opacity:1;transform:none}
    #nx-card .kicker{font-size:18px;letter-spacing:.42em;text-transform:uppercase;color:#7fd8e8;font-weight:700;opacity:0;transform:translateY(14px);transition:all .8s ease .35s}
    #nx-card .title{color:#fff !important;font-size:78px;line-height:1.05;font-weight:800;letter-spacing:-1.5px;margin:18px 0 0;opacity:0;transform:translateY(22px);transition:all .9s cubic-bezier(.2,.8,.2,1) .55s;text-shadow:0 10px 40px rgba(0,0,0,.35);font-family:Inter,'Segoe UI',system-ui,sans-serif}
    #nx-card .title em{font-style:normal;color:#22c3d9}
    #nx-card .sub{font-size:28px;line-height:1.4;color:#c8d8f5;font-weight:500;margin-top:22px;max-width:980px;opacity:0;transform:translateY(22px);transition:all .9s cubic-bezier(.2,.8,.2,1) .85s}
    #nx-card .line{width:0;height:5px;border-radius:3px;background:linear-gradient(90deg,#22c3d9,#0f5fd1);margin-top:34px;transition:width 1.4s cubic-bezier(.2,.8,.2,1) 1s}
    #nx-card.anim .kicker,#nx-card.anim .title,#nx-card.anim .sub{opacity:1;transform:none}
    #nx-card.anim .line{width:180px}
    #nx-card .tiles{display:grid;grid-template-columns:repeat(3,300px);gap:22px;margin-top:44px}
    #nx-card .tile{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.14);border-radius:18px;padding:22px 24px;text-align:left;opacity:0;transform:translateY(24px);transition:all .7s cubic-bezier(.2,.8,.2,1);backdrop-filter:blur(6px)}
    #nx-card.anim .tile{opacity:1;transform:none}
    #nx-card .tile b{display:block;font-size:22px;margin-bottom:6px;color:#fff}
    #nx-card .tile span{font-size:16px;color:#b9cbec;line-height:1.4}
    #nx-card .foot{position:absolute;bottom:38px;left:0;right:0;text-align:center;color:#8fa6cf;font-size:16px;letter-spacing:.08em;opacity:0;transition:opacity 1s ease 1.4s}
    #nx-card.anim .foot{opacity:1}
    #nx-card .badge{position:absolute;top:44px;left:56px;display:flex;align-items:center;gap:12px;font-size:16px;letter-spacing:.3em;text-transform:uppercase;color:#9fd9e6;font-weight:700;opacity:0;transition:opacity .8s ease .2s}
    #nx-card.anim .badge{opacity:1}
    #nx-card .badge i{display:block;width:34px;height:3px;background:#22c3d9;border-radius:2px}
  \`;
  document.head.appendChild(style);
  const cur = document.createElement('div'); cur.id = 'nx-cursor';
  cur.innerHTML = '<svg viewBox="0 0 24 24" width="30" height="30"><path d="M5 3l14 8.5-6.3 1.3L16 20l-2.6 1.3-3.4-7.2L5 18z" fill="#fff" stroke="#0d1a33" stroke-width="1.6" stroke-linejoin="round"/></svg>';
  const rip = document.createElement('div'); rip.id = 'nx-ripple';
  const cap = document.createElement('div'); cap.id = 'nx-caption'; cap.innerHTML = '<span class="bar"></span><span class="txt"></span>';
  const ring = document.createElement('div'); ring.id = 'nx-ring';
  const card = document.createElement('div'); card.id = 'nx-card';
  document.body.append(cur, rip, cap, ring, card);
  window.__nxPos = { x: -100, y: -100 };
  document.addEventListener('mousemove', (e) => {
    window.__nxPos = { x: e.clientX, y: e.clientY };
    cur.style.transform = 'translate(' + (e.clientX - 3) + 'px,' + (e.clientY - 2) + 'px)';
    cur.classList.add('on');
  }, true);
  document.addEventListener('mousedown', (e) => {
    rip.style.left = e.clientX + 'px'; rip.style.top = e.clientY + 'px';
    rip.classList.remove('go'); void rip.offsetWidth; rip.classList.add('go');
  }, true);
  window.__nxCaption = (html) => {
    if (html === null) { cap.classList.remove('show'); return; }
    const txt = cap.querySelector('.txt');
    if (cap.classList.contains('show')) {
      cap.classList.remove('show');
      setTimeout(() => { txt.innerHTML = html; cap.classList.add('show'); }, 380);
    } else { txt.innerHTML = html; cap.classList.add('show'); }
  };
  window.__nxRing = (rect) => {
    if (!rect) { ring.classList.remove('show'); return; }
    ring.style.left = (rect.x - 8) + 'px'; ring.style.top = (rect.y - 8) + 'px';
    ring.style.width = (rect.width + 16) + 'px'; ring.style.height = (rect.height + 16) + 'px';
    ring.classList.add('show');
  };
  window.__nxNet = () => {
    const w = 2100, h = 1250, pts = [];
    for (let i = 0; i < 90; i++) pts.push([Math.random() * w, Math.random() * h]);
    let s = '<svg class="net" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="xMidYMid slice">';
    for (let i = 0; i < pts.length; i++) for (let j = i + 1; j < pts.length; j++) {
      const dx = pts[i][0] - pts[j][0], dy = pts[i][1] - pts[j][1], d = Math.hypot(dx, dy);
      if (d < 210) s += '<line x1="' + pts[i][0] + '" y1="' + pts[i][1] + '" x2="' + pts[j][0] + '" y2="' + pts[j][1] + '" stroke="#7fd8e8" stroke-opacity="' + (0.35 * (1 - d / 210)).toFixed(2) + '" stroke-width="1.2"/>';
    }
    for (const p of pts) s += '<circle cx="' + p[0] + '" cy="' + p[1] + '" r="' + (2 + Math.random() * 2.5).toFixed(1) + '" fill="#9fe6f2" fill-opacity=".8"/>';
    return s + '</svg>';
  };
  window.__nxCard = (html) => {
    if (html === null) { card.classList.remove('show'); card.classList.remove('anim'); return; }
    card.innerHTML = window.__nxNet() + html;
    card.classList.remove('anim'); card.classList.add('show');
    requestAnimationFrame(() => requestAnimationFrame(() => card.classList.add('anim')));
  };
  window.__nxZoom = (scale, x, y, ms) => {
    const root = document.getElementById('root');
    document.documentElement.style.overflow = 'hidden';
    root.style.transformOrigin = x + 'px ' + y + 'px';
    root.style.transition = 'transform ' + ms + 'ms cubic-bezier(.25,.8,.25,1)';
    root.style.transform = scale === 1 ? '' : 'scale(' + scale + ')';
    if (scale === 1) setTimeout(() => { document.documentElement.style.overflow = ''; root.style.transition = ''; }, ms + 50);
  };
})();`;

export async function installOverlay(page) {
  await page.evaluate(OVERLAY);
}

function ease(t) { return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }

export class Tour {
  constructor(page, meta, log = console.log) {
    this.page = page; this.meta = meta; this.log = log;
    this.t0 = null; this.scenes = []; this.mouse = { x: 960, y: 540 };
  }
  start() { this.t0 = Date.now(); }
  now() { return (Date.now() - this.t0) / 1000; }
  async ensure() { await installOverlay(this.page); }

  // ---------- scenes ----------
  async scene(id, fn, opts = {}) {
    const start = this.now();
    const s = { id, start };
    this.scenes.push(s); this.cur = s;
    this.log(`▶ ${id} @ ${start.toFixed(1)}s`);
    await this.ensure();
    if (opts.caption !== undefined) await this.caption(opts.caption);
    try { await fn(); }
    catch (e) {
      this.log(`   ✖ ${id}: ${e.message.split('\n')[0]}`);
      await this.page.screenshot({ path: `rec/err_${id}.png` }).catch(() => {});
      await this.page.keyboard.press('Escape').catch(() => {});
    }
    const dur = this.meta[id]?.duration ?? 0;
    await this.holdUntil(start + dur + (opts.tail ?? 0.9));
    s.end = this.now();
    this.log(`   done @ ${s.end.toFixed(1)}s (narration ${dur.toFixed(1)}s)`);
  }
  async holdUntil(t) { if (DRY) return; const ms = (t - this.now()) * 1000; if (ms > 0) await new Promise(r => setTimeout(r, ms)); }
  /** Wait until `offset` seconds before the current scene's narration ends. */
  async holdRemaining(offset = 0) { const dur = this.meta[this.cur.id]?.duration ?? 0; await this.holdUntil(this.cur.start + dur - offset); }

  // ---------- overlay ----------
  async caption(html) { await this.ensure(); await this.page.evaluate((h) => window.__nxCaption(h), html); }
  async card(html) { await this.ensure(); await this.page.evaluate((h) => window.__nxCard(h), html); }
  async cardOff() { await this.page.evaluate(() => window.__nxCard(null)); await sleep(750); }
  async ring(locator, ms = 2200) {
    const box = await locator.boundingBox();
    if (!box) return;
    await this.page.evaluate((r) => window.__nxRing(r), box);
    if (ms) { await sleep(ms); await this.page.evaluate(() => window.__nxRing(null)); }
  }
  async ringOff() { await this.page.evaluate(() => window.__nxRing(null)); }
  async zoom(scale, x, y, ms = 1800) { await this.page.evaluate(([s, x, y, m]) => window.__nxZoom(s, x, y, m), [scale, x, y, ms]); }
  async zoomOn(locator, scale = 1.18, ms = 1800) {
    const b = await locator.boundingBox(); if (!b) return;
    await this.zoom(scale, b.x + b.width / 2, b.y + b.height / 2, ms);
  }
  async zoomReset(ms = 1200) { await this.zoom(1, 960, 540, ms); }

  // ---------- mouse ----------
  async moveTo(target, ms = 550) {
    let x, y;
    if (target.x !== undefined) ({ x, y } = target);
    else {
      const b = await target.boundingBox();
      if (!b) throw new Error('no box for target');
      x = b.x + b.width / 2; y = b.y + b.height / 2;
    }
    const from = { ...this.mouse };
    const steps = Math.max(8, Math.round(ms / 16));
    for (let i = 1; i <= steps; i++) {
      const t = ease(i / steps);
      const px = from.x + (x - from.x) * t, py = from.y + (y - from.y) * t;
      await this.page.mouse.move(px, py);
      await sleep(14);
    }
    this.mouse = { x, y };
  }
  async click(target, opts = {}) {
    if (typeof target !== 'object' || target.x === undefined) await target.scrollIntoViewIfNeeded().catch(() => {});
    await this.moveTo(target, opts.ms ?? 550);
    await sleep(opts.pause ?? 160);
    await this.page.mouse.down(); await sleep(70); await this.page.mouse.up();
    await sleep(opts.after ?? 350);
  }
  async hover(target, ms = 500) { await this.moveTo(target, ms); }
  async type(locator, text, delay = 34) {
    await this.click(locator, { after: 120 });
    await this.page.keyboard.type(text, { delay });
  }
  async select(locator, value) {
    await locator.scrollIntoViewIfNeeded().catch(() => {});
    await this.moveTo(locator, 450);
    await sleep(150);
    await locator.selectOption(value);
    await sleep(450);
  }
  async scroll(dy, steps = 8) {
    for (let i = 0; i < steps; i++) { await this.page.mouse.wheel(0, dy / steps); await sleep(45); }
    await sleep(300);
  }
  async goto(path) {
    await this.page.goto(BASE + path, { waitUntil: 'networkidle' });
    await this.ensure();
  }
  async nav(linkText) {
    const link = this.page.getByRole('link', { name: linkText, exact: true }).first();
    await this.click(link, { after: 700 });
    await this.page.waitForLoadState('networkidle').catch(() => {});
    await sleep(500);
  }
  /** SPA navigation without the mouse (used behind chapter cards). */
  async silentNav(linkText) {
    await this.page.getByRole('link', { name: linkText, exact: true }).first().dispatchEvent('click');
    await this.page.waitForLoadState('networkidle').catch(() => {});
  }
  dump(file) { fs.writeFileSync(file, JSON.stringify({ scenes: this.scenes, total: this.now() }, null, 2)); }
}

// ---------- cards ----------
export function introCard() {
  return `<div class="wrap">
    <img class="logo" src="/neurix-logo-light.png" alt="Neurix">
    <div class="kicker">Data Entry Management System</div>
    <h1 class="title">Turn documents into <em>structured knowledge</em></h1>
    <div class="sub">One workspace for data-entry agents, team leaders and your AI data pipeline.</div>
    <div class="line"></div>
  </div><div class="foot">NEURIX · 2026</div>`;
}
export function chapterCard(num, title, sub) {
  return `<div class="badge"><i></i> Chapter ${num}</div><div class="wrap">
    <div class="kicker">Chapter ${num}</div>
    <h1 class="title">${title}</h1>
    <div class="sub">${sub}</div>
    <div class="line"></div>
  </div>`;
}
export function platformCard() {
  const tiles = [
    ['Java 17 · Spring Boot 3', 'Stateless JWT API, JPA/Hibernate, audit log on every sensitive action'],
    ['React 18 · TypeScript', 'Route-level code splitting, skeleton loading, hand-crafted design system'],
    ['PostgreSQL 16+', 'Per-team isolation enforced on every write path'],
    ['Docker Compose', 'Postgres, API, UI and LibreTranslate in one stack with auto-SSL labels'],
    ['Self-hosted translation', 'Arabic ⇄ English on the server, no API keys, no rate limits'],
    ['Built for scale', 'Streaming uploads to 500 MB, daily quotas, OCR gate, brute-force protection'],
  ].map((t, i) => `<div class="tile" style="transition-delay:${0.5 + i * 0.14}s"><b>${t[0]}</b><span>${t[1]}</span></div>`).join('');
  return `<div class="badge"><i></i> Platform</div><div class="wrap">
    <div class="kicker">Under the hood</div>
    <h1 class="title" style="font-size:62px">Production-grade by design</h1>
    <div class="tiles">${tiles}</div>
  </div>`;
}
export function outroCard() {
  return `<div class="wrap">
    <img class="logo" src="/neurix-logo-light.png" alt="Neurix">
    <h1 class="title" style="font-size:64px;margin-top:30px">Manage data entry <em>with confidence</em></h1>
    <div class="sub">dataentry.neurix.uk</div>
    <div class="line"></div>
  </div><div class="foot">© 2026 NEURIX — ALL RIGHTS RESERVED</div>`;
}

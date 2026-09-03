// The scripted walkthrough. Records one continuous 1080p video of the whole system.
import { chromium } from 'playwright';
import fs from 'node:fs';
import { Tour, BASE, DRY, sleep, introCard, chapterCard, platformCard, outroCard } from './lib.mjs';

const meta = JSON.parse(fs.readFileSync('narration_meta.json', 'utf8'));
const state = JSON.parse(fs.readFileSync('seed_state.json', 'utf8'));
const AGENT = { u: 'omar.hassan', p: 'Agent#2026' };
const ADMIN = { u: 'demo.admin', p: 'DemoAdmin#2026' };
const SUPER = { u: 'superadmin', p: 'superadmin123' };
fs.mkdirSync('rec', { recursive: true });
fs.mkdirSync('live', { recursive: true });

// ---------- tiny API client for prep / cleanup ----------
async function api(u, p) {
  const r = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: p }) });
  if (!r.ok) throw new Error('login failed ' + u);
  const cookie = (r.headers.getSetCookie?.() || []).map(c => c.split(';')[0]).join('; ');
  const { token } = await r.json();
  const call = async (method, url, body) => {
    const h = { Cookie: cookie, Authorization: 'Bearer ' + token, 'Accept-Language': 'en' };
    if (body) h['Content-Type'] = 'application/json';
    const res = await fetch(BASE + url, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
    const t = await res.text(); let j = null; try { j = t ? JSON.parse(t) : null; } catch { j = t; }
    return { status: res.status, json: j };
  };
  return { get: (u) => call('GET', u), post: (u, b) => call('POST', u, b), del: (u) => call('DELETE', u) };
}

async function prep() {
  const adm = await api(ADMIN.u, ADMIN.p);
  // fresh notification for Omar + at least one "completed today"
  let all = (await adm.get('/api/admin/tickets?page=0&size=100')).json.items || [];
  // tidy leftovers from previous takes so the folders and lists look fresh
  const junk = all.filter(t => /^scan [1-3]$/i.test(t.title || '') || /Industrial Growth in the Delta — Chapter 2|Rail Links and Export Markets, 1930s/.test(t.title || ''));
  for (const t of junk) await adm.del(`/api/admin/tickets/${t.id}`);
  if (junk.length) all = (await adm.get('/api/admin/tickets?page=0&size=100')).json.items || [];
  console.log('prep: removed leftovers', junk.length);
  const pending = all.filter(t => t.status !== 'COMPLETED');
  const omar = pending.find(t => t.submittedByUsername === AGENT.u);
  if (omar) await adm.post(`/api/admin/tickets/${omar.id}/approve`);
  const other = pending.find(t => t.submittedByUsername !== AGENT.u);
  if (other) await adm.post(`/api/admin/tickets/${other.id}/approve`);
  // remove the member created by a previous run so the live "Add Member" works again
  const users = (await adm.get('/api/admin/users')).json;
  const hana = users.find(u => u.username === 'hana.adel');
  if (hana) await adm.del(`/api/admin/users/${hana.id}`);
  console.log('prep: approved', omar?.id, other?.id, 'hana removed:', !!hana);

  // unique files for the live uploads (unique bytes so duplicate detection does not block them)
  const stamp = new Date().toISOString();
  const browser = await chromium.launch({ channel: 'chrome' });
  const page = await browser.newPage({ viewport: { width: 1800, height: 2400 } });
  const scan = (title, n) => `<html><body style="margin:0;background:#f4efe3"><canvas id=c width=1800 height=2400 style="position:absolute;inset:0"></canvas>
    <div style="position:relative;padding:150px 160px;font-family:Georgia,serif;color:#1c1c1c;transform:rotate(${(n % 2 ? -0.35 : 0.3)}deg)">
    <div style="font-size:22px;color:#777;letter-spacing:.25em;text-transform:uppercase">Water Infrastructure Survey · scanned page ${n}</div>
    <h1 style="font-size:64px;margin:16px 0 40px;font-weight:600">${title}</h1>
    <p style="font-size:34px;line-height:1.75;text-align:justify">Reverse osmosis pushes seawater through semi-permeable membranes at high pressure, removing dissolved salts. It is the most energy-efficient large-scale desalination method in use today, and membrane recovery is the first figure an inspector checks.</p>
    <p style="font-size:34px;line-height:1.75;text-align:justify">Thermal methods such as multi-stage flash distillation remain common where waste heat from power plants is available. Brine disposal is the main environmental concern, and diffuser outfalls reduce the local salinity impact on marine habitats.</p>
    <p style="font-size:34px;line-height:1.75;text-align:justify">Field reference ${stamp} · batch ${n}. Sample readings are listed on the following page together with the calibration notes of the survey team.</p>
    <div style="position:absolute;bottom:-60px;left:0;right:0;text-align:center;font-size:24px;color:#888">— ${n} —</div></div>
    <script>const c=document.getElementById('c').getContext('2d');const im=c.createImageData(1800,2400);const d=im.data;for(let i=0;i<d.length;i+=4){const v=225+Math.random()*30;d[i]=v;d[i+1]=v-4;d[i+2]=v-16;d[i+3]=255;}c.putImageData(im,0,0);</script></body></html>`;
  const titles = ['Intake Screens and Pre-treatment', 'Membrane Recovery Readings', 'Brine Outfall Monitoring Log'];
  for (let i = 0; i < 3; i++) {
    await page.setContent(scan(titles[i], i + 1));
    await sleep(400);
    await page.screenshot({ path: `live/scan_${i + 1}.png`, fullPage: true });
  }
  await page.setContent(`<html><body style="margin:0;padding:90px;font-family:Georgia,serif;color:#111"><h1>Rail Links and Export Markets, 1930s</h1><p style="font-size:19px;line-height:1.7">Rail links completed in the 1930s connected the mills to coastal ports, cutting transport time for finished cloth from weeks to days and opening export markets. Reference ${stamp}.</p></body></html>`);
  await page.pdf({ path: 'live/report.pdf', format: 'A4', printBackground: true });
  await browser.close();
  console.log('prep: live files', fs.readdirSync('live').map(f => f + ' ' + (fs.statSync('live/' + f).size / 1e6).toFixed(1) + 'MB').join(', '));
}

async function cleanup() {
  const sup = await api(SUPER.u, SUPER.p);
  const tokens = (await sup.get('/api/super/api-tokens')).json || [];
  for (const t of tokens.filter(t => t.name === 'AI ingest job')) {
    await sup.post(`/api/super/api-tokens/${t.id}/revoke`); await sup.del(`/api/super/api-tokens/${t.id}`);
  }
  console.log('cleanup: demo tokens removed', tokens.filter(t => t.name === 'AI ingest job').length);
}

// ---------- the tour ----------
async function main() {
  await prep();
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({
    viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1, locale: 'en-US', timezoneId: 'Africa/Cairo', colorScheme: 'light',
    ...(DRY ? {} : { recordVideo: { dir: 'rec', size: { width: 1920, height: 1080 } } }),
  });
  await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE });
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  const tour = new Tour(page, meta);
  tour.start();

  async function switchUser(creds, landing) {
    await page.locator('.topbar-signout').first().dispatchEvent('click');
    await page.waitForURL(/\/login/, { timeout: 15000 });
    await sleep(400);
    await page.locator('#username').focus(); await page.keyboard.type(creds.u);
    await page.locator('#password').focus(); await page.keyboard.type(creds.p);
    await page.keyboard.press('Enter');
    // The login page returns the user to the page they came from, so steer to the role's home via the sidebar.
    await page.waitForURL(u => !u.pathname.includes('/login'), { timeout: 20000 });
    await page.waitForLoadState('networkidle').catch(() => {});
    if (!page.url().includes(landing)) {
      const link = landing === '/super'
        ? page.getByRole('link', { name: 'Super Admin' }).first()
        : page.getByRole('link', { name: 'Dashboard', exact: true }).first();
      await link.dispatchEvent('click');
      await page.waitForURL(u => u.pathname.startsWith(landing), { timeout: 15000 });
      await page.waitForLoadState('networkidle').catch(() => {});
    }
    await sleep(500);
  }

  // ===== INTRO =====
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await tour.ensure();
  await tour.scene('intro', async () => {
    await tour.card(introCard());
    await page.waitForLoadState('networkidle').catch(() => {});
  }, { caption: null, tail: 0.6 });

  // ===== LOGIN =====
  await tour.scene('login', async () => {
    await tour.cardOff();
    await tour.moveTo({ x: 1450, y: 560 }, 900);
    await tour.type(page.locator('#username'), AGENT.u, 75);
    await tour.type(page.locator('#password'), AGENT.p, 65);
    await tour.hover(page.locator('button', { hasText: 'العربية' }).first(), 700);
    await sleep(900);
    await tour.holdRemaining(2.2);
    await tour.click(page.locator('button[type=submit]'), { after: 100 });
    await page.waitForURL(/dashboard/, { timeout: 20000 });
  }, { caption: SCENE_CAPTION('login'), tail: 0.2 });

  // ===== CHAPTER 1 =====
  await tour.scene('ch1', async () => {
    await tour.card(chapterCard('01', 'Built for <em>data-entry agents</em>', 'A focused workspace for submitting, tracking and uploading work.'));
    await sleep(900);
    await tour.silentNav('New Entry');
  }, { caption: null, tail: 0.3 });

  // ===== AGENT DASHBOARD =====
  await tour.scene('dash_user', async () => {
    await tour.silentNav('Dashboard');
    await sleep(200);
    await tour.cardOff();
    await tour.moveTo({ x: 640, y: 330 }, 800);
    await tour.zoom(1.12, 1000, 280, 2400);
    await sleep(2600);
    await tour.moveTo({ x: 1400, y: 330 }, 1000);
    await sleep(500);
    await tour.zoomReset(1300); await sleep(1400);
    await tour.scroll(520, 10); await sleep(500);
    const bars = page.locator('.udash-trend-bar'); const n = await bars.count();
    for (const i of [n - 12, n - 6, n - 1]) if (i >= 0) { await tour.hover(bars.nth(i), 380); await sleep(260); }
    await tour.scroll(520, 10); await sleep(1400);
    await tour.scroll(-1100, 12);
  }, { caption: SCENE_CAPTION('dash_user') });

  // ===== NEW ENTRY: dynamic form =====
  await tour.scene('submit_form', async () => {
    await tour.nav('New Entry');
    const selects = page.locator('select.select');
    await tour.select(selects.nth(0), { label: 'Industrial Heritage Archive' });
    await tour.select(selects.nth(1), { label: 'Historical Records' });
    await tour.select(selects.nth(2), { label: 'Mill Ledgers' });
    await sleep(500);
    const section = page.locator('.form-section-heading').first().locator('..');
    await tour.ring(section, 1600);
    await tour.select(page.locator('select:has(option:text-is("High"))').first(), { label: 'High' });
    await tour.type(page.getByPlaceholder('e.g. 1934'), '1936', 70);
    await tour.type(page.getByPlaceholder('e.g. TM-1934-017'), 'TM-1936-021', 50);
    await tour.click(page.locator('.article-mode-card-content').first());
    await tour.type(page.getByPlaceholder('e.g. Electromagnetic Waves'), 'Industrial Growth in the Delta — Chapter 2', 42);
  }, { caption: SCENE_CAPTION('submit_form') });

  // ===== NEW ENTRY: OCR =====
  await tour.scene('submit_ocr', async () => {
    await tour.click(page.getByRole('button', { name: /Extract text from file/ }).first());
    const modal = page.locator('.modal').last();
    await modal.waitFor({ timeout: 10000 });
    await sleep(500);
    await tour.moveTo(modal.locator('input[type=file]'), 600);
    await modal.locator('input[type=file]').setInputFiles('docs/history_scan.png');
    await modal.getByText(/Extracted from/).waitFor({ timeout: 90000 });
    await sleep(500);
    await tour.ring(modal, 2600);
    await tour.click(modal.getByRole('button', { name: /Use as content|Insert into article/ }));
    await sleep(500);
    await tour.hover(page.locator('textarea.textarea').first(), 700);
  }, { caption: SCENE_CAPTION('submit_ocr') });

  // ===== NEW ENTRY: AI check, second article with attachment, submit =====
  await tour.scene('submit_ai', async () => {
    await tour.click(page.getByRole('button', { name: /Check Content/ }).first());
    const dlg = page.locator('.modal').last();
    await dlg.getByRole('button', { name: 'Apply suggestion' }).waitFor({ timeout: 30000 });
    await sleep(2600);
    await tour.click(dlg.getByRole('button', { name: 'Apply suggestion' }));
    await sleep(500);
    await tour.click(page.getByRole('button', { name: 'Add another article' }).last());
    await sleep(500);
    const card1 = page.locator('.article-card').nth(0);
    const card2 = page.locator('.article-card').nth(1);
    await tour.type(card2.getByPlaceholder('e.g. Electromagnetic Waves'), 'Rail Links and Export Markets, 1930s', 40);
    // the mode switch (write / attach) is global for all articles; every article shows one document row
    await tour.click(page.locator('.article-mode-card-attachments').first());
    await sleep(500);
    const attach = async (card, name, file) => {
      if (await card.locator('input[type=file]').count() === 0) {
        await tour.click(card.getByRole('button', { name: /Add document/ }));
        await sleep(300);
      }
      await tour.type(card.getByPlaceholder('e.g. Q1 Report').last(), name, 30);
      await card.locator('input[type=file]').last().setInputFiles(file);
      await sleep(500);
    };
    await attach(card1, 'Scanned page — chapter 2', 'live/scan_1.png');
    await attach(card2, 'Rail links survey — original scan', 'live/report.pdf');
    await sleep(400);
    await tour.click(page.getByRole('button', { name: /Submit 2 article/ }));
    const outcome = await Promise.race([
      page.locator('.toast-message').filter({ hasText: /submitted/i }).waitFor({ timeout: 45000 }).then(() => 'submitted'),
      page.locator('.field-error').first().waitFor({ timeout: 45000 }).then(() => 'validation-error'),
    ]).catch(() => 'timeout');
    tour.log('   submit outcome: ' + outcome);
    await sleep(1500);
  }, { caption: SCENE_CAPTION('submit_ai') });

  // ===== MY ENTRIES =====
  await tour.scene('my_entries', async () => {
    await tour.nav('My Tasks');
    await tour.type(page.getByPlaceholder('Search content, website…'), 'Delta', 80);
    await sleep(1000);
    await tour.click(page.getByRole('button', { name: 'View' }).first());
    await sleep(3000);
    await tour.click(page.getByRole('button', { name: 'Close' }).last());
  }, { caption: SCENE_CAPTION('my_entries') });

  // ===== PROJECT FOLDERS: quick upload =====
  await tour.scene('folders_user', async () => {
    await tour.nav('Project Folders');
    await sleep(300);
    await tour.click(page.locator('a.dept-card', { hasText: 'Water Infrastructure Survey' }));
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(600);
    await tour.click(page.getByRole('button', { name: 'Upload multiple files' }));
    const modal = page.locator('.modal').last();
    await modal.waitFor({ timeout: 10000 });
    await tour.select(modal.locator('#quick-upload-dept'), { label: 'Desalination' });
    await tour.moveTo(modal.getByText('Drop files here or click to browse'), 600);
    await modal.locator('input[type=file]').setInputFiles(['live/scan_1.png', 'live/scan_2.png', 'live/scan_3.png']);
    await sleep(1000);
    await tour.hover(modal.locator('input.input').nth(0), 500);
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 20, downloadThroughput: -1, uploadThroughput: 1_600_000 });
    await tour.click(modal.getByRole('button', { name: /^Upload 3 files/ }));
    await page.locator('.modal').waitFor({ state: 'detached', timeout: 120000 }).catch(() => {});
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await sleep(700);
    await tour.ring(page.locator('table').first(), 1500);
  }, { caption: SCENE_CAPTION('folders_user') });

  // ===== NOTIFICATIONS =====
  await tour.scene('notify', async () => {
    await tour.click(page.locator('button.notification-btn'));
    await sleep(700);
    const item = page.locator('li button').filter({ hasText: /approved/ }).first();
    await tour.ring(item, 1500);
    await tour.click(item);
    await page.waitForLoadState('networkidle').catch(() => {});
  }, { caption: SCENE_CAPTION('notify') });

  // ===== ASSISTANT =====
  await tour.scene('assistant', async () => {
    await tour.click(page.locator('button.chat-fab'));
    await sleep(700);
    await tour.type(page.locator('.chat-panel textarea'), 'How do I submit a new entry?', 45);
    await tour.click(page.locator('.chat-send'));
    const action = page.locator('.chat-action-btn').first();
    await action.waitFor({ timeout: 15000 });
    await sleep(1300);
    await tour.click(action);
    await sleep(900);
    if (await page.locator('.chat-panel').isVisible().catch(() => false)) await tour.click(page.locator('button.chat-fab'));
  }, { caption: SCENE_CAPTION('assistant') });

  // ===== THEME + LANGUAGE =====
  await tour.scene('theme', async () => {
    const icons = page.locator('.topbar-right .icon-btn');
    await tour.click(icons.nth(0)); await sleep(2300);
    await tour.click(icons.nth(1)); await sleep(3000);
    await tour.click(icons.nth(1)); await sleep(500);
    await tour.click(icons.nth(0)); await sleep(300);
  }, { caption: SCENE_CAPTION('theme') });

  // ===== CHAPTER 2 =====
  await tour.scene('ch2', async () => {
    await tour.card(chapterCard('02', 'Built for <em>team leaders</em>', 'Structure the work, review every entry, and see who is delivering.'));
    await sleep(600);
    await switchUser(ADMIN, '/admin');
    await tour.silentNav('Team Members');
  }, { caption: null, tail: 0.3 });

  // ===== ADMIN DASHBOARD =====
  await tour.scene('dash_admin', async () => {
    await tour.silentNav('Dashboard');
    await sleep(200);
    await tour.cardOff();
    await tour.moveTo({ x: 900, y: 180 }, 800);
    await tour.zoom(1.12, 1080, 200, 2200); await sleep(2400); await tour.zoomReset(1200); await sleep(1300);
    await tour.scroll(450, 10); await sleep(1500);
    await tour.scroll(500, 10); await sleep(500);
    await tour.click(page.getByRole('button', { name: 'Show subcategories' }).first()); await sleep(1300);
    await tour.scroll(800, 12); await sleep(300);
    await tour.click(page.getByRole('button', { name: 'Month', exact: true })); await sleep(1500);
  }, { caption: SCENE_CAPTION('dash_admin') });

  // ===== TEAM MEMBERS =====
  await tour.scene('members', async () => {
    await tour.nav('Team Members');
    await tour.hover(page.locator('table tbody tr').nth(2), 600); await sleep(400);
    await tour.click(page.getByRole('button', { name: '+ Add Member' }));
    const m = page.locator('.side-panel').last();
    await m.waitFor({ timeout: 10000 });
    await tour.type(m.getByPlaceholder('e.g. Jane Doe'), 'Hana Adel', 50);
    await tour.type(m.getByPlaceholder('jane.doe', { exact: true }), 'hana.adel', 50);
    await tour.type(m.getByPlaceholder('jane.doe@company.com'), 'hana.adel@neurix.demo', 28);
    await tour.type(m.getByPlaceholder('At least 6 characters'), 'Agent#2026', 35);
    await tour.click(m.getByRole('button', { name: 'Save Member' }));
    await sleep(1600);
  }, { caption: SCENE_CAPTION('members') });

  // ===== PROJECTS / DEPARTMENTS / SUBCATEGORIES =====
  await tour.scene('structure', async () => {
    await tour.nav('Projects');
    await tour.hover(page.locator('table tbody tr').nth(0), 700); await sleep(600);
    await tour.hover(page.locator('table tbody tr').nth(2), 700); await sleep(900);
    await tour.nav('Departments');
    await sleep(300);
    await tour.click(page.locator('button.dept-card-open').first());
    await sleep(2600);
    await tour.click(page.locator('.modal-header button').first());
    await tour.nav('Subcategories');
    await sleep(300);
    await tour.hover(page.locator('.dept-mini-stat').nth(1), 600);
    await sleep(900);
  }, { caption: SCENE_CAPTION('structure') });

  // ===== DATA ENTRY TASKS =====
  await tour.scene('tasks', async () => {
    await tour.nav('Data Entry Tasks');
    const search = page.getByPlaceholder('Search content, website, agent…');
    await tour.type(search, 'desalination', 60);
    await sleep(1300);
    await page.keyboard.press('Control+A'); await page.keyboard.press('Backspace'); await sleep(500);
    const sel = page.locator('select.select');
    await tour.select(sel.nth(0), { label: 'Historical Records' });
    await tour.select(sel.nth(1), { label: 'Completed' });
    await sleep(900);
    // inline status change on the first row (pick a value different from the current one)
    const inline = page.locator('select.actions-select').first();
    const current = await inline.inputValue().catch(() => 'COMPLETED');
    await tour.select(inline, { label: current === 'COMPLETED' ? 'Review' : 'Completed' });
    await sleep(1300);
    await tour.select(sel.nth(1), { label: 'All Statuses' });
    await sleep(500);
    await tour.click(page.getByRole('button', { name: 'View' }).first());
    await sleep(2800);
    await tour.click(page.locator('.modal-header button').first());
  }, { caption: SCENE_CAPTION('tasks') });

  // ===== APPROVALS =====
  await tour.scene('approve', async () => {
    await tour.nav('Project Folders');
    await tour.click(page.locator('a.dept-card', { hasText: 'Water Infrastructure Survey' }));
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(700);
    const approve = page.getByRole('button', { name: 'Approve', exact: true });
    await tour.click(approve.first()); await sleep(1500);
    await tour.click(approve.first()); await sleep(1200);
    await tour.click(page.getByRole('button', { name: 'View' }).first());
    await sleep(1800);
    const at = page.getByRole('button', { name: 'Approve ticket' });
    if (await at.count()) await tour.click(at.first());
    await sleep(900);
    await tour.click(page.getByRole('button', { name: 'Close' }).last());
  }, { caption: SCENE_CAPTION('approve') });

  // ===== REPORTS + AGENT ACTIVITY =====
  await tour.scene('reports', async () => {
    await tour.nav('Reports');
    await tour.moveTo({ x: 900, y: 320 }, 700);
    await tour.zoom(1.1, 1000, 320, 2000); await sleep(2200); await tour.zoomReset(1000); await sleep(1100);
    await tour.scroll(520, 10); await sleep(1600);
    await tour.nav('Dashboard');
    await tour.scroll(1800, 14); await sleep(300);
    await tour.click(page.getByRole('link', { name: 'View activity' }).first());
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(1600);
    await tour.scroll(520, 10); await sleep(1500);
  }, { caption: SCENE_CAPTION('reports') });

  // ===== CHAPTER 3 =====
  await tour.scene('ch3', async () => {
    await tour.card(chapterCard('03', 'Super admins and the <em>data pipeline</em>', 'Isolated teams, cross-team analytics, and a read-only export API for AI.'));
    await sleep(600);
    await switchUser(SUPER, '/super');
  }, { caption: null, tail: 0.3 });

  // ===== SUPER OVERVIEW + IMPERSONATION =====
  await tour.scene('super', async () => {
    await tour.cardOff();
    await tour.moveTo({ x: 900, y: 280 }, 800);
    await tour.zoom(1.1, 1080, 300, 2200); await sleep(2400); await tour.zoomReset(1200); await sleep(1300);
    await tour.scroll(420, 10);
    await tour.type(page.locator('input.input').first(), 'neurix', 80);
    await sleep(700);
    const row = page.locator('table tbody tr', { hasText: 'Neurix Demo' }).first();
    await tour.click(row.getByRole('button', { name: 'Enter team' }));
    await page.waitForURL(/\/admin/, { timeout: 15000 });
    await page.waitForLoadState('networkidle').catch(() => {});
    await sleep(900);
    await tour.ring(page.getByRole('status').filter({ hasText: 'You are viewing' }).first(), 2200);
    await tour.scroll(420, 8); await sleep(1100); await tour.scroll(-420, 8);
    await tour.click(page.getByRole('button', { name: 'Exit team' }));
    await page.waitForURL(/\/super/, { timeout: 15000 });
    await sleep(800);
  }, { caption: SCENE_CAPTION('super') });

  // ===== PROJECT ANALYTICS + DATA EXPLORER =====
  await tour.scene('explorer', async () => {
    await tour.nav('Project analytics');
    await sleep(400);
    await tour.select(page.locator('select.input').first(), { label: 'Neurix Demo' });
    await sleep(800);
    await tour.hover(page.locator('table tbody tr').first(), 700); await sleep(900);
    await tour.nav('Data explorer');
    await sleep(500);
    await tour.select(page.locator('select.input').nth(0), { label: 'Neurix Demo' });
    await sleep(700);
    await tour.type(page.getByPlaceholder('Search title, content or website…'), 'desalination', 60);
    await page.keyboard.press('Enter');
    await sleep(1300);
    await tour.click(page.locator('table tbody tr').first());
    await sleep(2200);
    await tour.scroll(420, 8); await sleep(800);
  }, { caption: SCENE_CAPTION('explorer') });

  // ===== DATASET + API TOKENS =====
  await tour.scene('pipeline', async () => {
    await tour.nav('Server dataset');
    await sleep(700);
    await tour.click(page.getByRole('button', { name: 'Publish new data' }));
    await page.getByText(/Published \d+ new/).waitFor({ timeout: 120000 }).catch(() => {});
    await sleep(2200);
    await tour.nav('API tokens');
    await tour.click(page.getByRole('button', { name: 'Create token' }));
    const m = page.locator('.modal').last();
    await m.waitFor({ timeout: 10000 });
    await tour.type(m.getByPlaceholder('e.g. AI ingest job'), 'AI ingest job', 48);
    await tour.click(m.getByRole('button', { name: '90 days' }));
    await tour.click(m.getByRole('button', { name: 'Create token' }));
    const reveal = page.locator('#api-token-plaintext');
    await reveal.waitFor({ timeout: 15000 });
    await sleep(500);
    await tour.ring(reveal, 1800);
    await tour.click(page.getByRole('button', { name: 'Copy', exact: true }));
    await sleep(1500);
    await tour.hover(page.locator('pre').first(), 600);
    await sleep(1400);
    await tour.click(page.getByRole('button', { name: 'Close', exact: true }).last());
  }, { caption: SCENE_CAPTION('pipeline') });

  // ===== PLATFORM + OUTRO =====
  await tour.scene('platform', async () => {
    await tour.caption(null);
    await tour.card(platformCard());
  }, { caption: null, tail: 0.5 });
  await tour.scene('outro', async () => {
    await tour.card(outroCard());
  }, { caption: null, tail: 4.5 });

  tour.dump(DRY ? 'timeline_dry.json' : 'timeline.json');
  const video = page.video();
  await ctx.close();
  if (video) {
    const p = await video.path();
    fs.copyFileSync(p, 'rec/tour.webm');
    console.log('recorded', p, '->', 'rec/tour.webm', (fs.statSync('rec/tour.webm').size / 1e6).toFixed(1), 'MB; length', tour.now().toFixed(1), 's');
  }
  await browser.close();
  await cleanup();
}

const CAPTIONS = Object.fromEntries((await import('./narration.mjs')).SCENES.map(s => [s.id, s.caption]));
function SCENE_CAPTION(id) { return CAPTIONS[id]; }

main().catch(e => { console.error(e); process.exit(1); });

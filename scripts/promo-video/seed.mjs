// Seeds an isolated "Neurix Demo" team with realistic data for the promo video.
// Everything lives inside the new team; existing teams are untouched.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const BASE = 'http://localhost:8082';
const OUT = 'seed_state.json';
const state = fs.existsSync(OUT) ? JSON.parse(fs.readFileSync(OUT, 'utf8')) : {};
const save = () => fs.writeFileSync(OUT, JSON.stringify(state, null, 2));

class Session {
  constructor() { this.cookie = ''; this.token = ''; this.headers = {}; }
  async login(username, password) {
    const r = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }) });
    if (!r.ok) throw new Error(`login ${username} failed: ${r.status} ${await r.text()}`);
    const sc = r.headers.getSetCookie?.() || [];
    this.cookie = sc.map(c => c.split(';')[0]).join('; ');
    const j = await r.json(); this.token = j.token; return j.user;
  }
  async req(method, url, body, extra = {}) {
    const h = { Cookie: this.cookie, Authorization: 'Bearer ' + this.token, 'Accept-Language': 'en', ...this.headers, ...(extra.headers || {}) };
    let payload = body;
    if (body && !(body instanceof FormData)) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
    const r = await fetch(BASE + url, { method, headers: h, body: payload });
    const text = await r.text();
    let json = null; try { json = text ? JSON.parse(text) : null; } catch { json = text; }
    if (!r.ok && !extra.allow?.includes(r.status)) throw new Error(`${method} ${url} -> ${r.status}: ${text.slice(0, 300)}`);
    return { status: r.status, json };
  }
  get(u, x) { return this.req('GET', u, null, x); }
  post(u, b, x) { return this.req('POST', u, b, x); }
  patch(u, b, x) { return this.req('PATCH', u, b, x); }
  put(u, b, x) { return this.req('PUT', u, b, x); }
}

const ADMIN = { username: 'demo.admin', password: 'DemoAdmin#2026', displayName: 'Sarah Mitchell', email: 'sarah.mitchell@neurix.demo' };
const AGENT_PW = 'Agent#2026';
const AGENTS = [
  { username: 'omar.hassan', displayName: 'Omar Hassan', email: 'omar.hassan@neurix.demo', phone: '+20 100 555 0101' },
  { username: 'layla.ahmed', displayName: 'Layla Ahmed', email: 'layla.ahmed@neurix.demo', phone: '+20 100 555 0102' },
  { username: 'youssef.ali', displayName: 'Youssef Ali', email: 'youssef.ali@neurix.demo', phone: '+20 100 555 0103' },
  { username: 'nour.ibrahim', displayName: 'Nour Ibrahim', email: 'nour.ibrahim@neurix.demo', phone: '+20 100 555 0104' },
  { username: 'karim.fathy', displayName: 'Karim Fathy', email: 'karim.fathy@neurix.demo', phone: '+20 100 555 0105' },
  { username: 'mariam.said', displayName: 'Mariam Said', email: 'mariam.said@neurix.demo', phone: '+20 100 555 0106' },
];

const PROJECTS = [
  { key: 'archive', name: 'Industrial Heritage Archive', subtitle: 'Digitising the textile-mill records', startDate: '2026-07-01', endDate: '2026-11-30', progress: 62, status: 'ON_TRACK',
    departments: [
      { key: 'records', name: 'Historical Records', subs: [{ key: 'ledgers', name: 'Mill Ledgers' }, { key: 'letters', name: 'Correspondence' }] },
      { key: 'maps', name: 'Photographs & Maps', subs: [{ key: 'survey', name: 'Survey Maps' }] },
    ] },
  { key: 'energy', name: 'Renewable Energy Handbook', subtitle: 'Solar and wind reference library', startDate: '2026-08-01', endDate: '2026-10-15', progress: 38, status: 'ON_TRACK',
    departments: [
      { key: 'solar', name: 'Solar Systems', subs: [{ key: 'datasheets', name: 'Panel Datasheets' }, { key: 'install', name: 'Installation Guides' }] },
      { key: 'wind', name: 'Wind Power', subs: [{ key: 'turbines', name: 'Turbine Specifications' }] },
    ] },
  { key: 'water', name: 'Water Infrastructure Survey', subtitle: 'Desalination and irrigation reports', startDate: '2026-06-15', endDate: '2026-09-01', progress: 85, status: 'DELAYED',
    departments: [
      { key: 'desal', name: 'Desalination', subs: [{ key: 'plants', name: 'Plant Reports' }] },
      { key: 'irrig', name: 'Irrigation Networks', subs: [{ key: 'canals', name: 'Canal Surveys' }] },
    ] },
];

const FIELDS = {
  ledgers: [
    { fieldKey: 'priority', label: 'Priority', type: 'SELECT', required: true, displayOrder: 1, options: 'Low,Medium,High' },
    { fieldKey: 'source_year', label: 'Source year', type: 'NUMBER', required: true, displayOrder: 2, placeholder: 'e.g. 1934' },
    { fieldKey: 'archive_ref', label: 'Archive reference', type: 'TEXT', required: false, displayOrder: 3, placeholder: 'e.g. TM-1934-017' },
  ],
  datasheets: [
    { fieldKey: 'manufacturer', label: 'Manufacturer', type: 'TEXT', required: true, displayOrder: 1, placeholder: 'e.g. SunPower' },
    { fieldKey: 'efficiency', label: 'Efficiency (%)', type: 'NUMBER', required: false, displayOrder: 2, placeholder: '22.5' },
    { fieldKey: 'datasheet_url', label: 'Datasheet URL', type: 'URL', required: false, displayOrder: 3, placeholder: 'https://' },
  ],
  plants: [
    { fieldKey: 'report_date', label: 'Report date', type: 'DATE', required: true, displayOrder: 1 },
    { fieldKey: 'capacity_m3', label: 'Capacity (m³/day)', type: 'NUMBER', required: false, displayOrder: 2, placeholder: '50000' },
    { fieldKey: 'inspector_email', label: 'Inspector email', type: 'EMAIL', required: false, displayOrder: 3, placeholder: 'name@agency.gov' },
  ],
};

// ---------- article corpus ----------
const P = {
  ledgers: [
    ['Wage Ledger of the Delta Spinning Mill, 1934', 'The 1934 wage ledger lists 412 employees across three shifts, with weekly pay recorded in piastres. Overtime entries increase sharply in October, matching the seasonal cotton harvest.\n\nMarginal notes by the foreman record two machinery breakdowns and the hiring of a night-shift supervisor. The ledger is bound in cloth and the last twelve pages show water damage.', { priority: 'High', source_year: '1934', archive_ref: 'TM-1934-017' }],
    ['Raw Cotton Purchase Register, 1936', 'Purchases of raw cotton are recorded by supplier, bale count and grade. The register shows a shift from local suppliers to the Kafr El-Zayat cooperative in the second half of the year.\n\nPrices per qantar are quoted in Egyptian pounds and cross-referenced with delivery receipts filed in the same box.', { priority: 'Medium', source_year: '1936', archive_ref: 'TM-1936-004' }],
    ['Export Sales Journal, 1938–1939', 'The journal tracks shipments of finished cloth to Alexandria, Marseille and Trieste. Each entry lists the vessel, the bolt count and the insurance premium paid.\n\nA summary sheet pasted inside the back cover totals the year at 1.2 million metres of cloth, the highest figure in the mill\'s history.', { priority: 'High', source_year: '1938', archive_ref: 'TM-1938-011' }],
    ['Maintenance Cost Book, 1941', 'Wartime shortages appear clearly in this cost book: spare parts are listed as "unavailable" for fourteen weeks and repairs are improvised from scrap.\n\nThe book also records the installation of blackout screens and the purchase of a diesel generator.', { priority: 'Low', source_year: '1941', archive_ref: 'TM-1941-002' }],
    ['Dye House Inventory, 1929', 'An inventory of dye stock, vats and boilers taken at the end of 1929. Indigo and alizarin stocks dominate; synthetic dyes appear for the first time in the December count.', { priority: 'Medium', source_year: '1929', archive_ref: 'TM-1929-008' }],
  ],
  letters: [
    ['Letter from the Mill Director to the Ministry of Commerce', 'A three-page typed letter requesting tariff relief on imported spinning machinery. The director argues that domestic output would double within two years if the duty were suspended.\n\nThe reply, attached, grants a partial exemption for machinery ordered before June 1933.'],
    ['Correspondence with Manchester Equipment Suppliers', 'A bundle of twenty letters negotiating the purchase of ring-spinning frames. The exchange covers pricing, shipping via Liverpool and the training of two local mechanics in England.'],
    ['Workers\' Petition of 1947', 'A handwritten petition signed by 218 workers requesting a half-day on Fridays and an improved canteen. The management response, dated two weeks later, agrees to the canteen but defers the schedule change.'],
    ['Insurance Claim Following the 1952 Warehouse Fire', 'Claim forms, an assessor\'s report and photographs describing the loss of 3,400 bales of cotton. The assessor attributes the fire to a faulty electrical panel in the north warehouse.'],
  ],
  survey: [
    ['Cadastral Map of the Mill District, 1925', 'A hand-coloured cadastral map showing the mill compound, the rail spur and the workers\' housing blocks. Plot numbers match the land register held by the provincial archive.'],
    ['Aerial Photograph of the Riverside Works, 1948', 'A vertical aerial photograph taken by the survey department. The image shows the extension of the weaving shed and the newly built water tower.'],
    ['Drainage Plan for the Factory Estate', 'Engineering drawing of the estate drainage system with pipe diameters, gradients and the outfall into the irrigation canal. Annotated in pencil with later repairs.'],
    ['Panoramic View of the Spinning Hall, 1955', 'A wide-format photograph of the spinning hall showing 140 ring frames in operation. Useful for dating machinery visible in other collections.'],
  ],
  datasheets: [
    ['Monocrystalline 450 W Panel Datasheet', 'Electrical characteristics at standard test conditions: 450 W maximum power, 41.5 V at maximum power point and 10.85 A. Temperature coefficient of power is −0.34 % per degree Celsius.\n\nThe datasheet includes mechanical load ratings of 5,400 Pa front and 2,400 Pa rear, and a 25-year linear performance warranty.', { manufacturer: 'SunPower', efficiency: '22.8', datasheet_url: 'https://example.com/datasheets/mono-450' }],
    ['Bifacial 540 W Module Specification', 'A bifacial module with a transparent backsheet delivering up to 20 % additional yield from rear-side irradiance on reflective surfaces.\n\nThe specification lists a bifaciality factor of 70 % and a maximum system voltage of 1,500 V.', { manufacturer: 'Longi', efficiency: '21.3', datasheet_url: 'https://example.com/datasheets/bifacial-540' }],
    ['Thin-Film CdTe Panel Technical Sheet', 'Cadmium-telluride thin-film modules with a lower temperature coefficient than crystalline silicon, well suited to hot desert climates.\n\nRated at 420 W with a 19.0 % efficiency and a spectral response that favours diffuse light.', { manufacturer: 'First Solar', efficiency: '19.0', datasheet_url: 'https://example.com/datasheets/cdte-420' }],
    ['Half-Cut Cell 400 W Module Data', 'Half-cut cell architecture reduces resistive losses and improves shade tolerance. The module is rated at 400 W with a 20.4 % efficiency.', { manufacturer: 'Jinko', efficiency: '20.4', datasheet_url: 'https://example.com/datasheets/halfcut-400' }],
  ],
  install: [
    ['Rooftop Mounting Procedure for Flat Roofs', 'Step-by-step procedure for ballasted mounting on flat concrete roofs: load calculation, membrane protection, rail alignment and torque values for module clamps.\n\nThe guide recommends a 10-degree tilt for the regional latitude and a minimum row spacing of 1.2 metres to avoid inter-row shading.'],
    ['String Inverter Commissioning Checklist', 'A commissioning checklist covering DC string polarity, insulation resistance, grid parameter settings and the final export-limit configuration.'],
    ['Grounding and Lightning Protection Guide', 'Requirements for equipotential bonding of module frames and rails, surge protection devices on both DC and AC sides, and earth-electrode resistance limits.'],
  ],
  turbines: [
    ['3.6 MW Onshore Turbine Specification', 'A three-blade upwind turbine with a 130-metre rotor diameter and a hub height of 110 metres. Cut-in wind speed is 3 m/s and rated power is reached at 11.5 m/s.\n\nThe specification includes the power curve, noise emission levels and the foundation load table.'],
    ['Small Wind Turbine Siting Report', 'Site assessment for a 50 kW turbine near an agricultural cooperative. Twelve months of anemometer data give an average wind speed of 6.1 m/s at 30 metres.'],
    ['Gearbox Maintenance Schedule', 'Recommended inspection intervals for the main gearbox, oil sampling frequency and the replacement criteria for the high-speed shaft bearings.'],
  ],
  plants: [
    ['Reverse Osmosis Plant Inspection – Ras Gharib', 'Annual inspection of the 50,000 m³/day reverse osmosis plant. Membrane recovery has fallen to 42 % and the report recommends replacing the first-pass elements before the summer peak.\n\nEnergy consumption averaged 3.6 kWh per cubic metre, within the design envelope.', { report_date: '2026-08-12', capacity_m3: '50000', inspector_email: 'h.saleh@water-agency.gov' }],
    ['Multi-Stage Flash Unit Performance Review', 'Performance review of a thermal desalination unit coupled to the coastal power station. Distillate output remains stable but scaling in the brine heater has increased cleaning frequency.', { report_date: '2026-07-29', capacity_m3: '32000', inspector_email: 'm.adel@water-agency.gov' }],
    ['Brine Outfall Environmental Monitoring', 'Quarterly salinity and temperature measurements at the diffuser outfall. Salinity returns to background levels within 180 metres of the discharge point.', { report_date: '2026-08-20', capacity_m3: '50000', inspector_email: 'r.nabil@water-agency.gov' }],
    ['Intake Screen Replacement Report', 'Report on the replacement of the seawater intake screens and the impact on pre-treatment filter run times.', { report_date: '2026-06-30', capacity_m3: '18000', inspector_email: 'h.saleh@water-agency.gov' }],
  ],
  canals: [
    ['Canal Lining Survey – Western Branch', 'Survey of 14 kilometres of the western branch canal. Concrete lining is cracked along 2.3 kilometres and seepage losses are estimated at 11 % of flow.\n\nThe survey recommends geomembrane relining of the worst sections before the next irrigation season.'],
    ['Pump Station Efficiency Audit', 'Audit of six pumping stations along the main feeder. Two stations operate below 60 % efficiency due to worn impellers and are scheduled for refurbishment.'],
    ['Drip Irrigation Pilot Results', 'Results of a 200-hectare drip irrigation pilot: water use fell by 38 % and yields rose by 12 % compared with flood irrigation on neighbouring plots.'],
    ['Gate Automation Feasibility Study', 'Feasibility of automating twelve regulator gates with solar-powered actuators and remote telemetry.'],
  ],
};

const SITES = [['Egyptian National Archives', 'https://www.nationalarchives.gov.eg'], ['IRENA Publications', 'https://www.irena.org/publications'], ['Water Research Portal', 'https://example.org/water-research'], ['Textile History Society', 'https://example.org/textile-history'], ['PV Magazine', 'https://www.pv-magazine.com']];

async function main() {
  // ---- super admin: team + admin ----
  const sup = new Session();
  await sup.login('superadmin', 'superadmin123');
  let team = (await sup.get('/api/super/teams')).json.find(t => t.slug === 'neurix-demo');
  if (!team) {
    team = (await sup.post('/api/super/teams', { slug: 'neurix-demo', name: 'Neurix Demo', description: 'Product demo workspace — safe to delete.', color: '#0f5fd1' })).json;
    console.log('team created', team.id);
  } else console.log('team exists', team.id);
  state.teamId = team.id; save();
  const members = (await sup.get(`/api/super/teams/${team.id}/members`)).json;
  if (!members.some(m => m.username === ADMIN.username)) {
    await sup.post(`/api/super/teams/${team.id}/admins`, ADMIN);
    console.log('team admin created');
  }

  // ---- team admin ----
  const adm = new Session();
  const admUser = await adm.login(ADMIN.username, ADMIN.password);
  state.adminId = admUser.id;

  // users
  const existing = (await adm.get('/api/admin/users')).json;
  state.agents = {};
  for (const a of AGENTS) {
    let u = existing.find(e => e.username === a.username);
    if (!u) u = (await adm.post('/api/admin/users', { ...a, password: AGENT_PW, role: 'USER' })).json;
    state.agents[a.username] = u.id;
  }
  save(); console.log('agents', state.agents);

  // projects -> departments -> subcategories -> fields
  const projList = (await adm.get('/api/admin/projects')).json;
  const deptList = (await adm.get('/api/admin/departments')).json;
  const subList = (await adm.get('/api/admin/subcategories')).json;
  state.projects = state.projects || {}; state.depts = state.depts || {}; state.subs = state.subs || {};
  const agentIds = Object.values(state.agents);
  for (const p of PROJECTS) {
    let pr = projList.find(x => x.name === p.name);
    if (!pr) pr = (await adm.post('/api/admin/projects', { name: p.name, subtitle: p.subtitle, departmentIds: [], memberIds: agentIds, startDate: p.startDate, endDate: p.endDate, progress: p.progress, status: p.status })).json;
    state.projects[p.key] = pr.id;
    const deptIds = [];
    for (const d of p.departments) {
      let dp = deptList.find(x => x.name === d.name && x.projectId === pr.id);
      if (!dp) dp = (await adm.post('/api/admin/departments', { name: d.name, projectId: pr.id, active: true })).json;
      state.depts[d.key] = dp.id; deptIds.push(dp.id);
      for (const s of d.subs) {
        let sb = subList.find(x => x.name === s.name && x.departmentId === dp.id);
        if (!sb) sb = (await adm.post('/api/admin/subcategories', { departmentId: dp.id, name: s.name, active: true })).json;
        state.subs[s.key] = sb.id;
      }
    }
    await adm.patch(`/api/admin/projects/${pr.id}`, { name: p.name, subtitle: p.subtitle, departmentIds: deptIds, memberIds: agentIds, startDate: p.startDate, endDate: p.endDate, progress: p.progress, status: p.status });
  }
  save(); console.log('projects', state.projects, 'depts', state.depts, 'subs', state.subs);

  const fieldList = (await adm.get('/api/admin/fields')).json;
  for (const [subKey, fields] of Object.entries(FIELDS)) {
    for (const f of fields) {
      const exists = (Array.isArray(fieldList) ? fieldList : fieldList.items || []).some(x => x.fieldKey === f.fieldKey && x.subcategoryId === state.subs[subKey]);
      if (!exists) await adm.post('/api/admin/fields', { ...f, subcategoryId: state.subs[subKey], active: true });
    }
  }
  console.log('fields ok');

  // ---- tickets ----
  if (!state.tickets) {
    state.tickets = [];
    const subToDept = {}; const subToProj = {};
    for (const p of PROJECTS) for (const d of p.departments) for (const s of d.subs) { subToDept[s.key] = d.key; subToProj[s.key] = p.key; }
    const agentNames = AGENTS.map(a => a.username);
    // weights: some agents more productive
    const weights = { 'omar.hassan': 5, 'layla.ahmed': 4, 'youssef.ali': 3, 'nour.ibrahim': 3, 'karim.fathy': 2, 'mariam.said': 2 };
    const pick = () => { const bag = []; for (const [k, w] of Object.entries(weights)) for (let i = 0; i < w; i++) bag.push(k); return bag[Math.floor(Math.random() * bag.length)]; };
    let si = 0;
    for (const [subKey, arts] of Object.entries(P)) {
      for (const [title, content, custom] of arts) {
        const who = pick();
        const sess = new Session(); await sess.login(who, AGENT_PW);
        const site = SITES[si++ % SITES.length];
        const body = {
          departmentId: state.depts[subToDept[subKey]], subcategoryId: state.subs[subKey], projectId: state.projects[subToProj[subKey]],
          customValues: custom || {},
          articles: [{ title, content, websiteName: site[0], websiteLink: site[1], resources: si % 2 ? [{ name: 'Original scan folder', url: 'https://drive.example.com/folder/' + (1000 + si) }] : [] }],
        };
        const r = await sess.post('/api/user/tickets/bulk', body);
        const created = r.json.tickets || r.json.items || r.json;
        const t = Array.isArray(created) ? created[0] : created;
        state.tickets.push({ id: t.id, who, subKey, proj: subToProj[subKey], title });
        process.stdout.write('.');
      }
    }
    save(); console.log('\ntickets', state.tickets.length);
  }

  // ---- attachments: each file once per project ----
  if (!state.attached) {
    const files = fs.readdirSync('docs').map(f => path.join('docs', f));
    const byProj = {};
    for (const t of state.tickets) (byProj[t.proj] ||= []).push(t);
    for (const [proj, list] of Object.entries(byProj)) {
      let fi = 0;
      for (const t of list.slice(0, files.length)) {
        const file = files[fi++];
        const sess = new Session(); await sess.login(t.who, AGENT_PW);
        const fd = new FormData();
        fd.append('file', new Blob([fs.readFileSync(file)], { type: file.endsWith('.pdf') ? 'application/pdf' : 'image/png' }), path.basename(file));
        fd.append('name', path.basename(file, path.extname(file)).replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase()));
        const r = await sess.post(`/api/tickets/${t.id}/documents`, fd, { allow: [409, 400] });
        process.stdout.write(r.status === 200 || r.status === 201 ? '+' : `(${r.status})`);
      }
    }
    state.attached = true; save(); console.log('\nattachments done');
  }

  // ---- quick-upload a few pending files into the Water folder (REVIEW status) ----
  if (!state.quick) {
    const sess = new Session(); await sess.login('nour.ibrahim', AGENT_PW);
    const fd = new FormData();
    for (const f of ['docs/water_scan.png', 'docs/history_scan.png']) {
      fd.append('files', new Blob([fs.readFileSync(f)], { type: 'image/png' }), path.basename(f).replace('_scan', '_page_scan_v2'));
    }
    fd.append('titles', 'Desalination plant – scanned page 12');
    fd.append('titles', 'Delta mills – scanned page 3');
    fd.append('departmentId', String(state.depts.desal));
    const r = await sess.post(`/api/project-folders/${state.projects.water}/quick-upload`, fd, { allow: [409, 400] });
    console.log('quick upload', r.status, JSON.stringify(r.json).slice(0, 200));
    state.quick = true; save();
  }

  // ---- statuses + backdating ----
  if (!state.dated) {
    const ids = state.tickets.map(t => t.id);
    // Spread over the last 30 days with an upward trend; keep 4 for today.
    const now = new Date();
    const stamps = [];
    for (let i = 0; i < ids.length; i++) {
      let daysAgo;
      if (i < 4) daysAgo = 0; else if (i < 8) daysAgo = 1; else daysAgo = 2 + Math.floor(Math.pow(Math.random(), 1.6) * 27);
      const d = new Date(now.getTime() - daysAgo * 86400000);
      d.setHours(8 + Math.floor(Math.random() * 9), Math.floor(Math.random() * 60), 0, 0);
      if (daysAgo === 0 && d > now) d.setTime(now.getTime() - 3600000 * (1 + Math.random() * 3));
      stamps.push(d.toISOString());
    }
    const sql = ids.map((id, i) => `UPDATE tickets SET submitted_at = '${stamps[i]}' WHERE id = ${id} AND team_id = ${state.teamId};`).join('\n');
    const r = spawnSync('docker', ['exec', '-i', 'dems-postgres', 'psql', '-U', 'daleel', '-d', 'dataentry', '-q'], { input: sql, encoding: 'utf8' });
    console.log('backdate', r.status, r.stderr.slice(0, 200));
    // statuses: oldest mostly completed, recent ones mixed
    for (let i = 0; i < ids.length; i++) {
      const rnd = Math.random();
      let status = 'COMPLETED';
      if (i < 4) status = rnd < 0.5 ? 'REVIEW' : 'IN_PROGRESS';
      else if (i < 8) status = rnd < 0.4 ? 'REVIEW' : rnd < 0.6 ? 'IN_PROGRESS' : 'COMPLETED';
      else status = rnd < 0.15 ? 'REVIEW' : rnd < 0.25 ? 'IN_PROGRESS' : 'COMPLETED';
      if (status !== 'COMPLETED') await adm.patch(`/api/admin/tickets/${ids[i]}/status`, { status });
      else await adm.post(`/api/admin/tickets/${ids[i]}/approve`);
    }
    state.dated = true; save(); console.log('statuses done');
  }

  const stats = (await adm.get('/api/admin/stats')).json;
  console.log('admin stats', stats);
}
main().catch(e => { console.error(e); process.exit(1); });

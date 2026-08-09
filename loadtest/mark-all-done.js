#!/usr/bin/env node
// Marks every task in the given ClickUp list as done ("Complete" or "Closed" status,
// whichever the list uses).  Runs sequentially with a small delay to stay under 100 req/min.

const https = require('https');

const TOKEN = process.env.CU_TOKEN;
const LIST_ID = process.env.CU_LIST_ID;
if (!TOKEN || !LIST_ID) { console.error('Missing CU_TOKEN / CU_LIST_ID'); process.exit(1); }

function req(method, path, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : '';
    const options = {
      hostname: 'api.clickup.com', path, method,
      headers: {
        'Authorization': TOKEN,
        'Content-Type': 'application/json',
      },
    };
    if (data) options.headers['Content-Length'] = Buffer.byteLength(data);
    const r = https.request(options, (res) => {
      let buf = '';
      res.on('data', (c) => buf += c);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, body: JSON.parse(buf) }); }
        catch { resolve({ status: res.statusCode, body: buf }); }
      });
    });
    r.on('error', reject);
    if (data) r.write(data);
    r.end();
  });
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  // 1. Discover the list's "closed / done" status.
  const listInfo = await req('GET', `/api/v2/list/${LIST_ID}`);
  const statuses = listInfo.body.statuses || [];
  // Prefer the status flagged as `type=closed`, otherwise fall back to the last one in order.
  const doneStatus = statuses.find(s => s.type === 'closed') || statuses[statuses.length - 1];
  if (!doneStatus) { console.error('Could not find any statuses on list'); process.exit(1); }
  console.log(`Done status resolved to: "${doneStatus.status}"`);

  // 2. Page through all tasks.
  let page = 0, all = [];
  while (true) {
    const r = await req('GET', `/api/v2/list/${LIST_ID}/task?page=${page}&subtasks=true&include_closed=true`);
    const tasks = r.body.tasks || [];
    if (!tasks.length) break;
    all = all.concat(tasks);
    page++;
    if (tasks.length < 100) break; // last page
  }
  console.log(`Found ${all.length} tasks`);

  // 3. Update each to the done status (skip ones already there).
  let updated = 0, skipped = 0;
  for (let i = 0; i < all.length; i++) {
    const t = all[i];
    if (t.status && t.status.status === doneStatus.status) { skipped++; continue; }
    const u = await req('PUT', `/api/v2/task/${t.id}`, { status: doneStatus.status });
    if (u.status >= 200 && u.status < 300) {
      console.log(`[${i+1}/${all.length}] done  ${t.id}  ${t.name}`);
      updated++;
    } else {
      console.log(`[${i+1}/${all.length}] ERR ${u.status}  ${t.id}  ${JSON.stringify(u.body).slice(0,150)}`);
    }
    await sleep(150);
  }
  console.log(`\n=== updated ${updated}, already done ${skipped}, total ${all.length} ===`);
})();

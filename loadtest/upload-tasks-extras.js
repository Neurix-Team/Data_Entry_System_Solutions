#!/usr/bin/env node
// Appends the 19 small / library-specific tasks I missed on the first pass.
// Uses the same script pattern as upload-tasks.js — token/list/assignee via env.

const https = require('https');

const TOKEN = process.env.CU_TOKEN;
const LIST_ID = process.env.CU_LIST_ID;
const ASSIGNEE = Number(process.env.CU_ASSIGNEE || 0);
if (!TOKEN || !LIST_ID || !ASSIGNEE) {
  console.error('Missing CU_TOKEN / CU_LIST_ID / CU_ASSIGNEE'); process.exit(1);
}

const TASKS = [
  // ---------- PDF & document library integrations ----------
  { g: 'PDF Libraries', n: '67. opendataloader-pdf Integration',
    d: '**Backend** — `org.opendataloader:opendataloader-pdf-core:2.5.0`, wired in `PdfExtractionService`. The library writes its output to a folder rather than returning it, so each request runs against an isolated temp folder under `app.pdf.output-dir` (default: `${java.io.tmpdir}/dataentry-pdf`); the generated markdown/text is read back and the folder deleted. `@PreDestroy` cleans up any leftover temp trees. Configurable `app.pdf.max-chars` (default 200,000) caps the returned text.' },

  { g: 'PDF Libraries', n: '68. Apache PDFBox Integration',
    d: '**Backend** — `org.apache.pdfbox:pdfbox:3.0.3`, used by `PdfOcrService` to render each PDF page to a rasterized image so Tesseract can OCR scanned Arabic pages that carry no embedded text layer. Loaded reflectively so a missing native dep doesn\'t break the whole app — falls back gracefully to native text extraction only.' },

  { g: 'PDF Libraries', n: '69. Tesseract / Tess4J Integration',
    d: '**Backend** — `net.sourceforge.tess4j:tess4j:5.13.0` (JNA binding to native Tesseract). Configured via `app.ocr.tessdata-path` (default `/usr/share/tesseract-ocr/4.00/tessdata/`) and `app.ocr.languages` (default `ara+eng`). Excluded `slf4j-log4j12` / `slf4j-simple` to avoid multiple bindings. Used for both standalone image OCR and PDF page fallback. Wraps `UnsatisfiedLinkError` in a 503 so a missing native lib returns a readable error instead of a boot crash.' },

  { g: 'PDF Libraries', n: '70. Apache Tika Integration',
    d: '**Backend** — `org.apache.tika:tika-core` + `tika-parsers-standard-package:2.9.2`. Powers extraction for everything that isn\'t a PDF or image: Word (doc/docx/docm), Excel (xls/xlsx/xlsm/xlsb/csv), PowerPoint (ppt/pptx/pptm), OpenDocument (odt/ods/odp), RTF, EPUB, HTML/XML/JSON, plain text/markdown. Uses `AutoDetectParser` for format detection and `Tika.detect()` for magic-byte cross-check before parsing. Bounded body handler with `Math.max(maxChars * 4, 1_000_000)` char cap; falls back to `tika.parseToString()` when the primary handler bails on very large docs.' },

  // ---------- Password + time abstractions ----------
  { g: 'Foundation', n: '71. BCrypt Password Hashing',
    d: '**Backend** — `PasswordEncoder` bean in `SecurityConfig` is a `BCryptPasswordEncoder` (default work factor). Every password write (`UserService.create`, `UserService.update`, `DataSeeder` admin+agent seed) goes through `passwordEncoder.encode(...)`. `AuthService.login` matches with `passwordEncoder.matches(raw, hash)`. Plaintext passwords are never persisted or logged — the audit log records only `passwordChanged=true|false`.' },

  { g: 'Foundation', n: '72. Injectable Clock for Testability',
    d: '**Backend** — `AppConfig` exposes a `Clock` bean (`Clock.systemDefaultZone()`) that `ProjectService` and `DashboardService` inject instead of calling `Instant.now()` / `LocalDate.now()` directly. Tests can swap in `Clock.fixed(...)` so every date-based assertion (streaks, days-left, sparklines, "today" counters) is deterministic across runs.' },

  // ---------- Data + backfill ----------
  { g: 'Data Bootstrap', n: '73. Default Data Seeding',
    d: '**Backend** — `DataSeeder` (CommandLineRunner) runs on every boot when `app.seed.enabled=true`. Creates: default admin (username + password from env), sample `agent1` user, 5 departments (`Marketing`, `Sales`, `Content Review`, `Compliance`, `Research`), a `General` subcategory under each, `Blog` + `Social` under Marketing, `Editorial` + `Legal` under Content Review, and two example custom fields (`priority` SELECT + `reference_id` TEXT). Idempotent — only inserts when the row doesn\'t already exist. Emits a loud SECURITY warning if the admin password is still the default `admin123`.' },

  { g: 'Data Bootstrap', n: '74. Legacy Schema Backfill',
    d: '**Backend** — `DataSeeder.backfillLegacyRows` catches databases that predate the Subcategory feature. Any `custom_fields` or `tickets` row whose `subcategory_id` is `NULL` gets auto-assigned to a `General` subcategory under the appropriate department, via a direct `JdbcTemplate` update so no JPA cascade side-effects fire. Uses `safeCount` probes so it\'s safe to run against fresh schemas that don\'t have the legacy columns.' },

  // ---------- Frontend HTTP plumbing ----------
  { g: 'Frontend Plumbing', n: '75. Axios 401 Auto-Logout Hook',
    d: '**Frontend** — `client.ts` exposes `setUnauthorizedHandler(fn)` and installs a response interceptor that fires the handler on any 401. `AuthContext` registers a handler that clears the in-memory token and resets the user state, so an expired JWT anywhere in the app instantly kicks the user back to `/login` without a manual refresh.' },

  { g: 'Frontend Plumbing', n: '76. Axios Error Message Extraction Helper',
    d: '**Frontend** — `extractError(err, fallback)` in `client.ts` pulls the human-readable message out of a Spring `GlobalExceptionHandler` response, prefers a field-level `details` message when the backend attached validation info, falls back to `message`, then to the caller\'s fallback string. Used in every form on the UI so backend validation shows up inline instead of "Something went wrong".' },

  { g: 'Frontend Plumbing', n: '77. Vite Dev-Server API Proxy',
    d: '**Frontend** — `vite.config.ts` proxies `/api` to the local backend on port 8083 during dev so the SPA can hit `/api/...` without CORS or hardcoded hostnames. `API_BASE` in `client.ts` respects `VITE_API_BASE` for prod builds where nginx handles the proxy instead.' },

  // ---------- Container images & serving ----------
  { g: 'Container Images', n: '78. Multi-Stage Frontend Docker Build',
    d: '**Ops** — `frontend/Dockerfile` builds the Vite bundle in a `node:22-alpine` builder stage (installs deps, runs `npm run build`), then copies the `dist/` output into a slim `nginx:alpine` runtime stage. Result: no Node in the runtime image, ~30 MB final size.' },

  { g: 'Container Images', n: '79. Nginx SPA Fallback + API Proxy',
    d: '**Ops** — `frontend/nginx.conf` serves `index.html` for any path that doesn\'t match a real file (`try_files $uri $uri/ /index.html`) so React Router deep links work on refresh. `/api` is `proxy_pass`ed to the `backend` service on port 8080 with `X-Forwarded-*` headers so the backend\'s rate limiter and JWT filter see the real client IP.' },

  { g: 'Container Images', n: '80. Backend Docker Image',
    d: '**Ops** — `backend/Dockerfile` uses `eclipse-temurin:17-jre` as the base, copies the Maven-built fat jar, exposes 8080, runs as a non-root `app` user with a dedicated `/app/data` writable directory for uploads and extraction artifacts. Includes system Tesseract (`tesseract-ocr`, `tesseract-ocr-ara`, `tesseract-ocr-eng`) + fonts so OCR runs out of the box.' },

  // ---------- Repo hygiene ----------
  { g: 'Foundation', n: '81. .gitignore Hardening',
    d: 'Ignores generated build artifacts (`backend/target/`, `frontend/dist/`, `frontend/node_modules/`, `frontend/.vite/`, `tsconfig.tsbuildinfo`), OS junk (`.DS_Store`, `Thumbs.db`, `desktop.ini`), IDE metadata (`.idea/`, `.vscode/`, `*.iml`), and — critically — secrets: `.env`, `.env.*` (with `!.env.example` escape hatch), and private keys (`*.pem`, `*.key`, `*.p12`, `*.pfx`). Also excludes the Claude Code local scratch dir (`.claude/settings.local.json`, `.claude/plans/`, `.claude/logs/`).' },

  // ---------- Ops small stuff ----------
  { g: 'Container Images', n: '82. LibreTranslate Healthcheck',
    d: '**Ops** — `dems-libretranslate` service has a docker healthcheck `wget -qO- http://localhost:5000/languages` every 30s with a 120s `start_period` to cover the first-boot model download. Compose surfaces `unhealthy` states so operators can see when the translator hasn\'t finished warming up yet.' },

  // ---------- Small backend defenses ----------
  { g: 'Backend Defenses', n: '83. Audit Log Detail Truncation',
    d: '**Backend** — `AuditService.truncate` clamps every `details` string to 2000 chars before persisting so a caller with a huge title / long list of changed fields can\'t bloat the audit table. Combined with the 2000-char column length, this bounds the per-row size regardless of what the caller sends.' },

  { g: 'Backend Defenses', n: '84. Login Attempt Automatic Pruning',
    d: '**Backend** — `DatabaseLoginRateLimiter` runs an amortized cleanup roughly every 500 acquires: deletes any `login_attempts` rows older than `window + 60s`. The prune runs inside the same transaction as the current attempt and is wrapped in a `try/catch` so a prune failure never blocks a legitimate login. Keeps table size ≈ `unique_keys × max_attempts` in steady state.' },

  { g: 'Backend Defenses', n: '85. Pagination Clamp Helpers',
    d: '**Backend** — every paginated endpoint (`TicketController.listMine/listAll`, `AuditLogController.list`) runs incoming `page`/`size` through `Math.max(0, page)` and `Math.min(Math.max(size,1), 200)`. Attackers cannot request `size=1_000_000` and OOM the JVM, nor negative page numbers that would crash Spring Data\'s `PageRequest`.' },
];

function post(path, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = https.request({
      hostname: 'api.clickup.com',
      path, method: 'POST',
      headers: {
        'Authorization': TOKEN,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
      },
    }, (res) => {
      let buf = '';
      res.on('data', (c) => buf += c);
      res.on('end', () => {
        try { resolve({ status: res.statusCode, body: JSON.parse(buf) }); }
        catch { resolve({ status: res.statusCode, body: buf }); }
      });
    });
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  const results = [];
  for (let i = 0; i < TASKS.length; i++) {
    const t = TASKS[i];
    const payload = {
      name: t.n,
      markdown_description: t.d,
      assignees: [ASSIGNEE],
      tags: [t.g.toLowerCase().replace(/[^a-z0-9]+/g, '-')],
    };
    try {
      const r = await post(`/api/v2/list/${LIST_ID}/task`, payload);
      if (r.status >= 200 && r.status < 300 && r.body && r.body.id) {
        console.log(`[${i+1}/${TASKS.length}] ok  ${r.body.id}  ${t.n}`);
        results.push({ ok: true, id: r.body.id, url: r.body.url, name: t.n });
      } else {
        console.log(`[${i+1}/${TASKS.length}] ERR ${r.status}  ${t.n}  ${JSON.stringify(r.body).slice(0,200)}`);
        results.push({ ok: false, name: t.n, status: r.status, body: r.body });
      }
    } catch (e) {
      console.log(`[${i+1}/${TASKS.length}] EX  ${t.n}  ${e.message}`);
      results.push({ ok: false, name: t.n, error: e.message });
    }
    await sleep(200);
  }
  const okCount = results.filter(r => r.ok).length;
  console.log(`\n=== ${okCount}/${results.length} tasks created ===`);
})();

#!/usr/bin/env node
// Uploads the 66-task breakdown to the given ClickUp list, assigned to Neurix AI (uid 308443853).
// Runs sequentially with a small delay to stay well under ClickUp's 100 req/min rate limit.

const https = require('https');

const TOKEN = process.env.CU_TOKEN;
const LIST_ID = process.env.CU_LIST_ID;
const ASSIGNEE = Number(process.env.CU_ASSIGNEE || 0);
if (!TOKEN || !LIST_ID || !ASSIGNEE) {
  console.error('Missing CU_TOKEN / CU_LIST_ID / CU_ASSIGNEE'); process.exit(1);
}

// Task list — trimmed to what fits the ClickUp task shape: name + markdown_description.
// Grouped by area, ordered same as the breakdown message so ClickUp positions them naturally.
const TASKS = [
  // Foundation
  { g: 'Foundation', n: '1. Repository & Build Skeleton',
    d: 'Establish the monorepo layout: `backend/` (Maven, Spring Boot 3.3, Java 17) and `frontend/` (Vite, React 18, TypeScript). Add root `docker-compose.yml`, `.gitignore`, `.env` pattern, and README. Configure the frontend build to output static assets and the backend to package as an executable jar.' },
  { g: 'Foundation', n: '2. Multi-Container Deployment',
    d: 'Compose three services: `dems-backend` (Spring Boot on port 8083), `dems-frontend` (nginx serving the Vite build on port 8082), and `dems-libretranslate` (self-hosted translation on internal port 5000). Connect the backend to PostgreSQL, persist uploaded files in `dems-data`, and cache LibreTranslate models in a second volume. Fail fast if database credentials or `JWT_SECRET` are not provided.' },
  { g: 'Foundation', n: '3. Environment Configuration',
    d: 'Externalize every deployment-sensitive value through env vars with safe defaults: `JWT_SECRET`, `APP_CORS_ALLOWED_ORIGINS`, `APP_SEED_ADMIN_USERNAME/PASSWORD`, `APP_AUTH_COOKIE_SECURE`, `APP_UPLOADS_PER_USER_DAILY_BYTES`, `TRANSLATION_BASE_URL`, `TRANSLATION_API_KEY`, `APP_LOGIN_RATE_STORAGE`. Document each inline in `application.yml` and `docker-compose.yml`.' },
  { g: 'Foundation', n: '4. Database Layer',
    d: 'Define JPA entities for User, Department, Subcategory, CustomField, Project, Ticket, TicketFieldValue, AuditLog, LoginAttempt. Use PostgreSQL through the official JDBC driver and Hibernate dialect. Add indexes on audit-log lookups and login-attempt window queries.' },
  { g: 'Foundation', n: '5. Nginx Frontend Serving',
    d: '`frontend/Dockerfile` builds the Vite bundle in a Node stage and serves it from nginx with a custom `nginx.conf` that supports SPA fallback (all non-file paths route to `index.html`) and proxies `/api` to the backend service.' },

  // Auth & Session
  { g: 'Auth & Session', n: '6. Login API + JWT Issuance',
    d: '**Backend** — `POST /api/auth/login` validates credentials against BCrypt-hashed passwords, returns a signed HS512 JWT with subject/role/uid and its expiry. `JwtService` enforces a minimum 32-byte secret and rejects the built-in placeholder at boot. Configurable expiry (default 24h).' },
  { g: 'Auth & Session', n: '7. JWT Auth Filter',
    d: '**Backend** — `JwtAuthFilter` (extends `OncePerRequestFilter`) extracts the token from either the `Authorization: Bearer` header or the httpOnly session cookie, parses it, loads the user, and populates the Spring Security context. Silently rejects expired/invalid tokens.' },
  { g: 'Auth & Session', n: '8. Cookie-Based Session for Browsers',
    d: '**Backend** — `AuthController` sets `dems_auth` as `HttpOnly; Secure; SameSite=Lax; Path=/` on login, and clears it on `POST /api/auth/logout` with `Max-Age=0`. **Frontend** — `axios` is configured with `withCredentials: true`; the token is no longer stored in `localStorage`; `AuthContext` probes `/auth/me` on boot to hydrate the current user.' },
  { g: 'Auth & Session', n: '9. Login Page',
    d: '**Frontend** — `LoginPage.tsx` — username/password form, submits via `authApi.login`, shows inline errors from the backend, redirects by role (admins → `/admin`, users → `/dashboard`). Uses the same translated labels as the rest of the app.' },
  { g: 'Auth & Session', n: '10. Route Protection',
    d: '**Frontend** — `ProtectedRoute` component checks the auth context and optionally a required role. Unauthenticated users are sent to `/login`; wrong-role users are bounced to their own home. `App.tsx` wraps every non-login route.' },
  { g: 'Auth & Session', n: '11. Login Rate Limiting',
    d: '**Backend** — `LoginRateLimiter` interface with two swappable implementations: `InMemoryLoginRateLimiter` (per-instance, default) and `DatabaseLoginRateLimiter` (shared across replicas via `login_attempts` table with amortized pruning). Selected by `app.security.login-rate.storage=memory|database`. Cap: 10 attempts per 5 minutes per (IP + username). Returns 429 when exceeded.' },

  // User Management
  { g: 'User Management', n: '12. Users CRUD (Admin)',
    d: '**Backend** — `AdminUserController` + `UserService` — create/list/update/delete users. Passwords BCrypt-hashed. Username validated with a strict regex. Prevent self-deletion. Every write recorded to the audit log (password value never included, only a change flag).' },
  { g: 'User Management', n: '13. Admin Users Page',
    d: '**Frontend** — `AdminUsersPage.tsx` — table of users with create/edit modal (username, display name, email, phone, role, password, active). Modal reuses the shared `Modal` component. Row action to view a user\'s activity page.' },
  { g: 'User Management', n: '14. Self-Profile Endpoint',
    d: '**Backend** — `GET /api/auth/me` returns the currently authenticated user\'s basic profile. Used by the frontend to hydrate the auth context on page load / refresh.' },

  // Domain Modelling
  { g: 'Domain Modelling', n: '15. Departments CRUD',
    d: '**Backend** — `DepartmentController` + `DepartmentService` — create/list/update/deactivate; unique name enforced; deletion blocked when tickets exist. Every write audited. Public list endpoint (`/api/departments`) for the ticket-submit dropdown.' },
  { g: 'Domain Modelling', n: '16. Admin Departments Page + Detail Modal',
    d: '**Frontend** — `AdminDepartmentsPage.tsx` grid of departments with create/edit inline. `DepartmentDetailModal.tsx` drills into per-department stats: total tickets, active agents, status breakdown, 30-day sparkline, subcategory list.' },
  { g: 'Domain Modelling', n: '17. Subcategories CRUD',
    d: '**Backend** — `SubcategoryController` + `SubcategoryService` — CRUD scoped by department, unique-per-department name, deletion blocked when referenced by fields or tickets. Every write audited.' },
  { g: 'Domain Modelling', n: '18. Admin Subcategories Page',
    d: '**Frontend** — `AdminSubcategoriesPage.tsx` — list per department, add/edit/deactivate inline. Shows counts of dependent fields and tickets so admins know why deletion is blocked.' },
  { g: 'Domain Modelling', n: '19. Custom Fields (Dynamic Schema)',
    d: '**Backend** — `CustomFieldController` + `CustomFieldService` — admin-defined extra fields per subcategory (`TEXT`, `TEXTAREA`, `NUMBER`, `URL`, `EMAIL`, `DATE`, `SELECT`). Immutable field key, ordered display, active/inactive flag, per-type validation. Audited.' },
  { g: 'Domain Modelling', n: '20. Admin Custom Fields Page',
    d: '**Frontend** — `AdminFieldsPage.tsx` — reorderable list per subcategory, create/edit form with type-specific inputs (options CSV for SELECT, placeholder, required flag, display order).' },
  { g: 'Domain Modelling', n: '21. Projects CRUD',
    d: '**Backend** — `ProjectController` + `ProjectService` — admin-managed projects with name, subtitle, department, member list (many-to-many with users), start/end dates, progress %, status (`ON_TRACK / DELAYED / COMPLETED`). Response includes computed `daysLeft`. Audited.' },
  { g: 'Domain Modelling', n: '22. Admin Projects Page',
    d: '**Frontend** — `AdminProjectsPage.tsx` — cards or list, create/edit form with a member picker, progress slider, date pickers, and status pill.' },

  // Ticket Workflow
  { g: 'Ticket Workflow', n: '23. Single Ticket Submission',
    d: '**Backend** — `POST /api/user/tickets` — validates department + subcategory active, required custom fields, URL syntax, email pattern, numeric parsing, SSRF blocklist on website links. Empty-string writes for legacy NOT-NULL columns. Bilingual translation triggered on write.' },
  { g: 'Ticket Workflow', n: '24. Bulk Ticket Submission',
    d: '**Backend** — `POST /api/user/tickets/bulk` — one request creates N tickets with shared metadata + per-article title/content/website. Same validation as the single endpoint.' },
  { g: 'Ticket Workflow', n: '25. Submit Ticket Page',
    d: '**Frontend** — `SubmitTicketPage.tsx` orchestrates a multi-article submission flow. Subcomponents in `pages/user/submit/`: `ArticleCard.tsx` (one article\'s editable card), `CustomFieldsSection.tsx` (renders the dynamic fields for the chosen subcategory), `DynamicField.tsx` (one custom-field input, switched by type), `DocumentUploadDialog.tsx` (drag/drop file upload → server extracts text → auto-fills the content), `AiCheckDialog.tsx` (sends content to `/api/ai/check`, shows diff + change log, apply-corrected), `useArticles.ts` (reducer hook for the article list state).' },
  { g: 'Ticket Workflow', n: '26. My Tickets Page',
    d: '**Frontend** — `MyTicketsPage.tsx` — paginated list of the current user\'s tickets with status pill, department/subcategory, submission time. Click for detail.' },
  { g: 'Ticket Workflow', n: '27. Admin Tickets Page',
    d: '**Frontend** — `AdminTicketsPage.tsx` — paginated list of all tickets, filter by status, per-row status-change dropdown, delete action. All admin writes go through the audited endpoints.' },
  { g: 'Ticket Workflow', n: '28. Ticket Detail & Status Transitions',
    d: '**Backend** — `GET /api/tickets/{id}` (ownership check for non-admins), `PATCH /api/admin/tickets/{id}/status`, `DELETE /api/admin/tickets/{id}`. Status changes are audited with `old → new`.' },

  // Document Processing
  { g: 'Document Processing', n: '29. Multi-Format Text Extraction',
    d: '**Backend** — `DocumentExtractionService` routes uploads by MIME: PDFs → `PdfExtractionService` with OCR fallback for scanned pages; Images → Tesseract via Tess4J (Arabic + English models); Everything else Tika-supported → `AutoDetectParser`. Returns cleaned text, character count, truncation flag, and warnings.' },
  { g: 'Document Processing', n: '30. XXE Hardening for Office/RTF/ODF',
    d: '**Backend** — Custom `ParseContext` in Tika with `SAXParserFactory` and `DocumentBuilderFactory` configured to disallow DOCTYPE declarations, external general/parameter entities, and external DTD loading.' },
  { g: 'Document Processing', n: '31. Executable File Rejection',
    d: '**Backend** — Pre-parse `Tika.detect()` cross-check of file signature vs declared extension. Executables (`x-msdownload`, `x-executable`, `x-mach-binary`, etc.) rejected with HTTP 400 even when renamed.' },
  { g: 'Document Processing', n: '32. Per-User Upload Quota',
    d: '**Backend** — `UploadQuotaService` — rolling 24h byte cap per user (default 500 MB, configurable). Exceeded uploads rejected with HTTP 413 before any processing. In-memory (fine because uploads are transient).' },
  { g: 'Document Processing', n: '33. Document Upload Dialog',
    d: '**Frontend** — `DocumentUploadDialog.tsx` — file picker → shows progress → displays extracted text with warnings + character count → user confirms to insert into the article body.' },

  // AI Assistance
  { g: 'AI Assistance', n: '34. Grammar / Formatting Check API',
    d: '**Backend** — `AiCheckController` + `AiCheckService` — heuristic pass (collapses repeated whitespace, normalizes punctuation, capitalizes sentence starts, caps blank-line runs) with a change log. Cap: 50M characters per request. Designed for swap to a real LLM later.' },
  { g: 'AI Assistance', n: '35. AI Check Dialog',
    d: '**Frontend** — `AiCheckDialog.tsx` — sends the article\'s content, displays original vs corrected side-by-side, lists applied rules, one-click "apply corrected".' },

  // Internationalization
  { g: 'Internationalization', n: '36. Bilingual Storage',
    d: '**Backend** — every user-facing name/title/description field has `*_en` and `*_ar` columns alongside the legacy single-column value. Applies to Department, Subcategory, CustomField (label/placeholder/options), Project (name/subtitle), User (display name), Ticket (title/content/website name), TicketFieldValue (translated only for TEXT/TEXTAREA/SELECT types).' },
  { g: 'Internationalization', n: '37. Translation Service',
    d: '**Backend** — `TranslationService` calls LibreTranslate `/translate`, auto-detects source language by Unicode range, returns a `Bilingual(en, ar)` pair. Fail-open: on 4xx/5xx/network error, both sides keep the original text so a translator outage never blocks a save. Empty base URL cleanly disables the feature.' },
  { g: 'Internationalization', n: '38. Self-Hosted LibreTranslate',
    d: '**Ops** — `libretranslate/libretranslate:latest` in compose with `LT_LOAD_ONLY=en,ar` to trim the image, `LT_DISABLE_WEB_UI=true` to reduce attack surface, healthcheck against `/languages`. Models cached to a named volume. No API key, no rate limits, no per-call cost.' },
  { g: 'Internationalization', n: '39. Translation Backfill',
    d: '**Backend** — on every boot, `DataSeeder` scans rows where `_en`/`_ar` are null and fills them via the translator. Idempotent — only touches rows that need it, so it\'s cheap after the first run.' },
  { g: 'Internationalization', n: '40. Locale-Aware Response Serialization',
    d: '**Backend** — `AcceptHeaderLocaleResolver` bean + `Localizer` component read the request\'s `Accept-Language` and pick the right side of every bilingual field. Fallback chain: requested language → the other language → the legacy column.' },
  { g: 'Internationalization', n: '41. Frontend i18n Context',
    d: '**Frontend** — `i18n/index.tsx` provides `LocaleProvider` + `useT()`, backed by `en.ts` / `ar.ts` dictionaries for static UI labels. Persists the choice in `localStorage`, updates `<html lang>` + `<html dir>` for RTL/LTR mirroring.' },
  { g: 'Internationalization', n: '42. Locale-Header Propagation',
    d: '**Frontend** — axios interceptor reads the stored locale on every request and sets `Accept-Language: ar|en` so the backend picks the right bilingual field.' },

  // Dashboards & Reporting
  { g: 'Dashboards & Reporting', n: '43. Admin Global Stats',
    d: '**Backend** — `GET /api/admin/stats` — totals for tickets, departments, active fields, users, plus status breakdown and today\'s completions.\n**Frontend** — `AdminDashboardPage.tsx` — KPI cards + status donut.' },
  { g: 'Dashboards & Reporting', n: '44. Weekly Report',
    d: '**Backend** — `GET /api/admin/reports` — per-day submission histogram (7 days) + top 5 completing agents.\n**Frontend** — `AdminReportsPage.tsx` — bar chart + leaderboard table.' },
  { g: 'Dashboards & Reporting', n: '45. Domain / Subcategory Analytics',
    d: '**Backend** — `GET /api/admin/dashboard/domains` (list) and `/domains/{id}` (detail: 30-day trends + subcategory breakdown).\n**Frontend** — invoked from `DepartmentDetailModal` and `AdminDashboardPage`.' },
  { g: 'Dashboards & Reporting', n: '46. Agent Leaderboard',
    d: '**Backend** — `GET /api/admin/dashboard/users?range=day|week|month` ranks agents by throughput with today/week totals + per-day average.\n**Frontend** — Admin dashboard section.' },
  { g: 'Dashboards & Reporting', n: '47. Per-Agent Activity View',
    d: '**Backend** — `GET /api/admin/dashboard/users/{id}` — daily submissions, department/subcategory/status breakdowns.\n**Frontend** — `AdminUserActivityPage.tsx` — chart + tables.' },
  { g: 'Dashboards & Reporting', n: '48. User Self-Dashboard',
    d: '**Backend** — `GET /api/user/dashboard/me` — one-shot bundle of KPIs (today/week/month), current + longest streak, best day, rolling average, 30-day trend, status/department/subcategory breakdowns, 5 most-recent tickets.\n**Frontend** — `UserDashboardPage.tsx` composed from `pages/user/dashboard/`: `KpiCard.tsx` (headline stat cards), `StatusDonut.tsx` (status share chart), `TrendChart.tsx` (daily submissions line/bar chart), `BreakdownList.tsx` (top departments/subcategories), `RecentActivity.tsx` (latest tickets feed).' },

  // Cross-Cutting UI
  { g: 'Cross-Cutting UI', n: '49. App Shell & Navigation',
    d: '**Frontend** — `Layout.tsx` provides the main shell (top bar + side panel + routed content). `SidePanel.tsx` renders role-aware navigation items. Reusable `Avatar.tsx`, `Icons.tsx`, `Modal.tsx`, `StatusPill.tsx` used across pages.' },
  { g: 'Cross-Cutting UI', n: '50. Theme System (Light / Dark)',
    d: '**Frontend** — `ThemeContext.tsx` — persists user choice, falls back to `prefers-color-scheme`. Applied via `<html data-theme>` attribute so `global.css` can switch tokens.' },
  { g: 'Cross-Cutting UI', n: '51. Preferences Toggle',
    d: '**Frontend** — `PreferencesToggle.tsx` — combined language + theme picker shown in the layout header.' },
  { g: 'Cross-Cutting UI', n: '52. Global Stylesheet & Design Tokens',
    d: '**Frontend** — `styles/global.css` — CSS variables for colors, spacing, radius; per-theme token overrides; RTL-aware rules using logical properties.' },

  // Auditing & Compliance
  { g: 'Auditing & Compliance', n: '53. Admin Action Audit Trail',
    d: '**Backend** — `AuditLog` entity + `AuditService` + `AuditLogRepository`. Every admin CREATE/UPDATE/DELETE/STATUS_CHANGE on Departments, Subcategories, Custom Fields, Projects, Users, and Tickets writes a row (actor id + username, action, entity type + id, short details, timestamp). Passwords never included — only `passwordChanged=true|false`.' },
  { g: 'Auditing & Compliance', n: '54. Audit Log Query API',
    d: '**Backend** — `GET /api/admin/audit-logs?page=&size=&entityType=&entityId=&actorId=` — paginated (size clamped to 200), ordered newest-first.' },

  // Security Hardening
  { g: 'Security Hardening', n: '55. CORS Policy',
    d: '**Backend** — explicit origin allowlist via env var, `allowCredentials(true)`. Documented that `*` is unsafe with credentials.' },
  { g: 'Security Hardening', n: '56. SSRF Blocklist for User URLs',
    d: '**Backend** — `validateUrl` in `TicketService` refuses private ranges (`127.*`, `10.*`, `192.168.*`, `172.16-31.*`, `169.254.169.254`, `localhost`, `.local`, `.internal`).' },
  { g: 'Security Hardening', n: '57. Page-Size Clamps',
    d: '**Backend** — every paginated endpoint (tickets, audit logs) clamps `page ≥ 0` and `size ∈ [1, 200]` to prevent memory blow-ups.' },
  { g: 'Security Hardening', n: '58. Global Exception Handler',
    d: '**Backend** — `GlobalExceptionHandler` maps validation errors, `ResponseStatusException`, and unexpected failures to sanitized JSON error bodies so stack traces do not leak.' },
  { g: 'Security Hardening', n: '59. Disable Spring Default In-Memory User',
    d: '**Backend** — `@SpringBootApplication(exclude = { UserDetailsServiceAutoConfiguration.class })` prevents Spring Boot from printing the generated password and registering the phantom `user` credential.' },
  { g: 'Security Hardening', n: '60. Fail-Fast Weak-Secret Detection',
    d: '**Backend** — `JwtService` refuses to start if `JWT_SECRET` is missing, still the placeholder, or shorter than 32 bytes.' },

  // Quality & Ops
  { g: 'Quality & Ops', n: '61. Backend Unit + Repository Tests',
    d: '`TicketServiceTest`, `DashboardServiceTest`, `PdfOcrServiceTest`, `TicketRepositoryTest` — 22 tests covering the business rules that matter (validation, authorization, aggregations). H2 in-memory with `MODE=LEGACY;NON_KEYWORDS=VALUE` for schema fidelity.' },
  { g: 'Quality & Ops', n: '62. Frontend Type-Check in CI',
    d: '`tsc --noEmit` runs cleanly on every build; catches contract drift between backend DTOs and `frontend/src/api/types.ts`.' },
  { g: 'Quality & Ops', n: '63. k6 Load Test',
    d: '`loadtest/dems-load.js` — 30 concurrent VUs over 2m20s, mixes EN/AR locales across login/departments/tickets/self-dashboard. Enforces p95 latency + error-rate thresholds. Baseline: 10,628 requests, 0 errors, p95 dashboard 4.7ms.' },
  { g: 'Quality & Ops', n: '64. Security Review Skill Run',
    d: '`/security-review` invoked against the pending changes — filtered high-confidence findings only.' },
  { g: 'Quality & Ops', n: '65. Deployment Playbook',
    d: 'Documented steps: clone → populate `.env` (`JWT_SECRET` random 32+, rotate seed admin) → `docker compose up -d --build` → wait for `libretranslate` model download (1-3 min on first boot) → smoke-test `/api/auth/me` → rotate admin password through the UI.' },
  { g: 'Quality & Ops', n: '66. Backup Strategy',
    d: 'Back up PostgreSQL with `pg_dump`, verify restores regularly, and back up the `dems-data` volume separately because it contains uploaded documents rather than relational data.' },
];

function post(path, body) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = https.request({
      hostname: 'api.clickup.com',
      path,
      method: 'POST',
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
    await sleep(200);  // stay under 100 req/min
  }
  const okCount = results.filter(r => r.ok).length;
  console.log(`\n=== ${okCount}/${results.length} tasks created ===`);
})();

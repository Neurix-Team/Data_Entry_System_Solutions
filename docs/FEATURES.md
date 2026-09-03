# Neurix Data Entry Management System — Feature Overview

A production-grade platform that turns scanned books, reports and documents into clean, structured, exportable knowledge. Three roles, one workspace, and a read-only export API for downstream AI projects.

---

## 1. Security & Access

- **JWT authentication** with httpOnly secure cookies and stateless sessions.
- **Brute-force protection** — persistent login rate limiting (database-backed, shared across replicas).
- **Role-based access**: `USER` (Data Entry Agent), `ADMIN` (Team Leader), `SUPER_ADMIN` (cross-team operator).
- **Multi-tenant teams** — every team is a fully isolated workspace (users, projects, departments, entries, uploads). Tenant ownership is enforced on every write path.
- **Audit log** of sensitive actions (approvals, deletions, status changes, admin operations).
- **Strict CORS allowlist**, BCrypt password hashing, rotating seed credentials via `.env`.

## 2. For Data Entry Agents

- **Personal dashboard** — animated KPI counters (today / week / month / all-time), daily streaks, 30-day trend chart, status donut, breakdown by department and subcategory, recent activity.
- **New Entry form**
  - Pick project → department → subcategory; the form adapts instantly.
  - **Admin-defined custom fields** per subcategory: text, multi-line text, number, URL, email, date, dropdown (with required/optional and ordering).
  - **Multi-article submit** — add several articles in one go, each becoming its own entry.
  - Website name/link, reference resources, and attached documents per article. Attaching a file **auto-fills the entry title** from it (the filename, or the PDF's own metadata title when the filename is a scanner default such as `scan0001.pdf`).
- **Extract text from any file** — PDF, Word, Excel, PowerPoint, OpenDocument, images, text. **Built-in OCR (Tesseract, Arabic + English)** for scans and images; extracted images are carried onto the entry.
- **AI content check** — cleans spacing, punctuation and capitalisation with an original-vs-suggested diff and one-click apply.
- **Automatic translation** — titles, content and names are translated Arabic ⇄ English on the server (self-hosted LibreTranslate, no API keys).
- **My Entries** — search, status tracking, full detail view with document download.
- **Project Folders & quick upload** — drop many files into a project folder; each becomes a review-status entry, titled from the file. Uploads are **chunked and parallel**: every file goes up as 8 MB chunks with four requests in flight (two files at a time), a failed chunk is retried on its own, and the server writes each chunk straight into place — finalize is a rename, never a copy. A **circular percentage meter** per file and for the whole batch shows bytes moved, speed and time left, then "done in 4.2 s". Byte-identical duplicates are rejected; files up to **500 MB** (5 GB per user per day).
- **In-app notifications** — bell with unread badge; agents are notified the moment an entry is approved; click to jump to the folder.
- **Neurix assistant** — floating chat that understands where features live and navigates you there (bilingual, history persisted).
- **Profile** — avatar upload, display name, email, phone, password change.
- **Dark mode** and **English / Arabic with full RTL layout**, one click each.

## 3. For Team Leaders (Admins)

- **Team dashboard** — headcount, active fields, departments, pending, completed today; weekly task-progress chart; top performers; **domain overview** per department (with subcategory drill-down); **agent leaderboard** with Today / Week / Month ranges.
- **Team Members** — create/edit accounts, roles, active/inactive access, bulk add, bulk delete, per-member activity page.
- **Projects** — multi-department projects with subtitle, start/end dates, days-left / overdue indicator, progress %, status (On Track / Delayed / Completed), assigned members.
- **Departments** — per-project departments with a detail modal (entries, subcategories, active agents, last-30-days stats).
- **Subcategories & custom form builder** — each subcategory owns its own form fields; no code changes needed.
- **Data Entry Tasks** — every entry in one table: search across content/website/agent, filter by department, status and date range, **inline status change**, side-panel review with full article, custom values, resources and **document download**, delete. **Update** any entry in place — title, content, website and resources — and attach more files in the same dialog, with the same upload meter.
- **Project Folders (admin view)** — review uploads per project, **approve individually or in bulk**; approvals notify the agent instantly.
- **Reports** — task distribution, week-over-week comparison, top performers, field fill rate.
- **Agent activity** — daily submissions for a specific agent with breakdowns by department, subcategory and status.

## 4. For Super Admins & the Data Pipeline

- **Global overview** — totals across every team (teams, users, admins, projects, departments, entries, today / this week).
- **Teams** — create/edit/deactivate isolated workspaces, add the team admin in one step, list members.
- **Enter any team (impersonation)** — work inside a team's data with a persistent orange banner and one-click exit.
- **Project analytics** — every project in every team with its admins and members.
- **Data explorer** — every entry in every team with filters (team, project, submitter, date range, search), expandable rows and file downloads.
- **Server dataset** — publish a flat, de-duplicated dataset snapshot with counters (published / pending records and files).
- **API tokens** — personal-access tokens for the export API, shown exactly once, with expiry presets, revoke and delete, last-used tracking.
- **Versioned export API** (`/api/v1/export`) — tickets, ticket detail, document download and the published dataset, with document content hashes for incremental mirroring.
- **Super admins management** — small, auditable list of cross-team operators.

## 5. Platform & Operations

- **Backend**: Java 17, Spring Boot 3, JPA/Hibernate, PostgreSQL 16+, Tesseract OCR, Apache PDF/Office extraction.
- **Frontend**: React 18 + TypeScript + Vite, hand-crafted design system, route-level code splitting, skeleton loading, toasts and confetti feedback, accessible components.
- **Deployment**: single `docker compose up --build` — PostgreSQL, API, nginx-served UI and self-hosted LibreTranslate; proxy labels for auto-SSL.
- **Performance**: query pooling and auth caching, chunked parallel resumable uploads (`/api/uploads/sessions`) with a rename-only finalize, multipart spooling inside the attachments volume, OCR concurrency gate, upload quotas, hourly sweep of abandoned upload sessions.
- **Localisation**: bilingual UI and bilingual data model (every name/title stored in English and Arabic).

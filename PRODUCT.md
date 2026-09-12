# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Three in-product roles, cumulative in authority:

- **Data Entry Agent** (`USER`) — the person at the keyboard for a full shift. Opens a source document (a scan, a PDF, a Word/Excel file, a photographed page), reads it, and captures it as a structured entry: department, subcategory, website/source, content body, custom fields, attachments. Works in batches ("add another article" with shared fields). Tracks their own streak, daily trend, and approval state.
- **Team Leader** (`ADMIN`, labelled "Team Leader" in the sidebar) — runs one team. Assigns and tracks data-entry tasks across departments, manages the roster and departments/subcategories/projects, reviews entries inside project folders and approves them, reads reports and per-agent activity.
- **Super Admin** (`SUPER_ADMIN`) — cross-team operator. Creates teams, "enters" a team (impersonation, demoted to ADMIN while scoped in), reads the global overview, runs the Data Explorer across every team, publishes the flat dataset, and issues read-only API tokens.

**Buyers / evaluators (confirmed by the user: all three segments, no single vertical):**
digitization and BPO contractors who run data-entry teams against client archives; government and public-sector bodies digitizing paper records; enterprise back-offices (banks, insurance, telecom, health) converting internal documents.

## Product Purpose

Neurix runs a document data-entry operation end to end: source documents in, reviewed structured records out, and the data back out again in a form the customer owns.

The loop: an agent imports a document and Neurix extracts its text (OCR for scans and images), the agent captures the structured entry against that text, a team leader reviews and approves it inside the project folder, and the approved records leave as a mirrored folder tree on disk, a ZIP, a published flat dataset, or a read-only pull API.

Success is operational, not feature-count: a team's daily throughput is visible, every entry is attributable, no team can see another team's data, and the customer can take the whole archive off the platform whenever they want.

## Positioning

What a neighbouring product could not truthfully copy:

1. **Arabic and English as one pipeline, not a locale setting.** OCR runs Tesseract with `ara+eng` together, so a mixed Arabic/English scanned page extracts in one pass. The UI is fully bilingual with real RTL, and the reference data itself is bilingual (teams, departments, subcategories, and custom-field labels each carry EN and AR values) — not just translated chrome.
2. **Tenant isolation enforced three independent ways.** A `TeamOwned` entity listener stamps ownership on persist; a Hibernate filter is switched on around every transaction by an AOP aspect; and a tenant guard re-checks ownership on direct by-id fetches, returning 404 rather than 403 so a wrong-team id cannot even confirm a row exists. Not a `WHERE team_id` convention.
3. **Ingest engineered for real archive files on real connections.** 8 MiB chunks, four in parallel, resumable across sessions for 24 hours (the server returns which chunks it already has), retry with backoff on transient failures only, live speed and ETA, per-user daily quota, and graceful fallback to plain multipart where chunking is unavailable.
4. **Exit is a feature.** Direct-to-disk folder mirroring (Project → Department → files) with "skip files that already exist" so a re-run fetches only what is new, a ZIP with the same tree for every other browser, a denormalized published dataset, and a token-scoped read-only pull API.

## Operating Context

- Agents work long shifts in front of the same screen; the in-app design system is deliberately soft and low-strain, with light and dark themes.
- Source material is whatever the archive holds: scanned PDFs, photographed pages, Word, Excel, PowerPoint, plain text, images. Per-file import cap 25 MB for extraction; per-file upload cap 500 MB; per-user upload quota 500 MB/day on a sliding 24-hour window.
- OCR is serialized through a fair single-permit gate and answers `503 "OCR engine is busy — try again in a moment"` rather than collapsing under load. Extraction is synchronous and request-scoped; there is no async job queue.
- Work is organized as Projects, each of which is also a folder: entries accumulate in it, pending approval, then approved ("✓ Saved to database").
- Deployment is self-hosted Docker Compose: Postgres, backend, nginx frontend, a nightly backup sidecar, and Prometheus/Alertmanager/Grafana. The frontend nginx proxies `/api/*` internally.
- Teams are fully isolated workspaces; a slug is immutable after creation.

## Capabilities and Constraints

**Real and citable:**
- Bilingual OCR ingest — Tesseract `ara+eng` via tess4j, PDFBox and Tika for PDF/Office/image/text extraction, automatic OCR on scanned pages, extracted images become captioned attachments with page badges.
- Chunked, parallel, resumable upload with live speed/ETA telemetry and cancellation.
- Three-layer multi-tenant isolation; impersonation that keeps audit attribution on the real human.
- Dynamic custom fields rendered per subcategory, stored normalized (no schema change per field).
- Project folders with review → approve, and a notification to the agent when their entry is approved.
- Dashboards: team progress, agent leaderboard, per-agent activity, domain/department breakdowns; per-agent streaks and daily trends.
- Data Explorer across every team, with cursor paging and filters.
- Export: direct-to-disk folder mirroring (File System Access API, Chrome/Edge) with incremental skip-existing, ZIP for all browsers, `.md` note per entry plus `index.csv` inside the archive, published flat dataset, read-only `/api/v1` pull API with named expiring revocable tokens.
- Security: stateless JWT with server-side revocation via token versioning, BCrypt, deny-by-default authorization, login and API rate limiting, boot-time refusal of wildcard CORS and insecure production config, security headers.
- Operations: Flyway migrations with `validate` (no silent schema drift), Actuator + Micrometer → Prometheus, provisioned Grafana dashboard, Alertmanager rules, nightly `pg` backup sidecar, runbook, load-test harness, CI.

**Must NOT be claimed (verified absent or misleading):**
- Any "AI", "LLM", "smart", or "intelligent" framing. The "Check Content" helper is a deterministic regex cleanup, and the in-app chat widget is a keyword→navigation router. Neither involves a model.
- `extraction_jobs` / `document_pages` tables or an async extraction queue — do not exist.
- Excel, CSV, or PDF *generation*. PDF is input only. The only tabular artifact is `index.csv` inside the ZIP.
- A no-code form builder: custom fields work end-to-end for agents, but there is **no admin UI** to create them (no `/admin/fields` route) — they come from the seeder. Backend endpoints exist; the screen does not.
- An audit-log dashboard or a notification centre. The audit log is real but has no UI; exactly one notification type is emitted (entry approved).
- Live machine translation as a user feature — translation runs only at seed time.

**Undecided / open:**
- Pricing, licensing, and packaging — none exist.
- Where demo requests should be delivered (no lead-capture endpoint exists in the backend).
- Product name is inconsistent in code: browser title and logo assets say **Neurix**; the in-app brand string says "DataEntry"; containers are prefixed `dems-`. Neurix is treated as the product name.

## Brand Commitments

- **Name:** Neurix. Logo assets already in the repo: `frontend/public/neurix-logo.png`, `neurix-logo-light.png`, `neurix-mark.png`.
- **Palette (from `tailwind.config.js` and `global.css`):** brand blue `#0f5fd1`, deep blue `#0a3f9c`, cyan `#22c3d9`, navy `#0d1a33`; signature gradient `135deg, #0a3f9c → #0f5fd1 → #22c3d9`. Dark theme ground `#060b1a` / surface `#0f172c`. Theme colour `#0f5fd1`.
- **Bilingual by commitment:** English and Arabic are peers, with real RTL driven off `<html dir>`. The in-app Arabic register is deliberately informal Egyptian colloquial in places.
- **No UI component library.** The design system is hand-crafted: CSS variables in `global.css` plus Tailwind 3.4 with `preflight: false`.
- Light and dark themes both ship and are switchable.

## Evidence on Hand

**Real:**
- Working product with the routes and capabilities listed above.
- `SECURITY.md`, `RUNBOOK.md`, `docs/`, `loadtest/`, `.github/` CI, `monitoring/` with a provisioned Grafana dashboard and Prometheus alert rules.
- Product tour videos scripted and recorded in English and Egyptian Arabic under `scripts/promo-video` (`NX_LANG`).
- Isolated demo data lives in a dedicated "Neurix Demo" team.
- Exact product copy worth quoting verbatim, e.g. *"Upload a PDF, Word (.doc/.docx), Excel (.xls/.xlsx), PowerPoint (.ppt/.pptx), image (.jpg/.png/…) or text file — up to 25 MB. OCR runs on images and scanned PDFs (Arabic + English)."*

**Absent — future work must not fabricate these:**
- No customers, logos, testimonials, case studies, or press.
- No published benchmarks or performance numbers beyond the configured limits above.
- No pricing, no trial, no self-service signup (accounts are created by an administrator: *"Contact your administrator if you don't have an account."*).
- No uptime or certification claims (no SOC 2, ISO, GDPR attestation).

## Product Principles

1. **Demonstrate the pipeline, never assert intelligence.** The product's credibility rests on engineering that is actually there — bilingual OCR, resumable chunked ingest, hard tenant isolation. Borrowed AI vocabulary would trade a real claim for a weak one.
2. **Arabic is a first-class citizen, not a translation layer.** Anything that treats Arabic as an afterthought misrepresents the product.
3. **The customer's data is the customer's.** Export paths, incremental re-runs, and a read-only pull API are positioning, not plumbing.
4. **Operational honesty over feature inflation.** Named limits, queue behaviour under load, and a documented runbook are the proof; an unbuilt screen is never advertised.
5. **Built for the long shift.** Density, contrast, and motion must respect someone who is looking at this for eight hours.

## Accessibility & Inclusion

- English and Arabic parity with correct RTL mirroring driven by `<html dir>`.
- Light and dark themes; palette chosen to reduce eye strain over long sessions.
- Direct-to-disk folder download requires Chrome or Edge (File System Access API); a ZIP path is the documented fallback for every other browser, and the UI says so.

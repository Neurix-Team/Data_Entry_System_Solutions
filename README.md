# Data Entry Management System

A production-grade, full-stack data entry management system:

- **Backend** — Java 17 + Spring Boot 3, JPA/Hibernate, PostgreSQL, JWT auth, BCrypt password hashing.
- **Frontend** — React 18 + TypeScript + Vite, React Router, axios, a hand-crafted design system (no UI library) tuned for long working hours (soft palette, generous spacing, muted accents).
- **Auth** — JWT bearer tokens, stateless sessions, role-based route protection (`ADMIN` / `USER`).
- **Dynamic form** — Admin adds/edits form fields at runtime; users see them automatically. Values are stored in a normalized `ticket_field_values` table (no schema changes needed).
- **AI content check** — server endpoint that returns grammar/spelling suggestions. Currently a stub (deterministic cleanup); swap in a real LLM call in `AiCheckService.check()`.

---

## Project layout

Super admins can use `/super/cleaned-files` to upload preprocessing outputs, organized by
project and department. Files have a cleaning date, optional due date, source/batch reference,
notes, and a manual AI-work status (ready, in progress, completed). Multiple files upload
independently, with failed files retained for retry. This section tracks work; it does not
run an AI model. Supported formats and the per-file limit are shown in the upload dialog
(50 MB by default, configurable with `app.cleaned-files.max-file-bytes`). Files are stored
under `data/cleaned-files` in the backend's existing persistent data volume. Include this
directory in file backups alongside `data/attachments`; the V13 migration stores metadata.
Projects and departments with cleaned outputs cannot be permanently deleted, so deleting
source data does not destroy the AI-work files. Projects can still be restored from the bin.
The workspace uses a table with per-file download/edit/delete actions and the same download
center as Data Explorer. Folder and streamed ZIP downloads cover every file matching the
current filters, including results beyond the visible page. Selection supports the current
page or all matching files with exclusions. Permanent deletion requires count confirmation;
stale selections are rejected, and file moves are rolled back if the database deletion fails.

```
data_entry/
├── backend/     Spring Boot API (Maven)
├── frontend/    Vite + React + TypeScript
└── README.md
```

---

## Deploying to a server

`.env` is gitignored — it holds the database password, the JWT signing key and the seed
admin passwords, so it never travels through the repository. Generate it on the server
instead; only the PostgreSQL password is asked for, everything else is generated or
defaulted:

```bash
git pull origin main
./scripts/setup-env.sh
sudo docker compose up -d --build
```

`setup-env.sh` refuses to overwrite an existing `.env` unless you pass `--force`, and prints
the generated seed credentials once. PostgreSQL is expected to be running **on the host**
(not in compose) with the `dataentry` database and the `daleel` role already created.

---

## Quickest way — Docker Compose

Just Docker required (`docker` + `docker compose`, both included with Docker Desktop):

```powershell
docker compose up --build
```

Then open **http://localhost:8082**. The backend also exposes its API on **http://localhost:8083** if you want to call it directly.

- Frontend nginx proxies `/api/*` internally to the backend service — no CORS setup needed.
- PostgreSQL connection values come from the root `.env`; `dems-data` keeps uploaded files.
- Stop with `Ctrl+C` or `docker compose down`. Database data remains in PostgreSQL.

Default credentials (change immediately):

| Role  | Username | Password  |
|-------|----------|-----------|
| Admin | `admin`  | `admin123`|
| User  | `agent1` | `agent123`|

Change the JWT secret before shipping anywhere real — edit `JWT_SECRET` in `docker-compose.yml`.

---

## Running without Docker

### Prerequisites

- **JDK 17+** (for the backend)
- **Node.js 18+** and **npm** (for the frontend)
- **PostgreSQL 16+** with an existing database and a user that can create tables
- No Maven install needed — the project uses a `pom.xml` + `mvnw` wrapper. If `mvnw` is missing, install Maven 3.9+ and use `mvn` instead.

---

## 1. Run the backend

```powershell
cd backend
# Windows
mvnw.cmd spring-boot:run
# macOS / Linux
./mvnw spring-boot:run
```

Or, if you have Maven installed globally:

```powershell
cd backend
mvn spring-boot:run
```

The API starts on **http://localhost:8080**. On first run it will:

- Connect to PostgreSQL and create/update the application tables
- Seed a default admin user, sample data-entry agent, departments, and example custom fields

**Default credentials (change immediately after first login):**

| Role  | Username | Password  |
|-------|----------|-----------|
| Admin | `admin`  | `admin123`|
| User  | `agent1` | `agent123`|

---

## 2. Run the frontend

In a second terminal:

```powershell
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**.

The Vite dev server proxies `/api/**` to the backend, so no CORS setup is needed for local development.

---

## Configuration

Edit `backend/src/main/resources/application.yml` (or set env vars at runtime):

| Setting                        | Env var           | Default                            |
|--------------------------------|-------------------|------------------------------------|
| PostgreSQL host                | `DB_HOST`         | `localhost`                        |
| PostgreSQL port                | `DB_PORT`         | `5432`                             |
| PostgreSQL database            | `DB_NAME`         | `dataentry`                        |
| PostgreSQL username            | `DB_USERNAME`     | `daleel`                           |
| PostgreSQL password            | `DB_PASSWORD`     | required                           |
| JWT signing secret             | `JWT_SECRET`      | ⚠ change in production            |
| Token expiration               | —                 | 24 hours                           |
| CORS allowed origins           | —                 | `http://localhost:5173, :3000`     |
| Seed default admin             | —                 | `true` (idempotent)                |
| Default admin username / pwd   | —                 | `admin` / `admin123`               |

### PostgreSQL connection

Copy `.env.example` to `.env`, set the `DB_*` values, and start the backend. Flyway runs
any pending migrations from `backend/src/main/resources/db/migration/` before Hibernate
starts, and Hibernate is set to `ddl-auto: validate` so any drift between an entity and
the schema fails the boot instead of silently patching the DB. Docker uses `DB_DOCKER_HOST`
when the database host differs from the address used by a backend running directly on Windows.

### Database migrations

Every schema change ships as a versioned SQL file in
`backend/src/main/resources/db/migration/`, named `V<n>__<short_description>.sql`
(double underscore). Flyway picks them up on backend startup and applies anything past
the current schema version, then Hibernate `validate` confirms every `@Entity` maps to a
real column. **Never change `ddl-auto` back to `update`** — it will silently drift the
schema again and undo the whole point of this system.

- **First boot on an existing deployment** — Flyway sees the tables already exist,
  creates its `flyway_schema_history` table, and stamps `V1` as a baseline row without
  executing it (`spring.flyway.baseline-on-migrate=true`, `baseline-version=1`).
  Only V2 onwards actually run.
- **First boot on a fresh database** — Flyway executes `V1__baseline_schema.sql` from
  scratch. V1 captures the schema as of the 2026-09-02 `pg_dump` plus the three
  entities added between that dump and the switch to Flyway (`upload_sessions`,
  `dataset_records`, `ticket_documents.content_hash`).
- **Adding a new column / table** — add a `V<next>__<what_it_does>.sql` file with the
  DDL, e.g. `V2__add_ticket_priority.sql`. Don't edit an already-applied migration
  (`V1` is the frozen baseline) — Flyway detects checksum drift and refuses to boot.
- **Tests** — H2 is used for `mvn test` with `spring.flyway.enabled=false` and
  `ddl-auto: create-drop`; the Postgres-flavoured migrations don't need to run there.

---

## API overview

Prefix: `/api`

### Auth (public + authenticated)

| Method | Path              | Auth   | Purpose                    |
|--------|-------------------|--------|----------------------------|
| POST   | `/auth/login`     | public | Exchange credentials → JWT |
| GET    | `/auth/me`        | any    | Current user info          |

### Admin

| Method | Path                          | Purpose                          |
|--------|-------------------------------|----------------------------------|
| GET    | `/admin/stats`                | Dashboard totals                 |
| GET / POST / PATCH / DELETE | `/admin/users[/{id}]`        | Manage users                     |
| GET / POST / PATCH / DELETE | `/admin/departments[/{id}]`  | Manage departments (full list)   |
| GET / POST / PATCH / DELETE | `/admin/fields[/{id}]`       | Manage custom form fields        |
| GET    | `/admin/tickets?page=&size=`  | Browse all tickets               |
| DELETE | `/admin/tickets/{id}`         | Delete a ticket                  |

### User (both roles)

| Method | Path                                 | Purpose                              |
|--------|--------------------------------------|--------------------------------------|
| GET    | `/departments`                       | Active departments (form dropdown)   |
| GET    | `/fields`                            | Active custom fields (form render)   |
| POST   | `/user/tickets`                      | Submit a new ticket                  |
| GET    | `/user/tickets?page=&size=`          | List own tickets                     |
| GET    | `/tickets/{id}`                      | Get a single ticket (own, or any if admin) |
| POST   | `/ai/check`                          | AI grammar/spelling check on content |

All non-public endpoints require `Authorization: Bearer <token>`.

---

## Frontend architecture

- `src/api/*` — thin axios wrappers around the REST endpoints
- `src/context/AuthContext.tsx` — auth state, login/logout, token persistence in `localStorage`
- `src/components/` — reusable primitives (`Layout`, `Modal`, `ProtectedRoute`)
- `src/pages/admin/*` — admin dashboards & management screens
- `src/pages/user/*` — data entry submission form & history
- `src/styles/global.css` — the design system (CSS variables + utility classes)

The design system is intentionally hand-crafted rather than importing a UI kit — it's small (single CSS file), uses a soft palette to reduce eye strain, and has consistent 8px spacing.

---

## Swapping the AI check for a real LLM

Open `backend/src/main/java/com/dataentry/service/AiCheckService.java` and replace the `check(String input)` method with a call to your LLM of choice (Anthropic, OpenAI, etc.). Keep the response shape (`original`, `corrected`, `notes`) and the frontend will work unchanged.

---

## Security notes

- Passwords are hashed with BCrypt (`spring-security-crypto`).
- JWTs are signed with HMAC-SHA (JJWT `0.12.x`). Rotate `JWT_SECRET` in production.
- CORS is locked to the origins listed in `app.cors.allowed-origins`.
- Admin endpoints require `ROLE_ADMIN`; user endpoints require any authenticated role.
- Server-side validation runs alongside client-side validation — never trust the client.

---

## License

Internal use — adapt as needed.

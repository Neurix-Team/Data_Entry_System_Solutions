# Data Entry Management System

A production-grade, full-stack data entry management system:

- **Backend** — Java 17 + Spring Boot 3, JPA/Hibernate, SQLite (swap to Postgres/MySQL with two config lines), JWT auth, BCrypt password hashing.
- **Frontend** — React 18 + TypeScript + Vite, React Router, axios, a hand-crafted design system (no UI library) tuned for long working hours (soft palette, generous spacing, muted accents).
- **Auth** — JWT bearer tokens, stateless sessions, role-based route protection (`ADMIN` / `USER`).
- **Dynamic form** — Admin adds/edits form fields at runtime; users see them automatically. Values are stored in a normalized `ticket_field_values` table (no schema changes needed).
- **AI content check** — server endpoint that returns grammar/spelling suggestions. Currently a stub (deterministic cleanup); swap in a real LLM call in `AiCheckService.check()`.

---

## Project layout

```
data_entry/
├── backend/     Spring Boot API (Maven)
├── frontend/    Vite + React + TypeScript
└── README.md
```

---

## Quickest way — Docker Compose

Just Docker required (`docker` + `docker compose`, both included with Docker Desktop):

```powershell
docker compose up --build
```

Then open **http://localhost:8081**. The backend also exposes its API on **http://localhost:8080** if you want to call it directly.

- Frontend nginx proxies `/api/*` internally to the backend service — no CORS setup needed.
- SQLite data lives in a named volume (`dems-data`) so it survives restarts.
- Stop with `Ctrl+C`, remove containers with `docker compose down`, wipe data with `docker compose down -v`.

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

- Create `./data/dataentry.db` (SQLite)
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
| JWT signing secret             | `JWT_SECRET`      | ⚠ change in production            |
| Token expiration               | —                 | 24 hours                           |
| CORS allowed origins           | —                 | `http://localhost:5173, :3000`     |
| Seed default admin             | —                 | `true` (idempotent)                |
| Default admin username / pwd   | —                 | `admin` / `admin123`               |

### Switching from SQLite to another database

Only two properties change:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://host:5432/dataentry
    username: ...
    password: ...
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

Then swap the SQLite driver in `pom.xml` for the appropriate JDBC driver (`postgresql`, `mysql-connector-j`, …). All entities use standard JPA — no SQLite-specific SQL.

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

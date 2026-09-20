# Security Audit Report — Data Entry System (Neurix)

- **Date:** 2026-09-20
- **Scope:** Full review of backend (Java 17 / Spring Boot 3.5), frontend (React 18 / TS / Vite), Docker/Compose, Nginx, CI, monitoring, and Git history.
- **Method:** Static review + Git history inspection + dependency-scan artifacts review. No code changes were made in this phase.

> ⚠️ **Scope note:** The workspace project is a **Data Entry Management System** (roles: SUPER_ADMIN / ADMIN / USER, teams, tickets, uploads, chat), **not an LMS** with Admin/Teacher/Student roles and course/video/R2/Stream content. This audit covers the system **as it exists**. The LMS-specific roadmap items (R2, Cloudflare Stream, enrollment, teacher ownership) apply to the target platform once it exists; findings below map onto the current codebase.

---

## 1. Executive Summary

The project is in notably better shape than a typical pre-hardening codebase — a prior security remediation pass (2026-09-12/13, documented in `SECURITY_AUDIT.md` and `docs/security/2026-09-13/`) already fixed several critical issues: CSRF for cookie auth, X-Forwarded-For spoofing, JWT token-versioning revocation, upload quotas, CORS wildcard refusal at boot, and dependency upgrades.

**Residual risk is concentrated in:**

1. A committed `.env` in Git history (b5a95f2 → removed in c03c6f7) containing a placeholder JWT secret — the commit message itself documents the forgery risk. Any instance that ever booted with it must rotate.
2. Actuator endpoints are `permitAll()` in the first security chain (`SecurityConfig.actuatorSecurityFilterChain`) — environment/health/metrics exposure.
3. MFA is absent for privileged accounts.
4. No `POST /refresh`, `/forgot-password`, `/reset-password`, OTP, or email-verification endpoints exist — password resets are handled via a documented SQL/BCrypt procedure (`SECURITY.md`), which is operational, not a self-service flow.
5. No Cloudflare Turnstile / CAPTCHA anywhere.
6. Brute-force protection exists (login only); there is no self-serve registration (accounts are admin-seeded), which mitigates enumeration/bot abuse.

**Overall residual severity: MEDIUM** — no unmitigated Critical found in current code; one historical Critical (committed dev secret) requires operational rotation.

---

## 2. Architecture as Found

| Area | Current implementation |
|---|---|
| Auth transport | JWT (JJWT 0.12.6, HMAC-SHA) in HttpOnly `Secure` `SameSite=Lax` cookie (`JwtAuthFilter.AUTH_COOKIE`); `Authorization: Bearer` also supported (CSRF-exempt). |
| Token revocation | Server-side via per-user `tokenVersion` claim (`tv`) checked on every request; `logout-everywhere` and password change bump the version. |
| Access token lifetime | Configurable `app.jwt.expiration-ms`; no refresh-token endpoint (cookie re-issued at login only). |
| Password hashing | BCrypt via `BCryptPasswordEncoder` (`SecurityConfig#passwordEncoder`). |
| Password policy | `PasswordPolicy` service: min 8 / max 200, username-similarity checks. |
| Sessions | Stateless (`SessionCreationPolicy.STATELESS`). |
| CSRF | `CookieCsrfTokenRepository` + `/api/auth/csrf` endpoint; requests bearing `Authorization: Bearer` are CSRF-exempt (documented decision). |
| CORS | Explicit origin allowlist via `app.cors.allowed-origins`; `*` refused at boot; `allowCredentials=true`; methods/headers enumerated. |
| Authorization | Route-based deny-by-default: `/api/super/**` → SUPER_ADMIN, `/api/admin/**` → ADMIN, `/api/user/**` → USER+ADMIN, `/api/**` authenticated, `anyRequest().denyAll()`. Plus team-scoping in services (`TeamOwned`, impersonation context). |
| IDOR protections | Ownership + team checks on extraction files, avatars, tickets, upload sessions (see prior S2/S7 fixes). UUID file identifiers for extraction objects. |
| SQL injection | JPA/JPQL throughout; 7 native queries reviewed — all use `:param` binding, no concatenation found. Sort fields not user-controlled (fixed ORDER BY). |
| XSS | React escapes by default; zero `dangerouslySetInnerHTML` usages in `frontend/src`. `safeUrl.ts` restricts links to http/https. |
| File uploads | Tika magic-byte detection, size limits + per-user daily byte quota persisted in `upload_usage` with row locks, chunked upload sessions with owner checks, UUID storage names, original filename as metadata. |
| Error handling | `@RestControllerAdvice` — generic 500, no stack traces to clients, field-scoped validation errors. |
| Logging | SLF4J; audit log table + admin-only `AuditLogController`; password values excluded from audit records. |
| Production checks | `ProductionSecurityCheck` (`@Profile("docker")`) refuses boot on default/placeholder secrets, short JWT secret, wildcard/missing CORS. |
| Nginx | `frontend/nginx.conf`: `server_tokens off`, HSTS, nosniff, X-Frame-Options DENY, Referrer-Policy, Permissions-Policy, restrictive CSP, `client_max_body_size 520m`; backend port bound to 127.0.0.1 in compose. |
| Backups | `backup-loop.sh` sidecar (daily, retention configurable), `mirror-to-remote.sh` offsite, `verify-latest.sh`. |
| Monitoring | Prometheus + Alertmanager + Grafana; JVM/HTTP metrics via Actuator/Micrometer. |
| Dependency scanning | Dependabot + weekly OWASP Dependency-Check (`failBuildOnCVSS=11`) + `npm audit --audit-level=high`. Last scan: 0 known-vulnerable packages (2026-09-13 artifacts). |
| Docker | Postgres 18-alpine on internal compose network (no public 5432), healthchecks, mem limits, restart policies, backend published on loopback only. |

---

## 3. Findings

Severity scale: Critical / High / Medium / Low.

### F-01 — Committed `.env` in Git history — **Critical (historical; remediation is operational)**
- **Affected component:** Git history (`b5a95f2` "Track .env with its local-dev values"; removed in `c03c6f7`).
- **Security risk:** The commit contains `JWT_SECRET=local-dev-secret-please-rotate-me-...` and `APP_SEED_ADMIN_PASSWORD=admin123`. Anyone with repo access can forge admin tokens for any instance that ever booted with those values.
- **Business impact:** Full account/role takeover on any deployment that used the committed dev secret.
- **Recommended remediation:** Confirm production never booted with the placeholder (`ProductionSecurityCheck` refuses known placeholders under the `docker` profile). Rotate `JWT_SECRET` + seed/admin passwords in every real environment regardless. `.gitignore` does NOT make this safe — only rotation does. Optionally rewrite history (BFG) and force-push; note all clones become stale.

### F-02 — Actuator endpoints unauthenticated — **High**
- **Affected component:** `SecurityConfig.actuatorSecurityFilterChain` — `anyRequest().permitAll()` on all Actuator endpoints.
- **Security risk:** Depending on the exposure list (`/actuator/env`, `/heapdump`, `/loggers`, `/threaddump`), configuration values, connection strings, or memory contents may leak. Prometheus scraping needs only `health` + `prometheus`.
- **Business impact:** Information disclosure; potential credential exposure.
- **Recommended remediation:** Restrict the chain: permit `health` and `prometheus` only; deny everything else. Confirm `management.endpoints.web.exposure.include` covers only `health,info,prometheus,metrics`. Keep Actuator off the public internet.

### F-03 — No MFA for privileged accounts — **Medium**
- **Affected component:** `AuthService`, `AuthController` — password-only factor.
- **Security risk:** A phished/leaked admin or super-admin password yields full cross-team compromise.
- **Business impact:** Privileged account takeover.
- **Recommended remediation:** Enforce TOTP for SUPER_ADMIN (mandatory) and ADMIN (strongly recommended) as a dedicated MFA module, not inlined into the login flow.

### F-04 — No refresh-token rotation; single long-lived token in cookie — **Medium**
- **Affected component:** `JwtService` / `AuthController` — no `/refresh`; `logout` clears the cookie client-side only.
- **Security risk:** A stolen cookie is valid until expiry; plain `logout` cannot revoke a stolen copy (only `logout-everywhere`, which re-issues a new token and bumps the version). No rotation means no theft detection.
- **Business impact:** Extended session-hijack window.
- **Recommended remediation:** Short access token (10–15 min) + server-stored rotating refresh token with revocation — or keep the single-token model but shorten expiry and add a guarded `/refresh`. Document the tradeoff; tokenVersion revocation partially compensates today.

### F-05 — CSRF exemption keyed on `Authorization` header presence — **Medium**

### F-06 — Brute-force protection limited to login — **Medium**
- **Affected component:** `LoginRateLimiter` (account + network keys, Postgres-backed).
- **Security risk:** No limiter on `/api/auth/me/password` (authenticated current-password guessing), export API token issuance, chat, or upload endpoints. No CAPTCHA anywhere.
- **Business impact:** Targeted password guessing by an authenticated user; resource abuse on machine endpoints.
- **Recommended remediation:** Add limiters to password change and `/api/v1/export` issuance; add Cloudflare Turnstile (server-validated) on login after N failures if/when self-serve registration exists.

### F-07 — Password policy floor of 8 characters — **Low–Medium**
- **Affected component:** `PasswordPolicy`, `AuthDtos.ChangePasswordRequest` (`@Size(min=8, max=200)`).
- **Security risk:** 8 is the absolute NIST floor; no compromised-password denylist.
- **Business impact:** Weak credentials.
- **Recommended remediation:** Min 10–12 for ADMIN/SUPER_ADMIN; keep max 200 for passphrases; optional breached-password (k-anonymity) check.

### F-08 — Grafana exposed publicly via proxy label — **Medium**
- **Affected component:** `docker-compose.yml` `grafana` (`3001:3000`, `neurix.proxy.domain=grafana.neurix.uk`).
- **Security risk:** Admin UI on the public internet (anonymous signup disabled, strong password required at boot).
- **Business impact:** Credential-stuffing surface, CVE exposure window.
- **Recommended remediation:** Restrict to VPN/internal network or place behind Cloudflare Access. Alertmanager correctly stays internal.

### F-09 — `frontend` publishes port 8082 without loopback binding — **Low**
- **Affected component:** `docker-compose.yml` `frontend.ports: "8082:80"`.
- **Security risk:** A second entry point (the container's own Nginx) bypassing the Neurix/Cloudflare edge if the host firewall is misconfigured.
- **Recommended remediation:** Hetzner firewall should allow 80/443 only from Cloudflare/proxy ranges; document it.

### F-10 — Alertmanager delivery ships silent — **Low**
- **Affected component:** `monitoring/alertmanager/alertmanager.yml`.
- **Security risk:** Monitoring without alert delivery = undetected incidents.
- **Recommended remediation:** Wire webhook/email/Slack delivery (documented in `RUNBOOK.md`); verify end-to-end with a test alert.

### F-11 — Turnstile/bot protection absent — **Low (current design mitigates)**
- **Affected component:** N/A — no public registration or contact form exists; accounts are admin-seeded.
- **Recommended remediation:** When self-serve flows appear (LMS student signup), add Cloudflare Turnstile server-validated on register/forgot-password.

### F-12 — Backup encryption not evidenced — **Low**
- **Affected component:** `scripts/backup/*`.
- **Security risk:** DB dumps at rest on the volume/remote mirror unencrypted.
- **Recommended remediation:** Encrypt dumps (age/gpg) before mirroring; restrict remote store credentials.

### F-13 — Security integration tests not fully gated in CI — **Low**
- **Affected component:** `.github/workflows/ci.yml`; `SecurityBoundaryAuditIT` Postgres-gated tests require `-Daudit.postgres=true`.
- **Recommended remediation:** Run the full security IT suite against a Postgres service container in CI.

---

## 4. Verified-Secure Areas (no change recommended)

- **BCrypt password hashing** — correct; retention justified. Migration to Argon2id would add production risk with no meaningful gain here.
- **JWT validation** — signature + expiry re-checked every request; `uid` + `tv` (token version) exact match; expired-token-from-memory issue previously fixed.
- **CORS** — wildcard refused at boot; explicit allowlist; credentials scoped.
- **SQL injection** — all native queries parameterized; no string concatenation found.
- **XSS** — no `dangerouslySetInnerHTML`; URL scheme allowlisting on the frontend.
- **File upload hardening** — Tika magic-byte detection, size + daily quota with row locks, owner checks, UUID naming.
- **Nginx** — security headers incl. CSP with `frame-ancestors 'none'`, `server_tokens off`, body size limit.
- **PostgreSQL** — internal network only, no public port, compose refuses to start without `DB_PASSWORD`.
- **Error handling** — generic 500, centralized advice, no stack traces to clients.
- **Audit logging** — admin actions recorded; passwords excluded.

---

## 5. Prioritized Remediation Plan

| Priority | Item | Phase mapping |
|---|---|---|
| P0 | F-01: rotate JWT_SECRET + seed passwords in all environments; confirm prod never used placeholders | Phase 2 |
| P0 | F-02: lock Actuator chain to health/prometheus only | Phases 18/19 |
| P1 | F-08: restrict Grafana exposure; F-06: rate-limit password change & export API | Phase 12 |
| P1 | F-04: refresh-token / short-token decision + implementation | Phase 4 |
| P2 | F-03: MFA for SUPER_ADMIN/ADMIN | Phase 24 |
| P2 | F-07: password policy tuning; F-12: backup encryption; F-13: CI security ITs | Phases 3/27/31 |
| P3 | F-05 documentation; F-09/F-10/F-11 hardening | Phases 13/17/28 |

---

## 6. Verification / Evidence Pointers

- Boot-refusal test: run compose with placeholder secrets → `ProductionSecurityCheck` must refuse.
- Actuator: `curl -i https://<api>/actuator/env` → expect 401/403 after F-02 (currently effectively open).
- Git history: `git log --all --oneline -- .env` → b5a95f2, c03c6f7.
- Dependency posture: `docs/security/2026-09-13/backend-packages.json`, `backend-current-packages.json` (0 affected at scan time).
- Tests: `backend/src/test/java/com/dataentry/security/SecurityBoundaryAuditIT.java` (22 passing on Postgres per SECURITY_AUDIT.md).

- **Recommended remediation:** Run the full security IT suite against a Postgres service container in CI.

- **Affected component:** `SecurityConfig.filterChain` CSRF matcher.
- **Security risk:** Any request carrying a `Bearer` header skips CSRF checks; exploitation requires a valid token, which already implies a worse problem (XSS/token leak) that CSRF defense cannot cover. Bearer-in-header is inherently immune to ambient-credential CSRF.
- **Business impact:** Low in practice; risk compounds only with an XSS.
- **Recommended remediation:** Keep, but document explicitly (partially done in `SECURITY_AUDIT.md`). Consider scoping the exemption to `/api/v1/**` machine clients only.

| Backups | `backup-loop.sh` sidecar (daily, retention configurable), `mirror-to-remote.sh` offsite, `verify-latest.sh`. |
| Monitoring | Prometheus + Alertmanager + Grafana; JVM/HTTP metrics via Actuator/Micrometer. |
| Dependency scanning | Dependabot + weekly OWASP Dependency-Check (`failBuildOnCVSS=11`) + `npm audit --audit-level=high`. Last scan: 0 known-vulnerable packages (2026-09-13 artifacts). |


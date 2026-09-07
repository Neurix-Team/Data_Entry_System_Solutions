-- Indexes on the columns most read/filter/sort queries actually key on. Without these,
-- once the tickets table hits ~100 k rows the dashboard, the ticket-list pages and the
-- duplicate-upload check start doing sequential scans and latency climbs into seconds.
--
-- All CREATE INDEX statements use `IF NOT EXISTS` so re-running the migration on a DB
-- that a previous ddl-auto=update accidentally created some of these on stays a no-op.

-- Dashboard "recent activity" per team + list-view default sort. Covers ORDER BY
-- submitted_at DESC scoped to the current team — the single most common ticket query.
CREATE INDEX IF NOT EXISTS ix_tickets_team_submitted_at
    ON tickets (team_id, submitted_at DESC);

-- Admin ticket-list filtering by department + status (open / review / completed pickers).
CREATE INDEX IF NOT EXISTS ix_tickets_department_status
    ON tickets (department_id, status);

-- "My tickets" for a data-entry agent — filter by submitted_by, sort by newest first.
CREATE INDEX IF NOT EXISTS ix_tickets_submitted_by_submitted_at
    ON tickets (submitted_by_id, submitted_at DESC);

-- Duplicate-upload detection. Every upload finalisation calls
-- SELECT ... FROM ticket_documents WHERE content_hash = ? (in project scope) — without
-- an index that's a full scan per upload.
CREATE INDEX IF NOT EXISTS ix_ticket_documents_content_hash
    ON ticket_documents (content_hash)
    WHERE content_hash IS NOT NULL;

-- Ticket detail eager-loads its documents ordered by uploaded_at. The FK already implies
-- a btree on ticket_id, but the composite matches the ORDER BY too so PG can skip the sort.
CREATE INDEX IF NOT EXISTS ix_ticket_documents_ticket_uploaded_at
    ON ticket_documents (ticket_id, uploaded_at, id);

-- Dataset export cursor pagination: /api/v1/export/dataset walks refreshed_at ASC.
CREATE INDEX IF NOT EXISTS ix_dataset_records_refreshed_at
    ON dataset_records (refreshed_at, id);

-- Team-scoped dataset scan (super-admin per-team stats).
CREATE INDEX IF NOT EXISTS ix_dataset_records_team
    ON dataset_records (team_id);

-- Audit log timeline: RUNBOOK's "audit after suspected leak" query filters actor_id
-- ordered by created_at DESC — the existing single-column indexes force a sort merge.
CREATE INDEX IF NOT EXISTS ix_audit_logs_actor_created_at
    ON audit_logs (actor_id, created_at DESC);

-- Notification bell polls "unread for this recipient." Existing index covers this well
-- (ix_notifications_recipient_unread) — this migration deliberately does NOT touch it.

-- Upload session sweeps look at expires_at across all owners. Existing single-column
-- index (ix_upload_sessions_expires) is fine — noted here so future maintainers know we
-- checked.

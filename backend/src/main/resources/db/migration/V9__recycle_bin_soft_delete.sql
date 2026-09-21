-- Recycle bin: tickets (data entries) and projects become recoverable instead of
-- vanishing on DELETE. A soft delete stamps deleted_at (+ who did it); every Hibernate
-- query on Ticket is restricted to "deleted_at IS NULL" via @Where, the native
-- statistics queries filter explicitly, and a scheduled sweeper purges rows that have
-- been in the bin longer than app.recycle-bin.retention-days.
--
-- Children of a soft-deleted ticket (custom values, resources, documents and their
-- files) are intentionally left untouched so a restore brings the entry back whole.
--
-- Projects do NOT get @Where: live tickets keep pointing at a binned project, so the
-- association must still resolve; project queries filter on deleted_at instead.

ALTER TABLE tickets ADD COLUMN deleted_at timestamp(6) with time zone;
ALTER TABLE tickets ADD COLUMN deleted_by_id bigint REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE projects ADD COLUMN deleted_at timestamp(6) with time zone;
ALTER TABLE projects ADD COLUMN deleted_by_id bigint REFERENCES users(id) ON DELETE SET NULL;

-- Admin/user recycle-bin listing: team-scoped, newest deletion first.
CREATE INDEX ix_tickets_team_deleted_at ON tickets (team_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_projects_team_deleted_at ON projects (team_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;

-- Retention sweeper: finds everything past the window in one index scan.
CREATE INDEX ix_tickets_deleted_at ON tickets (deleted_at)
    WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_projects_deleted_at ON projects (deleted_at)
    WHERE deleted_at IS NOT NULL;

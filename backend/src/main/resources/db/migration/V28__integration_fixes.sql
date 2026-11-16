-- Bug-hunt integration pass (16 Nov 2026). H2- and PostgreSQL-compatible.

-- R6-B2: the order of a job's requirements is stored (the wizard's drag / Alt+arrow order). Existing rows keep their
-- id order. A pure reorder updates only this column: no new requirements version, no audit row, no re-match.
ALTER TABLE job_requirements ADD COLUMN display_order INTEGER DEFAULT 0 NOT NULL;
UPDATE job_requirements SET display_order = (
    SELECT COUNT(*) FROM job_requirements o
    WHERE o.job_id = job_requirements.job_id AND o.id < job_requirements.id);

-- R1-U2: absolute session lifetime. Every token of a refresh family carries the time the sign-in started, so a
-- family ends after app.jwt.refresh-absolute-lifetime-days (default 30) however often it is rotated.
ALTER TABLE refresh_tokens ADD COLUMN family_started_at TIMESTAMP NULL;
UPDATE refresh_tokens SET family_started_at = (
    SELECT MIN(f.created_at) FROM refresh_tokens f WHERE f.family_id = refresh_tokens.family_id);
ALTER TABLE refresh_tokens ALTER COLUMN family_started_at SET NOT NULL;

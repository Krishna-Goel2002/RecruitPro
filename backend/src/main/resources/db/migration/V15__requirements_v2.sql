-- Requirements v2 (plan 3.2, WP-BE-B): alternatives, knockout gates, proficiency; job requirement versioning.

ALTER TABLE job_requirements ADD COLUMN alternatives_json TEXT NULL;
ALTER TABLE job_requirements ADD COLUMN knockout BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE job_requirements ADD COLUMN proficiency VARCHAR(20) NULL;

-- Existing must-haves become knockout gates (D6: a gate for ranking, never an automatic rejection).
UPDATE job_requirements SET knockout = is_mandatory;

ALTER TABLE job_requirements
    ADD CONSTRAINT chk_job_requirements_proficiency
    CHECK (proficiency IS NULL OR proficiency IN ('BASIC', 'INTERMEDIATE', 'ADVANCED', 'EXPERT'));

ALTER TABLE jobs ADD COLUMN requirements_version INTEGER DEFAULT 1 NOT NULL;
ALTER TABLE jobs ADD COLUMN closed_reason VARCHAR(60) NULL;

ALTER TABLE jobs ADD CONSTRAINT chk_jobs_requirements_version CHECK (requirements_version >= 1);

CREATE INDEX idx_jobs_status_deadline ON jobs (status, deadline);

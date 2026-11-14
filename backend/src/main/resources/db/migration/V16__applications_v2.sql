-- Application lifecycle v2 (plan 3.2 and 3.4, WP-BE-B): interviewing and selection, blind-review reveal,
-- reversible decisions and the match run each decision was made on (D8).

ALTER TABLE applications DROP CONSTRAINT chk_applications_status;
ALTER TABLE applications
    ADD CONSTRAINT chk_applications_status
    CHECK (status IN ('SUBMITTED', 'SHORTLISTED', 'INTERVIEWING', 'SELECTED', 'REJECTED', 'WITHDRAWN'));

ALTER TABLE applications ADD COLUMN identity_revealed_at TIMESTAMP NULL;
ALTER TABLE applications ADD COLUMN identity_revealed_by BIGINT NULL;
ALTER TABLE applications ADD COLUMN status_changed_at TIMESTAMP NULL;
ALTER TABLE applications
    ADD CONSTRAINT fk_applications_revealed_by FOREIGN KEY (identity_revealed_by) REFERENCES users (id);

-- Shortlisted applications were visible to reviewers before blind review existed (D4).
UPDATE applications SET identity_revealed_at = updated_at WHERE status = 'SHORTLISTED';
UPDATE applications SET status_changed_at = updated_at WHERE status <> 'SUBMITTED';

ALTER TABLE application_decision_history DROP CONSTRAINT chk_app_decision_history_action;
ALTER TABLE application_decision_history
    ADD CONSTRAINT chk_app_decision_history_action
    CHECK (action_type IN ('SHORTLIST', 'REJECT', 'COMMENT_UPDATED', 'UNDO_SHORTLIST', 'REOPEN',
                           'MOVE_TO_INTERVIEW', 'SELECT', 'WITHDRAW', 'IDENTITY_REVEALED'));

ALTER TABLE application_decision_history ADD COLUMN match_run_id BIGINT NULL;
ALTER TABLE application_decision_history
    ADD CONSTRAINT fk_app_decision_history_match_run FOREIGN KEY (match_run_id) REFERENCES job_match_runs (id);

CREATE INDEX idx_applications_job_status ON applications (job_id, status);
CREATE INDEX idx_applications_resume ON applications (resume_id);

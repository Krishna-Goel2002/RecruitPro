ALTER TABLE applications DROP CONSTRAINT chk_applications_status;
ALTER TABLE applications
    ADD CONSTRAINT chk_applications_status
    CHECK (status IN ('SUBMITTED', 'SHORTLISTED', 'REJECTED', 'WITHDRAWN'));
ALTER TABLE applications ADD COLUMN review_comment TEXT NULL;

CREATE TABLE application_decision_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    application_id BIGINT NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    new_status VARCHAR(30) NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    comment TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_app_decision_history_application FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE RESTRICT,
    CONSTRAINT fk_app_decision_history_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_app_decision_history_action CHECK (action_type IN ('SHORTLIST', 'REJECT', 'COMMENT_UPDATED'))
);
CREATE INDEX idx_app_decision_history_app ON application_decision_history(application_id, created_at);

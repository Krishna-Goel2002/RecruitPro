CREATE TABLE applications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    candidate_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    cover_letter TEXT NULL,
    applied_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_applications_candidate_job UNIQUE (candidate_id, job_id),
    CONSTRAINT fk_applications_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_applications_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_applications_resume FOREIGN KEY (resume_id) REFERENCES candidate_resumes(id) ON DELETE RESTRICT,
    CONSTRAINT chk_applications_status CHECK (status IN ('SUBMITTED', 'WITHDRAWN'))
);
CREATE INDEX idx_applications_candidate_history ON applications(candidate_id, applied_at DESC, id DESC);
CREATE INDEX idx_applications_job ON applications(job_id);

CREATE TABLE job_match_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    application_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    resume_analysis_id BIGINT NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    algorithm_version VARCHAR(80) NOT NULL,
    observed_evidence_coverage DECIMAL(5,1) NULL,
    score_label VARCHAR(255) NOT NULL,
    requirement_results_json TEXT NOT NULL,
    calculated_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_job_match_runs_application UNIQUE (application_id),
    CONSTRAINT fk_job_match_runs_application FOREIGN KEY (application_id) REFERENCES applications(id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_match_runs_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_match_runs_analysis FOREIGN KEY (resume_analysis_id) REFERENCES resume_analyses(id) ON DELETE RESTRICT,
    CONSTRAINT chk_job_match_runs_score CHECK (observed_evidence_coverage IS NULL OR (observed_evidence_coverage >= 0 AND observed_evidence_coverage <= 100))
);
CREATE INDEX idx_job_match_runs_job_score ON job_match_runs(job_id, observed_evidence_coverage DESC, id);

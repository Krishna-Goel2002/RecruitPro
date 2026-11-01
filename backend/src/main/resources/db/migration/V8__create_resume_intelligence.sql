CREATE TABLE resume_analyses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resume_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    parser_version VARCHAR(80) NULL,
    source_sha256 VARCHAR(64) NULL,
    error_code VARCHAR(60) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_resume_analyses_resume UNIQUE (resume_id),
    CONSTRAINT fk_resume_analyses_resume FOREIGN KEY (resume_id) REFERENCES candidate_resumes(id) ON DELETE RESTRICT,
    CONSTRAINT chk_resume_analyses_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'PARTIAL', 'FAILED', 'NEEDS_OCR', 'UNSUPPORTED_FORMAT')),
    CONSTRAINT chk_resume_analyses_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX idx_resume_analyses_queue ON resume_analyses(status, created_at, id);

CREATE TABLE resume_analysis_facts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    analysis_id BIGINT NOT NULL,
    fact_type VARCHAR(30) NOT NULL,
    extracted_value VARCHAR(1000) NOT NULL,
    normalized_value VARCHAR(1000) NOT NULL,
    evidence_text VARCHAR(1000) NOT NULL,
    source_section VARCHAR(80) NOT NULL,
    source_reference VARCHAR(80) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'UNREVIEWED',
    reviewed_value VARCHAR(1000) NULL,
    reviewer_user_id BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_resume_analysis_facts_analysis FOREIGN KEY (analysis_id) REFERENCES resume_analyses(id) ON DELETE CASCADE,
    CONSTRAINT fk_resume_analysis_facts_reviewer FOREIGN KEY (reviewer_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_resume_analysis_fact_type CHECK (fact_type IN ('SKILL', 'EDUCATION', 'EXPERIENCE', 'CERTIFICATION', 'EMAIL', 'PHONE')),
    CONSTRAINT chk_resume_analysis_review_status CHECK (review_status IN ('UNREVIEWED', 'CONFIRMED', 'CORRECTED'))
);
CREATE INDEX idx_resume_analysis_facts_analysis ON resume_analysis_facts(analysis_id, id);

INSERT INTO resume_analyses (resume_id, status, error_code, attempt_count, created_at, updated_at)
SELECT id,
       CASE WHEN LOWER(file_extension) IN ('pdf', 'docx') THEN 'PENDING' ELSE 'UNSUPPORTED_FORMAT' END,
       CASE WHEN LOWER(file_extension) IN ('pdf', 'docx') THEN NULL ELSE 'UNSUPPORTED_FORMAT' END,
       0,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM candidate_resumes;

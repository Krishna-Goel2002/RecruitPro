CREATE TABLE candidate_resumes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    candidate_id BIGINT NOT NULL,
    storage_key VARCHAR(36) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_extension VARCHAR(10) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_candidate_resumes_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_candidate_resumes_candidate FOREIGN KEY (candidate_id) REFERENCES candidate_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT chk_candidate_resume_size CHECK (file_size > 0)
);
CREATE INDEX idx_candidate_resumes_history ON candidate_resumes(candidate_id, uploaded_at, id);

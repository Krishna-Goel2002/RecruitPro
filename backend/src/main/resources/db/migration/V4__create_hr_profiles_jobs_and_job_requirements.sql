CREATE TABLE hr_profiles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    employee_id VARCHAR(100),
    department VARCHAR(100),
    designation VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hr_profiles_user UNIQUE (user_id),
    CONSTRAINT uk_hr_profiles_employee UNIQUE (employee_id),
    CONSTRAINT fk_hr_profiles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE TABLE jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_by BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    department VARCHAR(100) NOT NULL,
    location VARCHAR(255) NOT NULL,
    job_type VARCHAR(30) NOT NULL,
    experience_level VARCHAR(30) NOT NULL,
    minimum_salary DECIMAL(12,2),
    maximum_salary DECIMAL(12,2),
    vacancies INT NOT NULL DEFAULT 1,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    published_at TIMESTAMP NULL,
    deadline TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_jobs_hr_profile FOREIGN KEY (created_by) REFERENCES hr_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT chk_jobs_vacancies CHECK (vacancies > 0),
    CONSTRAINT chk_jobs_salary CHECK (minimum_salary IS NULL OR minimum_salary >= 0),
    CONSTRAINT chk_jobs_max_salary CHECK (maximum_salary IS NULL OR maximum_salary >= 0),
    CONSTRAINT chk_jobs_salary_range CHECK (minimum_salary IS NULL OR maximum_salary IS NULL OR minimum_salary <= maximum_salary)
);

CREATE TABLE job_requirements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    requirement_type VARCHAR(30) NOT NULL,
    description VARCHAR(500) NOT NULL,
    is_mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    minimum_years INT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_job_requirements_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE,
    CONSTRAINT chk_job_requirement_years CHECK (minimum_years IS NULL OR minimum_years >= 0)
);

CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_created_by ON jobs(created_by);
CREATE INDEX idx_jobs_department ON jobs(department);
CREATE INDEX idx_jobs_location ON jobs(location);
CREATE INDEX idx_jobs_job_type ON jobs(job_type);
CREATE INDEX idx_job_requirements_job ON job_requirements(job_id);

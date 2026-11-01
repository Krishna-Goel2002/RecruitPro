-- Phase 1 Foundation Migration
-- System metadata table for platform configuration and schema verification

CREATE TABLE IF NOT EXISTS system_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_key VARCHAR(100) NOT NULL UNIQUE,
    property_value VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL
);

INSERT INTO system_metadata (property_key, property_value, description)
VALUES ('schema_version', '1.0.0', 'Initial Phase 1 Backend Foundation Schema');

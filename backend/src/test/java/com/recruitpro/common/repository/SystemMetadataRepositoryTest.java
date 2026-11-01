package com.recruitpro.common.repository;

import com.recruitpro.common.entity.SystemMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SystemMetadataRepositoryTest {

    @Autowired
    private SystemMetadataRepository repository;

    @Test
    @DisplayName("Flyway V1 migration should populate initial schema_version metadata")
    void flywayMigrationDataExists() {
        Optional<SystemMetadata> metadata = repository.findByPropertyKey("schema_version");

        assertTrue(metadata.isPresent());
        assertEquals("1.0.0", metadata.get().getPropertyValue());
        assertNotNull(metadata.get().getCreatedAt());
    }

    @Test
    @DisplayName("Should save and retrieve custom SystemMetadata with BaseEntity audit timestamps")
    void saveAndRetrieveMetadata() {
        SystemMetadata metadata = SystemMetadata.builder()
                .propertyKey("test_key")
                .propertyValue("test_value")
                .description("Test description")
                .build();

        SystemMetadata saved = repository.save(metadata);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        Optional<SystemMetadata> found = repository.findByPropertyKey("test_key");
        assertTrue(found.isPresent());
        assertEquals("test_value", found.get().getPropertyValue());
    }
}

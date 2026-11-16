package com.recruitpro.organization.bootstrap;

import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.organization.service.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R1-U7: an instance that loses the first-start bootstrap race to another instance still starts. */
class BootstrapRunnerRaceTest {

    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final BootstrapRunner runner = new BootstrapRunner(organizationService, "Acme", "admin@acme.test",
            "SecurePassword123!", "Ada", "Admin");

    @Test
    void losingTheRaceToAnotherInstanceIsNotAFailedStart() {
        when(organizationService.bootstrapFirstOrganization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("duplicate key uk_organizations_slug"));
        when(organizationService.anyOrganizationExists()).thenReturn(true);
        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));

        when(organizationService.bootstrapFirstOrganization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateResourceException("Email is already registered"));
        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
    }

    @Test
    void aUniqueViolationWithoutAnyOrganisationStillFailsTheStart() {
        when(organizationService.bootstrapFirstOrganization(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("something else"));
        when(organizationService.anyOrganizationExists()).thenReturn(false);
        assertThrows(DataIntegrityViolationException.class, () -> runner.run(new DefaultApplicationArguments()));
    }
}

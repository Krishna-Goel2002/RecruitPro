package com.recruitpro.user.provisioning;

import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.env.Environment;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class HrManagerProvisioningTest {
    private static final ApplicationArguments EMPTY_ARGUMENTS = new DefaultApplicationArguments(new String[0]);

    @Autowired HrManagerProvisioningService provisioningService;
    @Autowired UserRepository userRepository;
    @Autowired HRProfileRepository hrProfileRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Environment environment;
    @Autowired LocalHrManagerProvisioner configuredProvisioner;
    @SpyBean HRProfileRepository hrProfileRepositorySpy;

    @Test
    void disabledByDefaultDoesNothingEvenOutsideDev() {
        assertFalse(environment.getProperty("app.dev-provisioning.hr-manager.enabled", Boolean.class, false));
        assertEquals(false, ReflectionTestUtils.getField(configuredProvisioner, "enabled"));
        assertDoesNotThrow(() -> configuredProvisioner.run(EMPTY_ARGUMENTS));
    }

    @Test
    void enabledFlagRequiresDevAndRefusesProdIncludingMixedProfiles() {
        HrManagerProvisioningService service = mock(HrManagerProvisioningService.class);
        HrManagerProvisioningPrompt prompt = mock(HrManagerProvisioningPrompt.class);

        LocalHrManagerProvisioner outsideDev = provisioner(true, environmentWithProfiles("test"), service, prompt);
        assertThrows(IllegalStateException.class, () -> outsideDev.run(EMPTY_ARGUMENTS));

        LocalHrManagerProvisioner production = provisioner(true, environmentWithProfiles("dev", "prod"), service, prompt);
        assertThrows(IllegalStateException.class, () -> production.run(EMPTY_ARGUMENTS));
        verifyNoInteractions(service, prompt);
    }

    @Test
    void devProvisioningCreatesHrManagerAndMatchingProfileWithEncodedPassword() {
        String email = uniqueEmail();
        char[] password = "LocalPassword123!".toCharArray();
        HrManagerProvisioningPrompt prompt = mock(HrManagerProvisioningPrompt.class);
        ProvisioningCredentials credentials = new ProvisioningCredentials(email.toUpperCase(), "Local", "HR", password);
        when(prompt.collect()).thenReturn(credentials);
        LocalHrManagerProvisioner provisioner = provisioner(true,
                environmentWithProfiles("dev"), provisioningService, prompt);

        provisioner.run(EMPTY_ARGUMENTS);

        User user = userRepository.findByEmail(email).orElseThrow();
        assertEquals(Role.HR_MANAGER, user.getRole());
        assertTrue(passwordEncoder.matches("LocalPassword123!", user.getPasswordHash()));
        assertNotEquals("LocalPassword123!", user.getPasswordHash());
        HRProfile profile = hrProfileRepository.findByUserEmailIgnoreCase(email).orElseThrow();
        assertEquals(user.getId(), profile.getUser().getId());
        verify(prompt).completed(email.toUpperCase());
        assertArrayEquals(new char[password.length], credentials.password());
    }

    @Test
    void duplicateEmailFailsWithoutChangingExistingCandidate() {
        String email = uniqueEmail();
        String existingHash = passwordEncoder.encode("ExistingPassword123!");
        User existing = userRepository.saveAndFlush(User.builder()
                .email(email).passwordHash(existingHash).firstName("Existing").lastName("Candidate")
                .role(Role.CANDIDATE).enabled(true).build());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> provisioningService.provision(email.toUpperCase(), "Attempt", "Elevation", "LocalPassword123!".toCharArray()));

        assertTrue(exception.getMessage().contains("already exists"));
        User unchanged = userRepository.findById(existing.getId()).orElseThrow();
        assertEquals(Role.CANDIDATE, unchanged.getRole());
        assertEquals(existingHash, unchanged.getPasswordHash());
        assertTrue(hrProfileRepository.findByUserEmailIgnoreCase(email).isEmpty());
    }

    @Test
    void profilePersistenceFailureRollsBackTheNewUser() {
        String email = uniqueEmail();
        doThrow(new IllegalStateException("simulated profile persistence failure"))
                .when(hrProfileRepositorySpy).saveAndFlush(any(HRProfile.class));

        assertThrows(IllegalStateException.class,
                () -> provisioningService.provision(email, "Local", "HR", "LocalPassword123!".toCharArray()));

        assertTrue(userRepository.findByEmail(email).isEmpty());
        verify(hrProfileRepositorySpy).saveAndFlush(any(HRProfile.class));
    }

    private LocalHrManagerProvisioner provisioner(boolean enabled, MockEnvironment environment,
                                                   HrManagerProvisioningService service, HrManagerProvisioningPrompt prompt) {
        LocalHrManagerProvisioner provisioner = new LocalHrManagerProvisioner(environment, service, prompt);
        ReflectionTestUtils.setField(provisioner, "enabled", enabled);
        return provisioner;
    }

    private MockEnvironment environmentWithProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private String uniqueEmail() {
        return "local-hr-" + UUID.randomUUID() + "@example.test";
    }
}

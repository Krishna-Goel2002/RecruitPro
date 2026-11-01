package com.recruitpro.user.provisioning;

import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class AdminProvisioningTest {
    private static final ApplicationArguments EMPTY_ARGUMENTS = new DefaultApplicationArguments(new String[0]);

    @Autowired AdminProvisioningService provisioningService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Environment environment;
    @Autowired LocalAdminProvisioner configuredProvisioner;

    @Test
    void disabledByDefaultDoesNothingEvenOutsideDev() {
        assertFalse(environment.getProperty("app.dev-provisioning.admin.enabled", Boolean.class, false));
        assertEquals(false, ReflectionTestUtils.getField(configuredProvisioner, "enabled"));
        assertDoesNotThrow(() -> configuredProvisioner.run(EMPTY_ARGUMENTS));
    }

    @Test
    void enabledFlagRequiresDevAndRefusesProdIncludingMixedProfiles() {
        AdminProvisioningService service = mock(AdminProvisioningService.class);
        AdminProvisioningPrompt prompt = mock(AdminProvisioningPrompt.class);
        UserRepository mockUsers = mock(UserRepository.class);

        LocalAdminProvisioner outsideDev = provisioner(true, environmentWithProfiles("test"), service, prompt, mockUsers);
        assertThrows(IllegalStateException.class, () -> outsideDev.run(EMPTY_ARGUMENTS));

        LocalAdminProvisioner production = provisioner(true, environmentWithProfiles("dev", "prod"), service, prompt, mockUsers);
        assertThrows(IllegalStateException.class, () -> production.run(EMPTY_ARGUMENTS));
        verifyNoInteractions(service, prompt);
    }

    @Test
    void skipsIfAdminAlreadyExists() {
        AdminProvisioningService service = mock(AdminProvisioningService.class);
        AdminProvisioningPrompt prompt = mock(AdminProvisioningPrompt.class);
        UserRepository mockUsers = mock(UserRepository.class);
        when(mockUsers.countByRole(Role.ADMIN)).thenReturn(1L);

        LocalAdminProvisioner provisioner = provisioner(true, environmentWithProfiles("dev"), service, prompt, mockUsers);
        provisioner.run(EMPTY_ARGUMENTS);

        verifyNoInteractions(service, prompt);
    }

    @Test
    void devProvisioningCallsServiceWhenNoAdminExists() {
        String email = "admin-" + UUID.randomUUID() + "@example.test";
        char[] password = "LocalPassword123!".toCharArray();
        AdminProvisioningPrompt prompt = mock(AdminProvisioningPrompt.class);
        ProvisioningCredentials credentials = new ProvisioningCredentials(email.toUpperCase(), "Super", "Admin", password);
        when(prompt.collect()).thenReturn(credentials);

        AdminProvisioningService service = mock(AdminProvisioningService.class);
        UserRepository mockUsers = mock(UserRepository.class);
        when(mockUsers.countByRole(Role.ADMIN)).thenReturn(0L);

        LocalAdminProvisioner provisioner = provisioner(true, environmentWithProfiles("dev"), service, prompt, mockUsers);
        provisioner.run(EMPTY_ARGUMENTS);

        verify(service).provision(eq(credentials.email()), eq(credentials.firstName()), eq(credentials.lastName()), any(char[].class));
        verify(prompt).completed(credentials.email());
        assertArrayEquals(new char[password.length], credentials.password());
    }

    @Test
    void provisioningServiceCreatesAdminWithEncodedPassword() {
        String email = "admin-" + UUID.randomUUID() + "@example.test";
        char[] password = "LocalPassword123!".toCharArray();

        User user = provisioningService.provision(email, "Super", "Admin", password);

        assertNotNull(user.getId());
        assertEquals(Role.ADMIN, user.getRole());
        assertTrue(user.isEnabled());
        assertTrue(passwordEncoder.matches("LocalPassword123!", user.getPasswordHash()));
        assertNotEquals("LocalPassword123!", user.getPasswordHash());

        User found = userRepository.findByEmail(email).orElseThrow();
        assertEquals(user.getId(), found.getId());
        assertEquals(Role.ADMIN, found.getRole());
    }

    @Test
    void duplicateEmailFailsWithoutChangingExistingUser() {
        String email = "candidate-" + UUID.randomUUID() + "@example.test";
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
    }

    @Test
    void invalidPasswordOrEmailFailsValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> provisioningService.provision("not-an-email", "Bad", "User", "short".toCharArray()));
    }

    private LocalAdminProvisioner provisioner(boolean enabled, Environment env,
                                             AdminProvisioningService service, AdminProvisioningPrompt prompt,
                                             UserRepository users) {
        LocalAdminProvisioner provisioner = new LocalAdminProvisioner(env, service, prompt, users);
        ReflectionTestUtils.setField(provisioner, "enabled", enabled);
        return provisioner;
    }

    private MockEnvironment environmentWithProfiles(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }
}

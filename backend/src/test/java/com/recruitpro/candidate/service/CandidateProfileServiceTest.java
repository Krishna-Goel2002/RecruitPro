package com.recruitpro.candidate.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateProfileServiceTest {
    private static final String CANDIDATE_EMAIL = "candidate@example.test";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsTheCurrentCandidateProfileWithoutCreatingAnotherRow() {
        User user = user(CANDIDATE_EMAIL, Role.CANDIDATE);
        user.setFirstName("Asha");
        user.setLastName("Rao");
        CandidateProfile profile = new CandidateProfile();
        profile.setId(42L);
        profile.setUser(user);
        AtomicReference<CandidateProfile> stored = new AtomicReference<>(profile);
        AtomicInteger saves = new AtomicInteger();
        CandidateProfileService service = serviceFor(user, stored, saves);
        authenticate(CANDIDATE_EMAIL);

        var response = service.getMine();

        assertEquals(42L, response.id());
        assertEquals(CANDIDATE_EMAIL, response.email());
        assertEquals("Asha", response.firstName());
        assertEquals("Rao", response.lastName());
        assertEquals(0, saves.get());
    }

    @Test
    void provisionsAMissingCandidateProfileOnlyOnce() {
        User user = user(CANDIDATE_EMAIL, Role.CANDIDATE);
        AtomicReference<CandidateProfile> stored = new AtomicReference<>();
        AtomicInteger saves = new AtomicInteger();
        CandidateProfileService service = serviceFor(user, stored, saves);
        authenticate(CANDIDATE_EMAIL);

        var first = service.getMine();
        var second = service.getMine();

        assertEquals(first.id(), second.id());
        assertEquals(CANDIDATE_EMAIL, first.email());
        assertEquals(user, stored.get().getUser());
        assertEquals(1, saves.get());
    }

    @Test
    void neverReturnsAProfileOwnedByAnotherCandidate() {
        User currentUser = user(CANDIDATE_EMAIL, Role.CANDIDATE);
        User otherUser = user("other@example.test", Role.CANDIDATE);
        CandidateProfile otherProfile = new CandidateProfile();
        otherProfile.setId(17L);
        otherProfile.setUser(otherUser);
        AtomicReference<CandidateProfile> stored = new AtomicReference<>(otherProfile);
        AtomicInteger saves = new AtomicInteger();
        CandidateProfileService service = serviceFor(currentUser, stored, saves);
        authenticate(CANDIDATE_EMAIL);

        var response = service.getMine();

        assertEquals(CANDIDATE_EMAIL, response.email());
        assertEquals(84L, response.id());
        assertSame(currentUser, stored.get().getUser());
        assertEquals(1, saves.get());
    }

    @Test
    void doesNotProvisionProfilesForOtherRolesOrMissingAccounts() {
        AtomicReference<CandidateProfile> stored = new AtomicReference<>();
        AtomicInteger saves = new AtomicInteger();
        User hr = user(CANDIDATE_EMAIL, Role.HR_MANAGER);
        CandidateProfileService hrService = serviceFor(hr, stored, saves);
        authenticate(CANDIDATE_EMAIL);

        assertThrows(AccessDeniedException.class, hrService::getMine);
        assertEquals(0, saves.get());

        CandidateProfileService missingUserService = serviceFor(null, stored, saves);
        assertThrows(ResourceNotFoundException.class, missingUserService::getMine);
        assertEquals(0, saves.get());
    }

    private CandidateProfileService serviceFor(User expectedUser, AtomicReference<CandidateProfile> stored,
                                                AtomicInteger saves) {
        CandidateProfileRepository profiles = proxy(CandidateProfileRepository.class, (method, args) -> {
            if (method.equals("findByUserEmailIgnoreCase")) {
                return Optional.ofNullable(stored.get()).filter(profile ->
                        profile.getUser().getEmail().equalsIgnoreCase((String) args[0]));
            }
            if (method.equals("save")) {
                CandidateProfile profile = (CandidateProfile) args[0];
                if (profile.getId() == null) profile.setId(84L);
                stored.set(profile);
                saves.incrementAndGet();
                return profile;
            }
            throw new UnsupportedOperationException(method);
        });
        UserRepository users = proxy(UserRepository.class, (method, args) -> {
            if (method.equals("findByEmail")) {
                return expectedUser != null && expectedUser.getEmail().equals(args[0])
                        ? Optional.of(expectedUser) : Optional.empty();
            }
            throw new UnsupportedOperationException(method);
        });
        return new CandidateProfileService(profiles, users);
    }

    private User user(String email, Role role) {
        return User.builder().email(email).passwordHash("unused-test-hash")
                .firstName("Test").lastName("Candidate").role(role).enabled(true).build();
    }

    private void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(email, "unused-test-credentials", "ROLE_CANDIDATE"));
    }

    private static <T> T proxy(Class<T> repositoryType, RepositoryCall call) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> repositoryType.getSimpleName() + " test proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            return call.invoke(method.getName(), args == null ? new Object[0] : args);
        };
        return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(),
                new Class<?>[]{repositoryType}, handler));
    }

    @FunctionalInterface
    private interface RepositoryCall {
        Object invoke(String method, Object[] args);
    }
}

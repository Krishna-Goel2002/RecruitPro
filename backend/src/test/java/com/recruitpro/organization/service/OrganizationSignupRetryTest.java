package com.recruitpro.organization.service;

import com.recruitpro.audit.service.AuditService;
import com.recruitpro.auth.service.AuthService;
import com.recruitpro.common.crypto.SecretCipher;
import com.recruitpro.organization.dto.OrganizationSignupRequest;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.security.SecurityUtils;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.user.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1-U6: two organisation sign-ups with the same name at the same moment both see the slug free; the loser's unique
 * violation is retried in a fresh transaction, which picks the next slug, instead of answering a generic 409.
 */
class OrganizationSignupRetryTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserAccountService accounts = mock(UserAccountService.class);
    private final AuthService authService = mock(AuthService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private OrganizationService service;

    private static final OrganizationSignupRequest REQUEST =
            new OrganizationSignupRequest("Acme", "Ada", "Admin", "ada@acme.test", "SecurePassword123!");

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(users.existsByEmail("ada@acme.test")).thenReturn(false);
        User admin = User.builder().email("ada@acme.test").firstName("Ada").lastName("Admin").role(Role.ADMIN)
                .enabled(true).passwordHash("x").build();
        admin.setId(9L);
        when(accounts.createStaffUser(anyLong(), anyString(), anyString(), anyString(), anyString(), eq(Role.ADMIN),
                anyBoolean())).thenReturn(admin);
        service = new OrganizationService(organizations, users, accounts, authService, mock(AuditService.class),
                mock(SecurityUtils.class), mock(SecretCipher.class), transactionManager, true);
    }

    @Test
    void aSlugCollisionWithAConcurrentSignUpIsRetriedWithTheNextSlug() {
        // The concurrent sign-up had not committed "acme" yet when the first attempt checked it.
        when(organizations.existsBySlug("acme")).thenReturn(false, true);
        when(organizations.existsBySlug("acme-2")).thenReturn(false);
        when(organizations.saveAndFlush(any(Organization.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key uk_organizations_slug"))
                .thenAnswer(invocation -> {
                    Organization saved = invocation.getArgument(0);
                    saved.setId(5L);
                    return saved;
                });

        service.signup(REQUEST);

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizations, times(2)).saveAndFlush(saved.capture());
        List<String> slugs = saved.getAllValues().stream().map(Organization::getSlug).toList();
        assertEquals(List.of("acme", "acme-2"), slugs);
        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    void retriesAreBounded() {
        when(organizations.existsBySlug(anyString())).thenReturn(false);
        when(organizations.saveAndFlush(any(Organization.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(DataIntegrityViolationException.class, () -> service.signup(REQUEST));
        verify(organizations, times(OrganizationService.MAX_SIGNUP_ATTEMPTS)).saveAndFlush(any(Organization.class));
    }
}

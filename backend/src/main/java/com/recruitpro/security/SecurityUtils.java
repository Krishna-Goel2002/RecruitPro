package com.recruitpro.security;

import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.UnauthorizedException;
import com.recruitpro.security.userdetails.CustomUserDetails;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the current caller. Requests authenticated by the JWT filter carry a {@link CustomUserDetails}
 * principal (the user row loaded for this request). Any other authenticated principal (for example test
 * principals) is resolved by its name, which is the user's email address.
 *
 * <p>Tenancy helpers: {@link #requireOrganizationId()} returns the caller's organisation and must be used to
 * scope every staff query (D3).</p>
 */
@Component
public class SecurityUtils {

    private final UserRepository userRepository;

    public SecurityUtils(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The current caller, or empty when the request is anonymous or the user no longer exists. */
    public Optional<CurrentUser> findCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details) {
            return Optional.of(CurrentUser.of(details.getUser()));
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || userRepository == null) {
            return Optional.empty();
        }
        Optional<User> user = userRepository.findByEmail(name);
        if (user.isEmpty()) {
            user = userRepository.findByEmail(name.trim().toLowerCase(Locale.ROOT));
        }
        return user.map(CurrentUser::of);
    }

    /** The current caller; 401 when there is none. */
    public CurrentUser currentUser() {
        return findCurrentUser().orElseThrow(() -> new UnauthorizedException("Authentication is required"));
    }

    /** The current caller's organisation id; 403 when the caller is not a member of an organisation. */
    public Long requireOrganizationId() {
        return requireOrganizationMember().organizationId();
    }

    /** The current caller, guaranteed to belong to an organisation (staff). */
    public CurrentUser requireOrganizationMember() {
        CurrentUser user = currentUser();
        if (user.organizationId() == null) {
            throw new ForbiddenException("Your account is not linked to an organisation");
        }
        return user;
    }
}

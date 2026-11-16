package com.recruitpro.security.userdetails;

import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new CustomUserDetails(user);
    }

    /**
     * Loads the user an access token was issued to. The row is read on every request so that disabling an
     * account or changing a role takes effect immediately. Unknown, disabled or deleted users, and tokens
     * issued before the last password change, resolve to empty (the request stays unauthenticated; L9).
     */
    public Optional<CustomUserDetails> loadForAccessToken(Long userId, String subject, Date issuedAt) {
        return loadForAccessToken(userId, subject, issuedAt, null);
    }

    /**
     * As {@link #loadForAccessToken(Long, String, Date)}; with the token's millisecond issue time (R1-U3) the password
     * change is compared to the millisecond instead of the second.
     */
    public Optional<CustomUserDetails> loadForAccessToken(Long userId, String subject, Date issuedAt,
                                                          Long issuedAtMillis) {
        Optional<User> found;
        if (userId != null) {
            found = userRepository.findById(userId);
        } else if (subject != null && !subject.isBlank()) {
            found = userRepository.findByEmail(subject.trim().toLowerCase(Locale.ROOT));
        } else {
            found = Optional.empty();
        }
        return found
                .filter(user -> subject == null || user.getEmail().equalsIgnoreCase(subject.trim()))
                .filter(user -> user.isEnabled() && user.getDeletedAt() == null)
                .filter(user -> issuedAfterPasswordChange(user, issuedAt, issuedAtMillis))
                .map(CustomUserDetails::new);
    }

    static boolean issuedAfterPasswordChange(User user, Date issuedAt, Long issuedAtMillis) {
        if (user.getPasswordChangedAt() == null) {
            return true;
        }
        if (issuedAtMillis != null) {
            // Millisecond precision: the session created right after the change (same millisecond) stays valid.
            Instant changed = user.getPasswordChangedAt().truncatedTo(ChronoUnit.MILLIS).toInstant(ZoneOffset.UTC);
            return issuedAtMillis >= changed.toEpochMilli();
        }
        if (issuedAt == null) {
            return true;
        }
        // Tokens without the millisecond claim (issued before R1-U3): second precision, as before.
        Instant changed = user.getPasswordChangedAt().truncatedTo(ChronoUnit.SECONDS).toInstant(ZoneOffset.UTC);
        return !issuedAt.toInstant().isBefore(changed);
    }
}

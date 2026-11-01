package com.recruitpro.auth.service;

import com.recruitpro.auth.dto.AuthResponse;
import com.recruitpro.auth.dto.LoginRequest;
import com.recruitpro.auth.dto.RegisterRequest;
import com.recruitpro.auth.dto.UserResponse;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.security.jwt.JwtService;
import com.recruitpro.security.userdetails.CustomUserDetails;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.auth.dto.RefreshTokenRequest;
import com.recruitpro.auth.entity.RefreshToken;
import com.recruitpro.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CandidateProfileRepository candidateProfileRepository;

    @org.springframework.beans.factory.annotation.Value("${app.jwt.refresh-token-expiry-ms}")
    private long refreshTokenExpiryMs;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email is already registered");
        }

        // Secure role assignment logic:
        // For this public registration endpoint, we force the role to CANDIDATE
        // to prevent unauthorized privilege escalation.
        Role role = Role.CANDIDATE;

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .enabled(true)
                .build();

        User savedUser = userRepository.save(user);
        CandidateProfile candidateProfile = new CandidateProfile();
        candidateProfile.setUser(savedUser);
        candidateProfileRepository.save(candidateProfile);

        CustomUserDetails userDetails = new CustomUserDetails(savedUser);
        return createAuthResponse(savedUser, userDetails);
    }

    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        normalizeEmail(request.getEmail()),
                        request.getPassword()
                )
        );

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();

        return createAuthResponse(user, userDetails);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.getRefreshToken());
        RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .filter(token -> token.getRevokedAt() == null && token.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Invalid refresh token"));

        current.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(current);
        User user = current.getUser();
        if (!user.isEnabled()) {
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid refresh token");
        }
        return createAuthResponse(user, new CustomUserDetails(user));
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenRepository.findByTokenHashForUpdate(hashToken(request.getRefreshToken()))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.setRevokedAt(LocalDateTime.now());
                    refreshTokenRepository.save(token);
                });
    }

    private AuthResponse createAuthResponse(User user, CustomUserDetails userDetails) {
        byte[] tokenBytes = new byte[48];
        secureRandom.nextBytes(tokenBytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        RefreshToken persisted = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(refreshToken))
                .expiresAt(LocalDateTime.now().plusNanos(refreshTokenExpiryMs * 1_000_000))
                .build();
        refreshTokenRepository.save(persisted);
        return AuthResponse.builder()
                .accessToken(jwtService.generateToken(userDetails))
                .refreshToken(refreshToken)
                .user(mapToUserResponse(user))
                .build();
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse mapToUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .build();
    }
}

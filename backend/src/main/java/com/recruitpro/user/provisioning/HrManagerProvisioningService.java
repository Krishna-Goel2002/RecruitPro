package com.recruitpro.user.provisioning;

import com.recruitpro.auth.dto.RegisterRequest;
import com.recruitpro.hr.entity.HRProfile;
import com.recruitpro.hr.repository.HRProfileRepository;
import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.CharBuffer;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class HrManagerProvisioningService {
    private final UserRepository userRepository;
    private final HRProfileRepository hrProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    @Transactional
    public void provision(String email, String firstName, String lastName, char[] password) {
        String normalizedEmail = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        String passwordText = password == null ? null : CharBuffer.wrap(password).toString();
        RegisterRequest input = RegisterRequest.builder()
                .email(normalizedEmail)
                .firstName(firstName)
                .lastName(lastName)
                .password(passwordText)
                .build();
        if (!validator.validate(input).isEmpty()) {
            throw new IllegalArgumentException("Provisioning details do not meet registration requirements");
        }
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalStateException("An account with that email already exists; no account was changed");
        }

        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(passwordText))
                .firstName(firstName.trim())
                .lastName(lastName.trim())
                .role(Role.HR_MANAGER)
                .enabled(true)
                .build();
        User savedUser = userRepository.saveAndFlush(user);

        HRProfile profile = new HRProfile();
        profile.setUser(savedUser);
        hrProfileRepository.saveAndFlush(profile);
    }
}

package com.recruitpro.auth.sso;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

    List<UserIdentity> findAllByUserId(Long userId);
}

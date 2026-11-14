package com.recruitpro.organization.repository;

import com.recruitpro.organization.entity.Invitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :tokenHash")
    Optional<Invitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<Invitation> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Invitation> findAllByOrganizationIdOrderByCreatedAtDescIdDesc(Long organizationId);

    List<Invitation> findAllByOrganizationIdAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(Long organizationId, String email);
}

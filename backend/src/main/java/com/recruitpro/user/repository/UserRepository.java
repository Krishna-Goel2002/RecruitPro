package com.recruitpro.user.repository;

import com.recruitpro.user.entity.Role;
import com.recruitpro.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRole(Role role);

    long countByRoleAndEnabled(Role role, boolean enabled);

    List<User> findAllByRoleIn(List<Role> roles);

    // ---- Organisation-scoped queries (D3) ----

    Optional<User> findByIdAndOrganizationId(Long id, Long organizationId);

    long countByOrganizationId(Long organizationId);

    long countByOrganizationIdAndRoleIn(Long organizationId, Collection<Role> roles);

    long countByOrganizationIdAndRole(Long organizationId, Role role);

    List<User> findAllByOrganizationIdAndRoleIn(Long organizationId, Collection<Role> roles);

    /**
     * L3: locks the organisation's enabled users with the given role (PESSIMISTIC_WRITE) so that two
     * concurrent demotions cannot both pass the last-administrator check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.organizationId = :organizationId and u.role = :role and u.enabled = true order by u.id")
    List<User> lockEnabledByOrganizationAndRole(@Param("organizationId") Long organizationId, @Param("role") Role role);

    /** L3: locks every user with the role in the organisation (enabled or not). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.organizationId = :organizationId and u.role = :role order by u.id")
    List<User> lockAllByOrganizationAndRole(@Param("organizationId") Long organizationId, @Param("role") Role role);
}

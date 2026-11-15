package com.recruitpro.verification.repository;

import com.recruitpro.verification.entity.ClaimVerdictLabel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClaimVerdictLabelRepository extends JpaRepository<ClaimVerdictLabel, Long> {

    Optional<ClaimVerdictLabel> findByVerdictIdAndLabellerUserId(Long verdictId, Long labellerUserId);

    List<ClaimVerdictLabel> findAllByOrganizationIdOrderByIdAsc(Long organizationId);

    List<ClaimVerdictLabel> findAllByVerdictIdInAndOrganizationId(Collection<Long> verdictIds, Long organizationId);

    @Modifying(flushAutomatically = true)
    @Query("delete from ClaimVerdictLabel l where l.verdictId in :verdictIds")
    int deleteAllByVerdictIds(@Param("verdictIds") Collection<Long> verdictIds);
}

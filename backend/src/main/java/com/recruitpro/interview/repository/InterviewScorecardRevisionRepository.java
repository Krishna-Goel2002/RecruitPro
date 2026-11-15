package com.recruitpro.interview.repository;

import com.recruitpro.interview.entity.InterviewScorecardRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface InterviewScorecardRevisionRepository extends JpaRepository<InterviewScorecardRevision, Long> {

    List<InterviewScorecardRevision> findAllByScorecardIdOrderByIdDesc(Long scorecardId);

    long countByScorecardId(Long scorecardId);

    @Query("select new com.recruitpro.interview.repository.RevisionCountRow(r.scorecardId, count(r)) "
            + "from InterviewScorecardRevision r where r.scorecardId in :scorecardIds group by r.scorecardId")
    List<RevisionCountRow> countByScorecardIds(@Param("scorecardIds") Collection<Long> scorecardIds);
}

package com.recruitpro.interview.repository;

import com.recruitpro.interview.entity.InterviewScorecard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewScorecardRepository extends JpaRepository<InterviewScorecard, Long> {

    Optional<InterviewScorecard> findByInterviewId(Long interviewId);

    boolean existsByInterviewId(Long interviewId);

    List<InterviewScorecard> findAllByInterviewIdIn(Collection<Long> interviewIds);
}

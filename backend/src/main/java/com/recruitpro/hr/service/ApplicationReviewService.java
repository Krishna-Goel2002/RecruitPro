package com.recruitpro.hr.service;

import com.recruitpro.application.entity.*;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.hr.dto.ApplicationCommentRequest;
import com.recruitpro.hr.dto.ApplicationDecisionHistoryResponse;
import com.recruitpro.hr.dto.ApplicationDecisionRequest;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ApplicationReviewService {
    private final ApplicationRepository applicationRepository;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final HrJobAccessService jobAccess;

    public ApplicationDecisionHistoryResponse decide(Long jobId, Long applicationId, ApplicationStatus target,
                                                       ApplicationDecisionRequest request) {
        jobAccess.requireManageAccess(jobId);
        Application application = findForReview(jobId, applicationId);
        ApplicationStatus previous = application.getStatus();
        if (previous != ApplicationStatus.SUBMITTED || (target != ApplicationStatus.SHORTLISTED && target != ApplicationStatus.REJECTED)) {
            throw new BadRequestException("Only submitted applications can be shortlisted or rejected");
        }
        String comment = normalize(request == null ? null : request.comment());
        application.setStatus(target);
        application.setReviewComment(comment);
        applicationRepository.saveAndFlush(application);
        ApplicationDecisionHistory event = event(application, previous, target,
                target == ApplicationStatus.SHORTLISTED ? ApplicationDecisionAction.SHORTLIST : ApplicationDecisionAction.REJECT,
                comment);
        return ApplicationDecisionHistoryResponse.from(historyRepository.saveAndFlush(event));
    }

    public ApplicationDecisionHistoryResponse updateComment(Long jobId, Long applicationId, ApplicationCommentRequest request) {
        jobAccess.requireManageAccess(jobId);
        Application application = findForReview(jobId, applicationId);
        if (application.getStatus() != ApplicationStatus.SHORTLISTED && application.getStatus() != ApplicationStatus.REJECTED) {
            throw new BadRequestException("Review comments can be updated only after an HR decision");
        }
        String comment = normalize(request == null ? null : request.comment());
        application.setReviewComment(comment);
        applicationRepository.saveAndFlush(application);
        return ApplicationDecisionHistoryResponse.from(historyRepository.saveAndFlush(event(application,
                application.getStatus(), application.getStatus(), ApplicationDecisionAction.COMMENT_UPDATED, comment)));
    }

    @Transactional(readOnly = true)
    public List<ApplicationDecisionHistoryResponse> history(Long jobId, Long applicationId) {
        jobAccess.requireManageAccess(jobId);
        Application application = applicationRepository.findByIdAndJobId(applicationId, jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        return historyRepository.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId()).stream()
                .map(ApplicationDecisionHistoryResponse::from).toList();
    }

    private Application findForReview(Long jobId, Long applicationId) {
        return applicationRepository.findForReview(applicationId, jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }

    private ApplicationDecisionHistory event(Application application, ApplicationStatus previous, ApplicationStatus next,
                                               ApplicationDecisionAction action, String comment) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User actor = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
        ApplicationDecisionHistory event = new ApplicationDecisionHistory();
        event.setApplication(application);
        event.setPreviousStatus(previous);
        event.setNewStatus(next);
        event.setActionType(action);
        event.setActor(actor);
        event.setComment(comment);
        return event;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

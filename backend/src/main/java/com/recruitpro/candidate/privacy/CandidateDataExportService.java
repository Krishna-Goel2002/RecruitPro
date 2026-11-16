package com.recruitpro.candidate.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.application.dto.ApplicationDetailResponse;
import com.recruitpro.application.entity.Application;
import com.recruitpro.application.entity.ApplicationDecisionHistory;
import com.recruitpro.application.entity.ApplicationStatus;
import com.recruitpro.application.repository.ApplicationDecisionHistoryRepository;
import com.recruitpro.application.repository.ApplicationRepository;
import com.recruitpro.assessment.dto.WorkSampleFileResponse;
import com.recruitpro.assessment.entity.WorkSampleAssignment;
import com.recruitpro.assessment.service.WorkSampleFileService;
import com.recruitpro.assessment.repository.WorkSampleAssignmentRepository;
import com.recruitpro.candidate.dto.CandidateProfileResponse;
import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.entity.Resume;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.candidate.repository.ResumeAnalysisFactRepository;
import com.recruitpro.candidate.repository.ResumeAnalysisRepository;
import com.recruitpro.candidate.repository.ResumeRepository;
import com.recruitpro.candidate.service.CandidateProfileService;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.evidence.dto.CandidateClaimResponse;
import com.recruitpro.evidence.repository.CandidateClaimRepository;
import com.recruitpro.fairness.dto.EqualOpportunityResponse;
import com.recruitpro.fairness.repository.EeoResponseRepository;
import com.recruitpro.interview.dto.CandidateInterviewResponse;
import com.recruitpro.interview.repository.InterviewRepository;
import com.recruitpro.interview.service.InterviewViews;
import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.repository.NotificationRepository;
import com.recruitpro.organization.entity.Organization;
import com.recruitpro.organization.repository.OrganizationRepository;
import com.recruitpro.user.entity.User;
import com.recruitpro.verification.service.VerificationPrivacyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the candidate's data export (plan 5.5). Not read-only: the candidate profile may be provisioned on first use.
 */
@Service
@Transactional
public class CandidateDataExportService {

    private final CandidateProfileService profileService;
    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ResumeAnalysisFactRepository factRepository;
    private final CandidateClaimRepository claimRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationDecisionHistoryRepository historyRepository;
    private final OrganizationRepository organizationRepository;
    private final WorkSampleAssignmentRepository assignmentRepository;
    private final InterviewRepository interviewRepository;
    private final InterviewViews interviewViews;
    private final EeoResponseRepository eeoRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;
    private final VerificationPrivacyService verificationPrivacyService;

    public CandidateDataExportService(CandidateProfileService profileService, ResumeRepository resumeRepository,
                                      ResumeAnalysisRepository analysisRepository, ResumeAnalysisFactRepository factRepository,
                                      CandidateClaimRepository claimRepository, ApplicationRepository applicationRepository,
                                      ApplicationDecisionHistoryRepository historyRepository,
                                      OrganizationRepository organizationRepository,
                                      WorkSampleAssignmentRepository assignmentRepository,
                                      InterviewRepository interviewRepository, InterviewViews interviewViews,
                                      EeoResponseRepository eeoRepository, NotificationRepository notificationRepository,
                                      ObjectMapper objectMapper, VerificationPrivacyService verificationPrivacyService) {
        this.profileService = profileService;
        this.resumeRepository = resumeRepository;
        this.analysisRepository = analysisRepository;
        this.factRepository = factRepository;
        this.claimRepository = claimRepository;
        this.applicationRepository = applicationRepository;
        this.historyRepository = historyRepository;
        this.organizationRepository = organizationRepository;
        this.assignmentRepository = assignmentRepository;
        this.interviewRepository = interviewRepository;
        this.interviewViews = interviewViews;
        this.eeoRepository = eeoRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        this.verificationPrivacyService = verificationPrivacyService;
    }

    /** Work-sample submission files (setter-injected to keep the constructor stable). */
    private WorkSampleFileService workSampleFileService;

    @Autowired
    public void setWorkSampleFileService(WorkSampleFileService workSampleFileService) {
        this.workSampleFileService = workSampleFileService;
    }

    public CandidateDataExport exportMine() {
        CandidateProfile profile = profileService.getOrProvisionMine();
        User user = profile.getUser();
        CandidateDataExport.Account account = new CandidateDataExport.Account(user.getId(), user.getEmail(),
                user.getFirstName(), user.getLastName(), user.getRole() == null ? null : user.getRole().name(),
                user.getEmailVerifiedAt(), user.getCreatedAt());

        List<CandidateDataExport.ResumeExport> resumes = new ArrayList<>();
        List<CandidateDataExport.FactExport> facts = new ArrayList<>();
        for (Resume resume : resumeRepository.findAllByCandidateIdOrderByUploadedAtDescIdDesc(profile.getId())) {
            ResumeAnalysis analysis = analysisRepository.findByResumeId(resume.getId()).orElse(null);
            resumes.add(new CandidateDataExport.ResumeExport(resume.getId(), resume.getOriginalFilename(),
                    resume.getContentType(), resume.getFileSize(), resume.getUploadedAt(),
                    analysis == null || analysis.getStatus() == null ? null : analysis.getStatus().name(),
                    analysis == null ? null : analysis.getParserVersion(),
                    analysis == null ? null : analysis.getCompletedAt(),
                    analysis == null ? null : json(analysis.getFlagsJson()),
                    analysis == null ? null : json(analysis.getTimelineJson())));
            if (analysis != null) {
                for (ResumeAnalysisFact fact : factRepository.findAllByAnalysisIdOrderById(analysis.getId())) {
                    facts.add(new CandidateDataExport.FactExport(fact.getId(), resume.getId(),
                            fact.getType() == null ? null : fact.getType().name(), fact.getExtractedValue(),
                            fact.getReviewedValue(), fact.getReviewStatus() == null ? null : fact.getReviewStatus().name(),
                            fact.getEvidence(), fact.getSourceSection(),
                            fact.getEvidenceTier() == null ? null : fact.getEvidenceTier().name(),
                            fact.getReviewedAt(), fact.getCreatedAt(), json(fact.getAttributesJson())));
                }
            }
        }

        List<CandidateDataExport.ClaimExport> claims = claimRepository.findAllByCandidateIdOrderByCreatedAtDescIdDesc(profile.getId())
                .stream().map(CandidateClaimResponse::forCandidate).map(CandidateDataExport.ClaimExport::from).toList();

        List<Application> applications = applicationRepository.findAllByCandidateId(profile.getId(),
                PageRequest.of(0, 10_000, Sort.by(Sort.Direction.DESC, "appliedAt"))).getContent();
        Set<Long> organizationIds = new HashSet<>();
        for (Application application : applications) {
            if (application.getJob().getOrganizationId() != null) {
                organizationIds.add(application.getJob().getOrganizationId());
            }
        }
        Map<Long, String> organizationNames = new HashMap<>();
        if (!organizationIds.isEmpty()) {
            for (Organization organization : organizationRepository.findAllById(organizationIds)) {
                organizationNames.put(organization.getId(), organization.getName());
            }
        }
        List<CandidateDataExport.ApplicationExport> applicationExports = new ArrayList<>();
        for (Application application : applications) {
            applicationExports.add(new CandidateDataExport.ApplicationExport(application.getId(), application.getJob().getId(),
                    application.getJob().getTitle(), organizationNames.get(application.getJob().getOrganizationId()),
                    application.getResume().getId(), application.getStatus().name(), application.getCoverLetter(),
                    application.getAppliedAt(), application.getStatusChangedAt(), timeline(application)));
        }

        List<CandidateDataExport.WorkSampleExport> workSamples = new ArrayList<>();
        List<WorkSampleAssignment> assignments = assignmentRepository.findAllForCandidate(profile.getId());
        // R2-L04: the files the candidate submitted (WP-PLAT) are part of the export (metadata; the bytes are
        // downloadable from the work-sample page).
        Map<Long, List<WorkSampleFileResponse>> files = workSampleFileService == null ? Map.of()
                : workSampleFileService.listFor(assignments.stream().map(WorkSampleAssignment::getId).toList());
        for (WorkSampleAssignment assignment : assignments) {
            workSamples.add(new CandidateDataExport.WorkSampleExport(assignment.getId(), assignment.getApplication().getId(),
                    assignment.getWorkSample().getJob().getTitle(), assignment.getWorkSample().getTitle(),
                    assignment.getStatus().name(), assignment.getAssignedAt(), assignment.getDueAt(),
                    assignment.getStartedAt(), assignment.getSubmittedAt(), assignment.getResponseText(),
                    assignment.getResponseUrl(), files.getOrDefault(assignment.getId(), List.of()),
                    assignment.getCancelledAt(), assignment.getCancelReason()));
        }

        List<CandidateInterviewResponse> interviews = interviewRepository.findAllForCandidate(profile.getId()).stream()
                .map(interviewViews::candidateView).toList();

        EqualOpportunityResponse survey = eeoRepository.findByCandidateId(profile.getId())
                .map(EqualOpportunityResponse::from).orElse(null);

        List<CandidateDataExport.NotificationExport> notifications = new ArrayList<>();
        for (Notification notification : notificationRepository.findAllByRecipientUserIdOrderByIdAsc(user.getId())) {
            boolean inbox = notification.getChannel() == NotificationChannel.IN_APP;
            notifications.add(new CandidateDataExport.NotificationExport(notification.getId(),
                    notification.getChannel() == null ? null : notification.getChannel().name(),
                    notification.getTemplate() == null ? null : notification.getTemplate().name(),
                    notification.getSubject(), inbox ? notification.getBody() : null,
                    notification.getStatus() == null ? null : notification.getStatus().name(),
                    notification.getCreatedAt(), notification.getSentAt(), notification.getReadAt()));
        }

        return new CandidateDataExport(CandidateDataExport.FORMAT, UtcTime.now(), account,
                CandidateDataExport.ProfileExport.from(CandidateProfileResponse.from(profile)),
                resumes, facts, claims, applicationExports, workSamples, interviews, survey, notifications, verificationPrivacyService.export(profile.getId()));
    }

    /** Submission plus every status change, as in the candidate's ApplicationDetail. */
    private List<ApplicationDetailResponse.TimelineEntry> timeline(Application application) {
        List<ApplicationDetailResponse.TimelineEntry> timeline = new ArrayList<>();
        timeline.add(new ApplicationDetailResponse.TimelineEntry(ApplicationStatus.SUBMITTED, application.getAppliedAt()));
        for (ApplicationDecisionHistory event : historyRepository.findAllByApplicationIdOrderByCreatedAtAscIdAsc(application.getId())) {
            if (event.getPreviousStatus() != event.getNewStatus()) {
                timeline.add(new ApplicationDetailResponse.TimelineEntry(event.getNewStatus(), event.getCreatedAt()));
            }
        }
        return timeline;
    }

    private JsonNode json(String text) {
        return JsonText.read(objectMapper, text);
    }
}

package com.recruitpro.verification.service;

import com.recruitpro.candidate.entity.CandidateProfile;
import com.recruitpro.candidate.repository.CandidateProfileRepository;
import com.recruitpro.jobs.entity.BackgroundJobType;
import com.recruitpro.jobs.service.BackgroundJobHandler;
import com.recruitpro.user.entity.User;
import com.recruitpro.verification.client.AgentModels;
import com.recruitpro.verification.client.AgentServiceClient;
import com.recruitpro.verification.client.AgentServiceException;
import com.recruitpro.verification.dto.PublicReferenceAnswer;
import com.recruitpro.verification.dto.ReferenceClaim;
import com.recruitpro.verification.entity.ClaimSubjectRef;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.entity.SubjectType;
import com.recruitpro.verification.entity.VerificationReferenceRequest;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VERIFY_REFERENCE handler (entity id = verification_reference_requests.id): sends the referee's answers to the
 * agent's deterministic {@code /verify/reference} rules and stores the verdicts (with {@code reference_id}). Error
 * handling as in {@link VerifySourceJobHandler}; failures are recorded in the request's {@code error_code}.
 */
@Component
public class VerifyReferenceJobHandler implements BackgroundJobHandler {

    private static final Logger log = LoggerFactory.getLogger(VerifyReferenceJobHandler.class);
    private static final TypeReference<List<PublicReferenceAnswer>> ANSWERS = new TypeReference<List<PublicReferenceAnswer>>() { };

    private final VerificationReferenceRequestRepository referenceRepository;
    private final CandidateProfileRepository profileRepository;
    private final VerificationResultWriter resultWriter;
    private final AgentServiceClient agentClient;
    private final VerificationViews views;
    private final TransactionTemplate transactions;

    public VerifyReferenceJobHandler(VerificationReferenceRequestRepository referenceRepository,
                                     CandidateProfileRepository profileRepository, VerificationResultWriter resultWriter,
                                     AgentServiceClient agentClient, VerificationViews views,
                                     PlatformTransactionManager transactionManager) {
        this.referenceRepository = referenceRepository;
        this.profileRepository = profileRepository;
        this.resultWriter = resultWriter;
        this.agentClient = agentClient;
        this.views = views;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    @Override
    public BackgroundJobType type() {
        return BackgroundJobType.VERIFY_REFERENCE;
    }

    @Override
    public void handle(long referenceId) {
        AgentModels.ReferenceVerifyRequest request = transactions.execute(status -> buildRequest(referenceId));
        if (request == null) {
            log.debug("Reference request {} is not waiting for verification", referenceId);
            return;
        }
        AgentModels.VerifyResponse response;
        try {
            response = agentClient.verifyReference(request);
        } catch (AgentServiceException ex) {
            if (ex.code() != null) {
                resultWriter.failReference(referenceId, ex.code());
                return;
            }
            if (ex.isTransient()) {
                throw new IllegalStateException("Agent service responded with HTTP " + ex.status());
            }
            resultWriter.failReference(referenceId, VerifySourceJobHandler.REJECTED);
            return;
        } catch (RestClientException ex) {
            throw new IllegalStateException("Agent service unavailable (" + ex.getClass().getSimpleName() + ")");
        } catch (IllegalStateException ex) {
            if (AgentServiceClient.NOT_CONFIGURED_MESSAGE.equals(ex.getMessage())) {
                resultWriter.failReference(referenceId, VerifySourceJobHandler.NOT_CONFIGURED);
                return;
            }
            throw ex;
        }
        resultWriter.storeReferenceResult(referenceId, response);
    }

    @Override
    public void onDeadLetter(long referenceId, String lastError) {
        resultWriter.failReference(referenceId, VerifySourceJobHandler.UNAVAILABLE);
    }

    private AgentModels.ReferenceVerifyRequest buildRequest(long referenceId) {
        VerificationReferenceRequest reference = referenceRepository.findById(referenceId).orElse(null);
        if (reference == null || reference.getStatus() != ReferenceStatus.ANSWERED || reference.getVerifiedAt() != null) {
            return null;
        }
        List<AgentModels.Claim> claims = new ArrayList<>();
        for (ReferenceClaim claim : views.referenceClaims(reference.getClaimRefsJson())) {
            SubjectType type = VerificationResultWriter.parseSubjectType(claim.subjectType());
            if (type != null && claim.subjectId() != null && claim.claimType() != null && claim.value() != null) {
                claims.add(new AgentModels.Claim(ClaimSubjectRef.of(type, claim.subjectId()), claim.claimType(), claim.value()));
            }
        }
        List<AgentModels.ReferenceAnswer> answers = new ArrayList<>();
        try {
            List<PublicReferenceAnswer> stored = reference.getAnswersJson() == null ? List.of()
                    : views.mapper().readValue(reference.getAnswersJson(), ANSWERS);
            for (PublicReferenceAnswer answer : stored) {
                answers.add(new AgentModels.ReferenceAnswer(answer.claimId(), answer.answer(), answer.comment()));
            }
        } catch (java.io.IOException ex) {
            log.warn("Stored answers of reference request {} are unreadable", referenceId);
        }
        String email = reference.getRefereeEmail();
        String emailDomain = email == null || !email.contains("@") ? "invalid" : email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        CandidateProfile profile = profileRepository.findById(reference.getCandidateId()).orElse(null);
        User user = profile == null ? null : profile.getUser();
        String holderName = user == null ? null
                : ((user.getFirstName() == null ? "" : user.getFirstName()) + " " + (user.getLastName() == null ? "" : user.getLastName())).trim();
        return new AgentModels.ReferenceVerifyRequest(claims,
                new AgentModels.Referee(reference.getRelationship().name(), emailDomain, reference.getEmployerDomain()),
                answers, holderName == null || holderName.isBlank() ? null : holderName);
    }
}

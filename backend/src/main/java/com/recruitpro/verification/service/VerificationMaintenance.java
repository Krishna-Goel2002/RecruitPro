package com.recruitpro.verification.service;

import com.recruitpro.common.time.UtcTime;
import com.recruitpro.verification.entity.ReferenceStatus;
import com.recruitpro.verification.repository.VerificationOAuthStateRepository;
import com.recruitpro.verification.repository.VerificationReferenceRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Reference expiry sweep ({@code app.verification.reference-expiry.enabled}, every
 * {@code app.verification.reference-expiry.interval-ms}, default 15 minutes): SENT requests past their expiry become
 * EXPIRED, and OAuth states older than a day are deleted.
 */
@Component
public class VerificationMaintenance {

    private static final Logger log = LoggerFactory.getLogger(VerificationMaintenance.class);

    private final VerificationReferenceRequestRepository referenceRepository;
    private final VerificationOAuthStateRepository stateRepository;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public VerificationMaintenance(VerificationReferenceRequestRepository referenceRepository,
                                   VerificationOAuthStateRepository stateRepository,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${app.verification.reference-expiry.enabled:true}") boolean enabled) {
        this.referenceRepository = referenceRepository;
        this.stateRepository = stateRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.verification.reference-expiry.interval-ms:900000}",
            initialDelayString = "${app.verification.reference-expiry.initial-delay-ms:60000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int expired = sweep();
            if (expired > 0) {
                log.info("Marked {} reference request(s) as expired", expired);
            }
        } catch (RuntimeException ex) {
            log.warn("Verification maintenance failed: {}", ex.getClass().getSimpleName());
        }
    }

    /** Runs the sweep now; returns how many reference requests expired. */
    public int sweep() {
        LocalDateTime now = UtcTime.now();
        Integer expired = transactions.execute(status -> {
            int count = referenceRepository.expireOverdue(ReferenceStatus.SENT, ReferenceStatus.EXPIRED, now);
            stateRepository.deleteExpiredBefore(now.minusDays(1));
            return count;
        });
        return expired == null ? 0 : expired;
    }
}

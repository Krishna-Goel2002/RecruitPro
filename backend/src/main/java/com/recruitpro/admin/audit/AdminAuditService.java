package com.recruitpro.admin.audit;

import com.recruitpro.admin.dto.AdminAuditRecord;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.user.entity.User;
import com.recruitpro.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final int MAX_AUDIT_LOGS = 1000;

    private final UserRepository userRepository;
    private final Deque<AdminAuditRecord> auditBuffer = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong(1);

    public void record(String action, String resourceType, String resourceId, String details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorEmail = (auth != null && auth.getName() != null) ? auth.getName() : "SYSTEM";
        Long actorId = null;
        if (auth != null && auth.getName() != null) {
            actorId = userRepository.findByEmail(auth.getName()).map(User::getId).orElse(null);
        }

        String safeDetails = details == null ? "" : details.replaceAll("(?i)(password|token|secret)=[^, ]+", "$1=***");
        AdminAuditRecord record = new AdminAuditRecord(
                String.valueOf(sequence.getAndIncrement()),
                action,
                actorEmail,
                actorId,
                resourceType,
                resourceId,
                safeDetails,
                LocalDateTime.now()
        );

        auditBuffer.addFirst(record);
        while (auditBuffer.size() > MAX_AUDIT_LOGS) {
            auditBuffer.pollLast();
        }

        AUDIT_LOGGER.info("ADMIN_ACTION action='{}' actor='{}' (id={}) targetType='{}' targetId='{}' details='{}'",
                action, actorEmail, actorId, resourceType, resourceId, safeDetails);
    }

    public PageResponse<AdminAuditRecord> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        List<AdminAuditRecord> all = new ArrayList<>(auditBuffer);
        int totalElements = all.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<AdminAuditRecord> content = all.subList(fromIndex, toIndex);

        return PageResponse.<AdminAuditRecord>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(Math.max(1, totalPages))
                .first(page == 0)
                .last(page >= totalPages - 1 || totalElements == 0)
                .build();
    }
}

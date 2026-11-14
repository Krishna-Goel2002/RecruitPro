package com.recruitpro.audit.service;

import com.recruitpro.audit.dto.AuditEventResponse;
import com.recruitpro.audit.entity.AuditEvent;
import com.recruitpro.audit.repository.AuditEventRepository;
import com.recruitpro.common.dto.PageResponse;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.common.web.ClientIp;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * M10: persistent audit trail. {@link #record} writes an {@link AuditEvent} with the caller's organisation,
 * identity and client IP inside the caller's transaction, so the audit row commits or rolls back together
 * with the change it describes.
 */
@Service
public class AuditService {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final Pattern SECRET_VALUES =
            Pattern.compile("(?i)(password|passwd|token|secret)(\\s*[=:]\\s*)[^,;\\s]+");
    private static final int MAX_DETAILS = 4000;
    static final int MAX_EXPORT_ROWS = 50_000;

    private final AuditEventRepository repository;
    private final SecurityUtils securityUtils;

    public AuditService(AuditEventRepository repository, SecurityUtils securityUtils) {
        this.repository = repository;
        this.securityUtils = securityUtils;
    }

    /** Records an action performed by the current caller (or SYSTEM when there is none). */
    @Transactional
    public AuditEvent record(String action, String targetType, String targetId, String details) {
        Optional<CurrentUser> actor = securityUtils.findCurrentUser();
        return recordFor(
                actor.map(CurrentUser::organizationId).orElse(null),
                actor.map(CurrentUser::userId).orElse(null),
                actor.map(CurrentUser::email).orElse("SYSTEM"),
                action, targetType, targetId, details);
    }

    /** Records an action with an explicit organisation and actor (sign-up, invitation acceptance, lockout). */
    @Transactional
    public AuditEvent recordFor(Long organizationId, Long actorUserId, String actorEmail, String action,
                                String targetType, String targetId, String details) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("Audit action is required");
        }
        AuditEvent event = new AuditEvent();
        event.setOrganizationId(organizationId);
        event.setActorUserId(actorUserId);
        event.setActorEmail(limit(actorEmail, 255));
        event.setAction(limit(action, 80));
        event.setTargetType(limit(targetType, 60));
        event.setTargetId(limit(targetId, 80));
        event.setDetails(sanitize(details));
        event.setIpAddress(ClientIp.current().orElse(null));
        AuditEvent saved = repository.save(event);
        AUDIT_LOGGER.info("AUDIT action='{}' org={} actor='{}' (id={}) targetType='{}' targetId='{}'",
                saved.getAction(), organizationId, saved.getActorEmail(), actorUserId, saved.getTargetType(),
                saved.getTargetId());
        return saved;
    }

    /** GET /api/v1/admin/audit-logs: the caller's organisation, newest first, optionally one action. */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> listForCurrentOrganization(int page, int size, String action) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BadRequestException("page must be non-negative and size must be between 1 and 100");
        }
        Long organizationId = securityUtils.requireOrganizationId();
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<AuditEvent> events = action == null || action.isBlank()
                ? repository.findAllByOrganizationId(organizationId, pageable)
                : repository.findAllByOrganizationIdAndAction(organizationId, action.trim(), pageable);
        return PageResponse.<AuditEventResponse>builder()
                .content(events.getContent().stream().map(AuditEventResponse::from).toList())
                .page(events.getNumber())
                .size(events.getSize())
                .totalElements(events.getTotalElements())
                .totalPages(events.getTotalPages())
                .first(events.isFirst())
                .last(events.isLast())
                .build();
    }

    /**
     * GET /api/v1/admin/audit-export: CSV of the caller's organisation between {@code from} and {@code to}
     * (ISO-8601 dates or date-times; defaults: last 90 days). The export itself is audited.
     */
    @Transactional
    public String exportCsvForCurrentOrganization(String from, String to) {
        Long organizationId = securityUtils.requireOrganizationId();
        LocalDateTime end = parseBound(to, UtcTime.now());
        LocalDateTime start = parseBound(from, end.minusDays(90));
        if (start.isAfter(end)) {
            throw new BadRequestException("'from' must not be after 'to'");
        }
        List<AuditEvent> events = repository.findAllByOrganizationIdAndCreatedAtBetween(organizationId, start, end,
                PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
        StringBuilder csv = new StringBuilder(256 + events.size() * 128);
        csv.append("id,createdAt,action,actorEmail,actorUserId,targetType,targetId,ipAddress,details\n");
        for (AuditEvent event : events) {
            csv.append(event.getId()).append(',')
                    .append(cell(UtcTime.format(event.getCreatedAt()))).append(',')
                    .append(cell(event.getAction())).append(',')
                    .append(cell(event.getActorEmail())).append(',')
                    .append(event.getActorUserId() == null ? "" : event.getActorUserId()).append(',')
                    .append(cell(event.getTargetType())).append(',')
                    .append(cell(event.getTargetId())).append(',')
                    .append(cell(event.getIpAddress())).append(',')
                    .append(cell(event.getDetails())).append('\n');
        }
        record(AuditActions.AUDIT_EXPORTED, "ORGANIZATION", String.valueOf(organizationId),
                "Exported " + events.size() + " audit events from " + UtcTime.format(start) + " to " + UtcTime.format(end));
        return csv.toString();
    }

    /** Masks secrets and truncates; details must never carry passwords, tokens or resume text. */
    static String sanitize(String details) {
        if (details == null) {
            return null;
        }
        String masked = SECRET_VALUES.matcher(details).replaceAll("$1$2***");
        return limit(masked, MAX_DETAILS);
    }

    /** CSV cell with quoting and spreadsheet formula neutralisation. */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            safe = "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static LocalDateTime parseBound(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return UtcTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("Dates must be ISO-8601, e.g. 2026-11-08 or 2026-11-08T10:15:30Z");
        }
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}

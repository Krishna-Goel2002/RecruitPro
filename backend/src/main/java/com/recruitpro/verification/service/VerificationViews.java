package com.recruitpro.verification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recruitpro.common.util.JsonText;
import com.recruitpro.verification.dto.EvidenceItemResponse;
import com.recruitpro.verification.dto.IntegrityNoteView;
import com.recruitpro.verification.dto.ReferenceClaim;
import com.recruitpro.verification.dto.VerdictReason;
import com.recruitpro.verification.entity.EvidenceItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Reads and writes the JSON columns of the verification tables; never throws on bad stored data. */
@Component
public class VerificationViews {

    private static final TypeReference<List<VerdictReason>> REASONS = new TypeReference<List<VerdictReason>>() { };
    private static final TypeReference<List<IntegrityNoteView>> NOTES = new TypeReference<List<IntegrityNoteView>>() { };
    private static final TypeReference<List<Long>> IDS = new TypeReference<List<Long>>() { };
    private static final TypeReference<List<ReferenceClaim>> REFERENCE_CLAIMS = new TypeReference<List<ReferenceClaim>>() { };

    private final ObjectMapper objectMapper;

    public VerificationViews(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<VerdictReason> reasons(String json) {
        return readList(json, REASONS);
    }

    public List<IntegrityNoteView> notes(String json) {
        return readList(json, NOTES);
    }

    public List<Long> ids(String json) {
        List<Long> ids = new ArrayList<>();
        for (Long id : readList(json, IDS)) {
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    public List<ReferenceClaim> referenceClaims(String json) {
        return readList(json, REFERENCE_CLAIMS);
    }

    public JsonNode json(String text) {
        return JsonText.read(objectMapper, text);
    }

    public String write(Object value) {
        return JsonText.write(objectMapper, value);
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    public EvidenceItemResponse evidence(EvidenceItem item) {
        return new EvidenceItemResponse(item.getId(), item.getItemKey(), item.getKind() == null ? null : item.getKind().name(),
                item.getUrl(), item.getTitle(), item.getRetrievedAt(), item.getContentHash(), item.getExcerpt(),
                json(item.getAttributesJson()));
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<T> values = objectMapper.readValue(json, type);
            return values == null ? new ArrayList<>() : new ArrayList<>(values);
        } catch (java.io.IOException | RuntimeException ex) {
            return new ArrayList<>();
        }
    }
}

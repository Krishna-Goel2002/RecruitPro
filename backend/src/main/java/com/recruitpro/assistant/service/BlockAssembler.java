package com.recruitpro.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recruitpro.assistant.service.AssistantApplicationLookup.AppInfo;
import com.recruitpro.assistant.tool.ApplicationAccess;
import com.recruitpro.assistant.tool.ApplicationCodes;
import com.recruitpro.assistant.tool.Tools;
import com.recruitpro.common.time.UtcTime;
import com.recruitpro.security.CurrentUser;
import com.recruitpro.user.entity.Role;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the UI blocks of assistant messages (ASSISTANT_PLAN 3.2). Planner blocks are untrusted: only the kinds
 * metrics, table, candidates, link, notice and question are kept, sizes are capped, links must be portal-relative paths,
 * and candidate items are re-resolved on the server: applications the user may not see are dropped, the status comes
 * from the database, the link is the portal route, and {@code revealedName} is the candidate's name only when the
 * identity is revealed (names exist only in blocks for the user, never in planner input). job_preview and action blocks
 * are made by the backend only.
 */
@Component
public class BlockAssembler {

    static final Set<String> PLANNER_KINDS = Set.of("metrics", "table", "candidates", "link", "notice", "question");
    static final Set<String> TONES = Set.of("info", "success", "warning", "error");
    static final int MAX_BLOCKS = 12;
    static final int MAX_ITEMS = 50;
    static final int MAX_COLUMNS = 12;
    static final int MAX_CELL = 300;
    static final int MAX_TEXT = 1000;
    /** Portal-relative path: starts with one slash, no scheme, no "//", no spaces or quotes. */
    static final Pattern SAFE_LINK = Pattern.compile("^/(?![/\\\\])[A-Za-z0-9/_\\-?=&.%#+,~]*$");

    private final ApplicationAccess access;

    public BlockAssembler(ApplicationAccess access) {
        this.access = access;
    }

    /** The planner's blocks, validated and enriched for {@code user}. */
    public ArrayNode fromPlanner(CurrentUser user, List<Map<String, Object>> blocks) {
        ArrayNode result = AssistantJson.emptyArray();
        if (blocks == null) {
            return result;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Map<String, Object> block : blocks) {
            if (block != null && "candidates".equals(block.get("kind")) && block.get("items") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> map) {
                        Long id = applicationId(map);
                        if (id != null) ids.add(id);
                    }
                }
            }
        }
        Map<Long, AppInfo> visible = ids.isEmpty() ? Map.of() : access.visible(user, ids);
        boolean reader = user.hasAnyRole(Role.ADMIN, Role.HR_MANAGER, Role.RECRUITER);
        for (Map<String, Object> block : blocks) {
            if (result.size() >= MAX_BLOCKS) break;
            if (block == null || !(block.get("kind") instanceof String kind) || !PLANNER_KINDS.contains(kind)) continue;
            JsonNode built = switch (kind) {
                case "metrics" -> metrics(block);
                case "table" -> table(block);
                case "candidates" -> candidates(block, visible, reader);
                case "link" -> link(text(block.get("label"), 100), text(block.get("href"), 300));
                case "notice" -> notice(text(block.get("tone"), 20), text(block.get("text"), MAX_TEXT));
                case "question" -> question(text(block.get("field"), 60), text(block.get("text"), MAX_TEXT), block.get("options"));
                default -> null;
            };
            if (built != null) result.add(built);
        }
        return result;
    }

    // ---- block builders (also used by the backend's own messages) ----

    public ObjectNode notice(String tone, String text) {
        if (text == null || text.isBlank()) return null;
        ObjectNode node = AssistantJson.object();
        node.put("kind", "notice");
        node.put("tone", tone != null && TONES.contains(tone.toLowerCase(Locale.ROOT)) ? tone.toLowerCase(Locale.ROOT) : "info");
        node.put("text", text);
        return node;
    }

    /** {kind:"action", actionId, action, summary, preview, status, expiresAt}. */
    public ObjectNode action(Long actionId, String action, String summary, JsonNode preview, String status,
                             LocalDateTime expiresAt) {
        ObjectNode node = AssistantJson.object();
        node.put("kind", "action");
        node.put("actionId", actionId);
        node.put("action", action);
        node.put("summary", summary);
        node.set("preview", preview == null ? AssistantJson.object() : preview);
        node.put("status", status);
        node.put("expiresAt", UtcTime.format(expiresAt));
        return node;
    }

    public ObjectNode link(String label, String href) {
        if (href == null || !SAFE_LINK.matcher(href).matches()) return null;
        ObjectNode node = AssistantJson.object();
        node.put("kind", "link");
        node.put("label", label == null || label.isBlank() ? href : label);
        node.put("href", href);
        return node;
    }

    public ObjectNode question(String field, String text, Object options) {
        if (text == null || text.isBlank()) return null;
        ObjectNode node = AssistantJson.object();
        node.put("kind", "question");
        if (field == null) node.putNull("field"); else node.put("field", field);
        node.put("text", text);
        ArrayNode list = node.putArray("options");
        if (options instanceof Collection<?> values) {
            for (Object value : values) {
                String option = text(value instanceof Map<?, ?> map ? map.get("label") : value, 100);
                if (option != null && list.size() < 10) list.add(option);
            }
        }
        return node;
    }

    private ObjectNode metrics(Map<String, Object> block) {
        if (!(block.get("items") instanceof List<?> items)) return null;
        ObjectNode node = AssistantJson.object();
        node.put("kind", "metrics");
        ArrayNode out = node.putArray("items");
        for (Object item : items) {
            if (out.size() >= MAX_COLUMNS) break;
            if (!(item instanceof Map<?, ?> map)) continue;
            String label = text(map.get("label"), 100);
            if (label == null) continue;
            ObjectNode metric = out.addObject();
            metric.put("label", label);
            putScalar(metric, "value", map.get("value"));
            String hint = text(map.get("hint"), 200);
            if (hint != null) metric.put("hint", hint);
        }
        return out.isEmpty() ? null : node;
    }

    private ObjectNode table(Map<String, Object> block) {
        if (!(block.get("columns") instanceof List<?> columns) || !(block.get("rows") instanceof List<?> rows)) return null;
        ObjectNode node = AssistantJson.object();
        node.put("kind", "table");
        ArrayNode outColumns = node.putArray("columns");
        for (Object column : columns) {
            if (outColumns.size() >= MAX_COLUMNS) break;
            String label = text(column instanceof Map<?, ?> map ? map.get("label") : column, 100);
            outColumns.add(label == null ? "" : label);
        }
        ArrayNode outRows = node.putArray("rows");
        for (Object row : rows) {
            if (outRows.size() >= MAX_ITEMS) break;
            if (!(row instanceof List<?> cells)) continue;
            ArrayNode outRow = outRows.addArray();
            for (int i = 0; i < cells.size() && i < outColumns.size(); i++) {
                Object cell = cells.get(i);
                if (cell instanceof Integer || cell instanceof Long || cell instanceof Short) outRow.add(((Number) cell).longValue());
                else if (cell instanceof Number number) outRow.add(number.doubleValue());
                else if (cell instanceof Boolean flag) outRow.add(flag);
                else if (cell == null) outRow.addNull();
                else outRow.add(text(cell, MAX_CELL));
            }
        }
        String caption = text(block.get("caption"), 200);
        if (caption != null) node.put("caption", caption);
        return outColumns.isEmpty() ? null : node;
    }

    private ObjectNode candidates(Map<String, Object> block, Map<Long, AppInfo> visible, boolean reader) {
        if (!(block.get("items") instanceof List<?> items)) return null;
        ObjectNode node = AssistantJson.object();
        node.put("kind", "candidates");
        ArrayNode out = node.putArray("items");
        Set<Long> seen = new LinkedHashSet<>();
        for (Object item : items) {
            if (out.size() >= MAX_ITEMS) break;
            if (!(item instanceof Map<?, ?> map)) continue;
            Long id = applicationId(map);
            AppInfo info = id == null ? null : visible.get(id);
            if (info == null || !seen.add(id)) continue;
            ObjectNode candidate = out.addObject();
            candidate.put("applicationId", info.applicationId());
            candidate.put("code", info.code());
            String name = info.revealedName();
            if (name == null) candidate.putNull("revealedName"); else candidate.put("revealedName", name);
            candidate.put("status", info.status() == null ? null : info.status().name());
            candidate.put("jobId", info.jobId());
            putScalar(candidate, "verified", map.get("verified") != null ? map.get("verified") : map.get("verifiedCoverage"));
            putScalar(candidate, "claimed", map.get("claimed") != null ? map.get("claimed") : map.get("claimedCoverage"));
            putScalar(candidate, "group", map.get("group") != null ? map.get("group") : map.get("rankGroup"));
            ArrayNode gates = candidate.putArray("gates");
            if (map.get("gates") instanceof List<?> values) {
                for (Object value : values) {
                    String gate = text(value, 200);
                    if (gate != null && gates.size() < 10) gates.add(gate);
                }
            }
            candidate.put("link", reader ? Tools.applicantLink(info.jobId(), info.applicationId()) : "/interviews");
        }
        return out.isEmpty() ? null : node;
    }

    private static Long applicationId(Map<?, ?> map) {
        Long id = ApplicationCodes.parse(map.get("applicationId"));
        return id != null ? id : ApplicationCodes.parse(map.get("code"));
    }

    private static void putScalar(ObjectNode node, String key, Object value) {
        if (value == null) node.putNull(key);
        else if (value instanceof Boolean flag) node.put(key, flag);
        else if (value instanceof Integer || value instanceof Long) node.put(key, ((Number) value).longValue());
        else if (value instanceof Number number) node.put(key, number.doubleValue());
        else node.put(key, text(value, MAX_CELL));
    }

    static String text(Object value, int max) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Collection<?>) return null;
        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** Blocks list helper: drops nulls. */
    public static ArrayNode blocks(List<JsonNode> nodes) {
        ArrayNode array = AssistantJson.emptyArray();
        for (JsonNode node : new ArrayList<>(nodes)) {
            if (node != null) array.add(node);
        }
        return array;
    }
}

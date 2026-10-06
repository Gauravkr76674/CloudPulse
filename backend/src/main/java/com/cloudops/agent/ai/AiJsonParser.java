package com.cloudops.agent.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloudops.agent.ai.model.AiDiagnosis;
import com.cloudops.agent.ai.model.AiRecommendation;
import com.cloudops.agent.ai.model.AiTriage;
import com.cloudops.agent.entity.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a model's text reply into a typed record.
 *
 * Models drift from instructions, so the parser is defensive: it tolerates markdown
 * fences and surrounding prose by extracting the first balanced JSON object, accepts a
 * few field-name variants, and returns {@link Optional#empty()} rather than throwing
 * when the reply is unusable. An unparsable reply becomes a failed AiAnalysis row —
 * never a guess.
 */
@Component
public class AiJsonParser {

    private static final Logger log = LoggerFactory.getLogger(AiJsonParser.class);

    private final ObjectMapper objectMapper;

    public AiJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Extracts and parses the first JSON object found in the reply. */
    public Optional<JsonNode> extractObject(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }

        String candidate = stripFences(content.trim());
        String json = firstBalancedObject(candidate);
        if (json == null) {
            log.debug("No JSON object found in the model reply");
            return Optional.empty();
        }

        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (Exception ex) {
            log.debug("Model reply was not valid JSON: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Optional<AiTriage> parseTriage(String content) {
        return extractObject(content).map(node -> new AiTriage(
                bool(node, true, "genuineAnomaly", "genuine_anomaly", "anomaly"),
                severity(node, "suggestedSeverity", "severity"),
                text(node, "summary", "assessment"),
                text(node, "signalAssessment", "signal_assessment", "reasoning"),
                confidence(node)));
    }

    public Optional<AiDiagnosis> parseDiagnosis(String content) {
        return extractObject(content).map(node -> new AiDiagnosis(
                text(node, "summary", "diagnosis"),
                text(node, "rootCause", "root_cause", "cause"),
                text(node, "reasoning", "analysis", "explanation"),
                severity(node, "suggestedSeverity", "severity"),
                confidence(node)));
    }

    public Optional<AiRecommendation> parseRecommendation(String content) {
        return extractObject(content).map(node -> new AiRecommendation(
                text(node, "action", "recommendedAction", "recommended_action", "actionType"),
                text(node, "title", "summary"),
                text(node, "rationale", "reason", "reasoning"),
                text(node, "expectedImpact", "expected_impact", "impact"),
                text(node, "riskLevel", "risk_level", "risk"),
                confidence(node),
                integer(node, "priority"),
                stringList(node, "alternativeActions", "alternative_actions", "alternatives")));
    }

    /** VERIFICATION stage: returns (recovered, safeToClose, summary, reasoning). */
    public Optional<Verification> parseVerification(String content) {
        return extractObject(content).map(node -> new Verification(
                bool(node, false, "recovered", "resolved"),
                bool(node, false, "safeToClose", "safe_to_close"),
                text(node, "summary"),
                text(node, "reasoning", "analysis"),
                confidence(node)));
    }

    /** Parsed VERIFICATION payload. */
    public record Verification(
            boolean recovered,
            boolean safeToClose,
            String summary,
            String reasoning,
            Double confidence) {
    }

    // ---------------------------------------------------------------------
    // field helpers
    // ---------------------------------------------------------------------

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String asText = value.isValueNode() ? value.asText() : value.toString();
                if (asText != null && !asText.isBlank() && !"null".equals(asText)) {
                    return asText.trim();
                }
            }
        }
        return null;
    }

    private boolean bool(JsonNode node, boolean fallback, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            String asText = value.asText("").trim();
            if ("true".equalsIgnoreCase(asText) || "yes".equalsIgnoreCase(asText)) {
                return true;
            }
            if ("false".equalsIgnoreCase(asText) || "no".equalsIgnoreCase(asText)) {
                return false;
            }
        }
        return fallback;
    }

    private Double confidence(JsonNode node) {
        JsonNode value = node.get("confidence");
        if (value == null || value.isNull()) {
            return null;
        }
        double raw;
        if (value.isNumber()) {
            raw = value.asDouble();
        } else {
            try {
                raw = Double.parseDouble(value.asText().replace("%", "").trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        // Accept both 0-1 and 0-100 scales
        if (raw > 1.0 && raw <= 100.0) {
            raw = raw / 100.0;
        }
        return Math.max(0.0, Math.min(1.0, raw));
    }

    private Integer integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Severity severity(JsonNode node, String... names) {
        String raw = text(node, names);
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        for (Severity severity : Severity.values()) {
            if (severity.name().equals(normalized)) {
                return severity;
            }
        }
        return null;
    }

    private List<String> stringList(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isArray()) {
                List<String> items = new ArrayList<>();
                value.forEach(element -> {
                    String asText = element.asText("").trim();
                    if (!asText.isEmpty()) {
                        items.add(asText);
                    }
                });
                return items;
            }
        }
        return List.of();
    }

    // ---------------------------------------------------------------------
    // extraction helpers
    // ---------------------------------------------------------------------

    private static String stripFences(String content) {
        String result = content;
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            if (firstNewline > 0) {
                result = result.substring(firstNewline + 1);
            }
            int closing = result.lastIndexOf("```");
            if (closing >= 0) {
                result = result.substring(0, closing);
            }
        }
        return result.trim();
    }

    /**
     * Scans for the first {@code {...}} block with balanced braces, ignoring braces that
     * appear inside string literals.
     */
    private static String firstBalancedObject(String content) {
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}

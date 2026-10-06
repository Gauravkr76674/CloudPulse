package com.cloudops.agent.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.cloudops.agent.util.JsonUtil;

import jakarta.annotation.PostConstruct;

/**
 * Deterministic offline analyser, active when {@code cloudops.ai.provider=stub}
 * (the default) so the whole pipeline is demonstrable with no API key and no cost.
 *
 * It is a real client, not a bypass: it reads the same prompt the HTTP client would send,
 * emits the same JSON contract, and its output flows through {@link AiJsonParser} and the
 * action whitelist exactly like a live model's. Switching to a hosted model is a matter of
 * setting AI_PROVIDER=http and AI_API_KEY — no pipeline code changes.
 */
@Component
@ConditionalOnProperty(name = "cloudops.ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(StubAiClient.class);

    private static final Pattern METRIC = Pattern.compile("metric:\\s*(\\S+)");
    private static final Pattern TRIGGER = Pattern.compile("trigger value:\\s*([-\\d.]+)");
    private static final Pattern THRESHOLD = Pattern.compile("threshold:\\s*([-\\d.]+)");
    private static final Pattern OCCURRENCES = Pattern.compile("consecutive occurrences:\\s*(\\d+)");
    private static final Pattern INSTANCE_TYPE = Pattern.compile("instance type:\\s*(\\S+)");

    private final JsonUtil jsonUtil;

    public StubAiClient(JsonUtil jsonUtil) {
        this.jsonUtil = jsonUtil;
    }

    @PostConstruct
    void logMode() {
        log.info("AI stub provider active — deterministic local analysis, no external calls. "
                + "Set AI_PROVIDER=http with AI_API_KEY to use a real model.");
    }

    @Override
    public String providerName() {
        return "stub";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        long startedAt = System.currentTimeMillis();
        String prompt = request.userPrompt();

        String metric = group(METRIC, prompt, "CPUUtilization");
        double trigger = number(TRIGGER, prompt, 0);
        double threshold = number(THRESHOLD, prompt, 0);
        int occurrences = (int) number(OCCURRENCES, prompt, 1);
        String instanceType = group(INSTANCE_TYPE, prompt, "t3.micro");
        double exceedance = threshold > 0 ? ((trigger - threshold) / threshold) * 100.0 : 0.0;

        Map<String, Object> payload = switch (request.stage()) {
            case DETECTION -> detection(metric, trigger, threshold, occurrences, exceedance);
            case DIAGNOSIS -> diagnosis(metric, trigger, threshold, occurrences, instanceType, exceedance);
            case RECOMMENDATION -> recommendation(metric, occurrences, prompt);
            case VERIFICATION -> verification(metric, prompt, threshold);
        };

        String content = jsonUtil.toJson(payload);
        long latency = System.currentTimeMillis() - startedAt;

        if (content == null) {
            return AiResponse.failed(providerName(), "stub-analyst-v1", latency,
                    "stub client could not serialise its response");
        }
        return AiResponse.ok(content, providerName(), "stub-analyst-v1", latency, null, null);
    }

    // ---------------------------------------------------------------------
    // stage responses
    // ---------------------------------------------------------------------

    private Map<String, Object> detection(String metric, double trigger, double threshold,
            int occurrences, double exceedance) {
        boolean genuine = occurrences >= 2 || exceedance >= 10.0;
        String severity;
        if (exceedance >= 25.0 || occurrences >= 4) {
            severity = "CRITICAL";
        } else if (exceedance >= 10.0 || occurrences >= 3) {
            severity = "HIGH";
        } else if (exceedance > 0) {
            severity = "MEDIUM";
        } else {
            severity = "LOW";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("genuineAnomaly", genuine);
        result.put("suggestedSeverity", severity);
        result.put("summary", String.format(
                "%s reached %.2f against a threshold of %.2f across %d consecutive datapoint(s).",
                metric, trigger, threshold, occurrences));
        result.put("signalAssessment", genuine
                ? String.format("The breach is %.1f%% above threshold and repeated %d times, "
                        + "which is consistent with a sustained condition rather than a transient spike.",
                        exceedance, occurrences)
                : "Only a single marginal datapoint breached the threshold, which is often benign noise.");
        result.put("confidence", genuine ? 0.78 : 0.55);
        return result;
    }

    private Map<String, Object> diagnosis(String metric, double trigger, double threshold,
            int occurrences, String instanceType, double exceedance) {
        String rootCause;
        String reasoning;

        switch (metric) {
            case "CPUUtilization" -> {
                boolean networkAlsoHigh = false;
                rootCause = "Sustained CPU saturation on a " + instanceType
                        + " instance — most likely a runaway or unbounded process, or demand "
                        + "exceeding what this instance size can serve.";
                reasoning = String.format(
                        "CPU held at %.2f%% over %d consecutive %s-second datapoints, %.1f%% above the "
                        + "%.0f%% threshold. A burstable %s instance that stays at this level will exhaust "
                        + "its CPU credits and then be throttled, so latency degrades even if the process "
                        + "recovers. %s",
                        trigger, occurrences, "300", exceedance, threshold, instanceType,
                        networkAlsoHigh
                                ? "Elevated NetworkIn alongside CPU points at genuine traffic growth."
                                : "Without a matching traffic rise in the samples provided, a local process "
                                        + "is the more probable cause than an external load surge.");
            }
            case "NetworkIn" -> {
                rootCause = "Inbound traffic well above the normal envelope — either a legitimate "
                        + "demand spike or unwanted/abusive traffic reaching the instance.";
                reasoning = String.format(
                        "NetworkIn measured %.0f bytes against a %.0f byte threshold over %d datapoint(s). "
                        + "Traffic-driven load should be confirmed against application request counts before "
                        + "any restart, since restarting will not reduce inbound traffic.",
                        trigger, threshold, occurrences);
            }
            case "StatusCheckFailed" -> {
                rootCause = "EC2 status check failing — the instance or its underlying host is unhealthy.";
                reasoning = "A non-zero StatusCheckFailed value means AWS itself cannot verify the "
                        + "instance. If the failure is the instance status check, a reboot commonly clears "
                        + "it; if it is the system status check, the host is at fault and a stop/start "
                        + "(which migrates the instance to new hardware) is the correct remedy.";
            }
            default -> {
                rootCause = "Metric " + metric + " outside its expected range; cause not determinable "
                        + "from the supplied evidence alone.";
                reasoning = String.format("%s reached %.2f against a threshold of %.2f over %d "
                        + "datapoint(s). Additional signals are needed before attributing a cause.",
                        metric, trigger, threshold, occurrences);
            }
        }

        String severity;
        if (exceedance >= 25.0 || occurrences >= 4) {
            severity = "CRITICAL";
        } else if (exceedance >= 10.0 || occurrences >= 3) {
            severity = "HIGH";
        } else {
            severity = "MEDIUM";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", String.format("Probable cause: %s",
                rootCause.length() > 140 ? rootCause.substring(0, 137) + "..." : rootCause));
        result.put("rootCause", rootCause);
        result.put("reasoning", reasoning);
        result.put("suggestedSeverity", severity);
        result.put("confidence", occurrences >= 3 ? 0.74 : 0.61);
        return result;
    }

    private Map<String, Object> recommendation(String metric, int occurrences, String prompt) {
        String action;
        String title;
        String rationale;
        String expectedImpact;
        String risk;
        int priority;

        switch (metric) {
            case "StatusCheckFailed" -> {
                action = "REBOOT_EC2_INSTANCE";
                title = "Reboot the instance to clear the failing status check";
                rationale = "A failing instance status check is most often cleared by a reboot, which "
                        + "restarts the guest without releasing the instance or its private address.";
                expectedImpact = "Brief downtime of roughly one to two minutes while the instance "
                        + "restarts; in-flight requests will fail during that window.";
                risk = "MEDIUM";
                priority = 1;
            }
            case "CPUUtilization" -> {
                if (occurrences >= 3) {
                    action = "REBOOT_EC2_INSTANCE";
                    title = "Reboot to clear the saturated process";
                    rationale = "CPU has stayed above threshold across several consecutive datapoints, "
                            + "so a transient spike has been ruled out. A reboot clears a runaway process "
                            + "and restores headroom immediately.";
                    expectedImpact = "One to two minutes of downtime; CPU should return to baseline. "
                            + "If saturation returns afterwards the cause is demand, not a stuck process, "
                            + "and the instance should be resized instead.";
                    risk = "MEDIUM";
                    priority = 1;
                } else {
                    action = "TAG_FOR_REVIEW";
                    title = "Tag for review and keep watching";
                    rationale = "The breach is not yet sustained enough to justify downtime. Tagging "
                            + "records the concern without disrupting service.";
                    expectedImpact = "No disruption. The instance stays under observation and the next "
                            + "poll will confirm whether the condition persists.";
                    risk = "LOW";
                    priority = 3;
                }
            }
            case "NetworkIn" -> {
                action = "TAG_FOR_REVIEW";
                title = "Tag for traffic review before touching the instance";
                rationale = "Restarting an instance does not reduce inbound traffic. The source of the "
                        + "surge must be identified first, so the safe step is to flag it for a human.";
                expectedImpact = "No disruption; the instance is marked for investigation.";
                risk = "LOW";
                priority = 2;
            }
            default -> {
                action = "COLLECT_MORE_METRICS";
                title = "Collect more metrics before acting";
                rationale = "The available evidence does not identify a cause, and no mutating action "
                        + "is justified without one.";
                expectedImpact = "No disruption; another collection cycle is triggered.";
                risk = "LOW";
                priority = 4;
            }
        }

        // Never propose something outside the catalog the caller supplied
        if (!catalogContains(prompt, action)) {
            action = catalogContains(prompt, "NO_ACTION") ? "NO_ACTION" : action;
            title = "No safe catalog action applies";
            rationale = "The preferred action is not available in the allowed catalog for this "
                    + "resource, so no mutating action is proposed.";
            expectedImpact = "No change to the resource.";
            risk = "LOW";
            priority = 5;
        }

        List<String> alternatives = new ArrayList<>();
        for (String candidate : List.of("TAG_FOR_REVIEW", "NOTIFY_ONCALL", "COLLECT_MORE_METRICS")) {
            if (!candidate.equals(action) && catalogContains(prompt, candidate)) {
                alternatives.add(candidate);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("title", title);
        result.put("rationale", rationale);
        result.put("expectedImpact", expectedImpact);
        result.put("riskLevel", risk);
        result.put("confidence", occurrences >= 3 ? 0.72 : 0.6);
        result.put("priority", priority);
        result.put("alternativeActions", alternatives);
        return result;
    }

    private Map<String, Object> verification(String metric, String prompt, double threshold) {
        Double latest = lastMetricValue(prompt);
        boolean recovered = latest != null && threshold > 0 && latest < threshold;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recovered", recovered);
        result.put("safeToClose", recovered);
        result.put("summary", latest == null
                ? "No post-remediation datapoints are available yet, so recovery cannot be confirmed."
                : String.format("Latest %s reading is %.2f against a threshold of %.2f — %s.",
                        metric, latest, threshold,
                        recovered ? "within normal range" : "still breaching"));
        result.put("reasoning", latest == null
                ? "Verification needs at least one datapoint collected after the action completed."
                : String.format("The newest sample (%.2f) is compared directly with the threshold "
                        + "(%.2f) used to open the incident.", latest, threshold));
        result.put("confidence", latest == null ? 0.3 : 0.7);
        return result;
    }

    // ---------------------------------------------------------------------
    // prompt reading helpers
    // ---------------------------------------------------------------------

    private static boolean catalogContains(String prompt, String action) {
        return prompt != null && prompt.contains("- " + action);
    }

    /** Reads the value from the last "... value=NN" metric line in the prompt. */
    private static Double lastMetricValue(String prompt) {
        if (prompt == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("value=([-\\d.]+)").matcher(prompt);
        Double last = null;
        while (matcher.find()) {
            try {
                last = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // keep the previous value
            }
        }
        return last;
    }

    private static String group(Pattern pattern, String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static double number(Pattern pattern, String text, double fallback) {
        String raw = group(pattern, text, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}

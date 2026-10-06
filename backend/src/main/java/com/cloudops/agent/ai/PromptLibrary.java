package com.cloudops.agent.ai;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloudops.agent.ai.model.AiDiagnosis;
import com.cloudops.agent.ai.model.IncidentContext;
import com.cloudops.agent.entity.AiStage;
import com.cloudops.agent.util.Texts;

/**
 * All prompts live here, one specialised pair (system + user) per {@link AiStage}.
 *
 * This is what makes a single configured model behave like four specialists, and what
 * lets each stage be pointed at a different model without touching the pipeline code.
 *
 * Two rules are enforced in every prompt:
 * <ol>
 *   <li>reply with a single JSON object and nothing else — parsing stays deterministic;</li>
 *   <li>choose actions only from the supplied catalog — the model is never invited to
 *       produce a CLI command, and anything outside the catalog is rejected downstream.</li>
 * </ol>
 */
@Component
public class PromptLibrary {

    private static final String JSON_ONLY =
            "Reply with exactly one JSON object and no prose, no markdown fences and no commentary. "
            + "If a value is unknown use null.";

    public String systemPrompt(AiStage stage) {
        return switch (stage) {
            case DETECTION -> """
                    You are the detection specialist of an automated cloud operations agent \
                    monitoring AWS EC2 instances through CloudWatch.
                    Your job: decide whether an alert represents a genuine problem or benign \
                    noise (for example a short batch job, a planned load test, or a single \
                    isolated spike), and set a severity.
                    Be conservative: sustained breaches across several consecutive datapoints \
                    are genuine; one isolated datapoint usually is not.
                    """ + JSON_ONLY;

            case DIAGNOSIS -> """
                    You are the diagnosis specialist of an automated cloud operations agent \
                    for AWS EC2.
                    Your job: from the metric history and instance context, explain the most \
                    probable root cause of the incident. Reason about what the numbers imply \
                    (for example CPU saturation with high NetworkIn suggests a traffic surge, \
                    while high CPU with flat network suggests a runaway local process).
                    State clearly when the evidence is insufficient rather than inventing a cause. \
                    You have no shell access and no ability to inspect the operating system: \
                    reason only from the data provided.
                    """ + JSON_ONLY;

            case RECOMMENDATION -> """
                    You are the decision specialist of an automated cloud operations agent \
                    for AWS EC2.
                    Your job: choose ONE action from the allowed action catalog you are given.
                    You must copy the action name exactly as it appears in the catalog. \
                    You cannot invent actions, write shell commands, write AWS CLI commands, \
                    or reference any API not in the catalog; such a reply is discarded by the \
                    system and wastes the operator's time.
                    Prefer the least disruptive effective action. If the evidence does not \
                    justify touching the instance, choose TAG_FOR_REVIEW, COLLECT_MORE_METRICS \
                    or NO_ACTION. A human administrator reviews and approves your choice before \
                    anything executes.
                    """ + JSON_ONLY;

            case VERIFICATION -> """
                    You are the verification specialist of an automated cloud operations agent \
                    for AWS EC2.
                    Your job: after a remediation has run, judge from the newest metrics whether \
                    the incident has actually recovered, and say whether it is safe to close.
                    """ + JSON_ONLY;
        };
    }

    public String userPrompt(AiStage stage, IncidentContext context) {
        return switch (stage) {
            case DETECTION -> detectionPrompt(context);
            case DIAGNOSIS -> diagnosisPrompt(context);
            case RECOMMENDATION -> recommendationPrompt(context, null);
            case VERIFICATION -> verificationPrompt(context);
        };
    }

    public String recommendationPrompt(IncidentContext context, AiDiagnosis diagnosis) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(incidentBlock(context));

        if (diagnosis != null) {
            prompt.append("\nDIAGNOSIS FROM THE PREVIOUS STAGE\n")
                    .append("summary: ").append(Texts.defaultIfBlank(diagnosis.summary(), "n/a")).append('\n')
                    .append("root cause: ").append(Texts.defaultIfBlank(diagnosis.rootCause(), "n/a")).append('\n')
                    .append("reasoning: ").append(Texts.defaultIfBlank(diagnosis.reasoning(), "n/a")).append('\n');
        }

        prompt.append("\nALLOWED ACTION CATALOG — copy one name exactly\n");
        for (String action : context.allowedActions()) {
            prompt.append("- ").append(action).append('\n');
        }

        prompt.append("""

                Respond with this JSON shape:
                {
                  "action": "<one name from the catalog above>",
                  "title": "<short imperative summary, max 15 words>",
                  "rationale": "<why this action addresses the diagnosed cause>",
                  "expectedImpact": "<what the operator should expect, including any disruption>",
                  "riskLevel": "LOW" | "MEDIUM" | "HIGH",
                  "confidence": <number between 0 and 1>,
                  "priority": <integer 1 (highest) to 5 (lowest)>,
                  "alternativeActions": ["<optional other catalog names>"]
                }
                """);
        return prompt.toString();
    }

    private String detectionPrompt(IncidentContext context) {
        return incidentBlock(context) + """

                Respond with this JSON shape:
                {
                  "genuineAnomaly": true | false,
                  "suggestedSeverity": "LOW" | "MEDIUM" | "HIGH" | "CRITICAL",
                  "summary": "<one sentence an on-call engineer can read at a glance>",
                  "signalAssessment": "<why you consider this genuine or benign>",
                  "confidence": <number between 0 and 1>
                }
                """;
    }

    private String diagnosisPrompt(IncidentContext context) {
        return incidentBlock(context) + """

                Respond with this JSON shape:
                {
                  "summary": "<one sentence stating the most probable cause>",
                  "rootCause": "<the specific probable cause, or "insufficient evidence">",
                  "reasoning": "<step by step reasoning from the metric evidence>",
                  "suggestedSeverity": "LOW" | "MEDIUM" | "HIGH" | "CRITICAL",
                  "confidence": <number between 0 and 1>
                }
                """;
    }

    private String verificationPrompt(IncidentContext context) {
        return incidentBlock(context) + """

                Respond with this JSON shape:
                {
                  "recovered": true | false,
                  "summary": "<did the remediation work, in one sentence>",
                  "reasoning": "<which datapoints support that conclusion>",
                  "safeToClose": true | false,
                  "confidence": <number between 0 and 1>
                }
                """;
    }

    /** The shared factual block: incident, resource, metric history, prior actions. */
    private String incidentBlock(IncidentContext context) {
        StringBuilder block = new StringBuilder();

        block.append("INCIDENT\n")
                .append("id: ").append(context.incidentId()).append('\n')
                .append("title: ").append(context.title()).append('\n')
                .append("current severity: ").append(context.severity()).append('\n')
                .append("status: ").append(context.status()).append('\n')
                .append("detection rule: ").append(Texts.defaultIfBlank(context.ruleName(), "n/a")).append('\n')
                .append("metric: ").append(context.metricName()).append('\n')
                .append("trigger value: ").append(Texts.number(context.triggerValue())).append('\n')
                .append("threshold: ").append(Texts.number(context.thresholdValue())).append('\n')
                .append("consecutive occurrences: ").append(context.occurrenceCount()).append('\n')
                .append("opened at: ").append(context.openedAt()).append('\n');

        block.append("\nRESOURCE\n")
                .append("name: ").append(context.resourceName()).append('\n')
                .append("provider id: ").append(context.resourceId()).append('\n')
                .append("type: ").append(context.resourceType()).append('\n')
                .append("instance type: ").append(Texts.defaultIfBlank(context.instanceType(), "n/a")).append('\n')
                .append("region: ").append(context.region()).append('\n')
                .append("availability zone: ").append(Texts.defaultIfBlank(context.availabilityZone(), "n/a")).append('\n')
                .append("lifecycle state: ").append(context.resourceStatus()).append('\n')
                .append("tags: ").append(Texts.defaultIfBlank(context.resourceTags(), "{}")).append('\n');

        block.append("\nRECENT METRICS (oldest first)\n");
        appendLines(block, context.recentMetricLines(), "no metric samples available");

        block.append("\nPRIOR ACTIONS ON THIS RESOURCE\n");
        appendLines(block, context.priorActionLines(), "none");

        return block.toString();
    }

    private void appendLines(StringBuilder target, List<String> lines, String emptyText) {
        if (lines == null || lines.isEmpty()) {
            target.append(emptyText).append('\n');
            return;
        }
        for (String line : lines) {
            target.append(line).append('\n');
        }
    }
}

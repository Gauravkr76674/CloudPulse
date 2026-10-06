package com.cloudops.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cloudops.agent.ai.model.AiDiagnosis;
import com.cloudops.agent.ai.model.AiRecommendation;
import com.cloudops.agent.ai.model.AiTriage;
import com.cloudops.agent.entity.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The parser is the airlock between a language model and typed Java.
 *
 * Two behaviours matter. It has to survive the ways models actually reply — fenced blocks,
 * a sentence of preamble, snake_case keys, "85%" instead of 0.85. And when the reply is
 * unusable it must return {@link Optional#empty()} so the caller records a failed analysis,
 * rather than inventing a plausible-looking value.
 */
class AiJsonParserTest {

    private AiJsonParser parser;

    @BeforeEach
    void setUp() {
        parser = new AiJsonParser(new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // tolerating how models really answer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("plain JSON parses")
    void plainJsonParses() {
        Optional<AiTriage> triage = parser.parseTriage("""
                {"genuineAnomaly": true, "suggestedSeverity": "HIGH",
                 "summary": "CPU pinned at 97%", "signalAssessment": "Sustained, not a spike",
                 "confidence": 0.9}
                """);

        assertThat(triage).isPresent();
        assertThat(triage.get().genuineAnomaly()).isTrue();
        assertThat(triage.get().suggestedSeverity()).isEqualTo(Severity.HIGH);
        assertThat(triage.get().summary()).isEqualTo("CPU pinned at 97%");
        assertThat(triage.get().confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("a markdown-fenced block parses")
    void fencedJsonParses() {
        Optional<AiDiagnosis> diagnosis = parser.parseDiagnosis("""
                ```json
                {"summary": "Runaway process", "root_cause": "A stuck worker loop",
                 "reasoning": "CPU rose without a matching NetworkIn rise",
                 "severity": "critical", "confidence": 85}
                ```
                """);

        assertThat(diagnosis).isPresent();
        assertThat(diagnosis.get().rootCause()).isEqualTo("A stuck worker loop");
        assertThat(diagnosis.get().suggestedSeverity()).isEqualTo(Severity.CRITICAL);
        // 0-100 scale is normalised to 0-1
        assertThat(diagnosis.get().confidence()).isEqualTo(0.85);
    }

    @Test
    @DisplayName("JSON wrapped in prose parses")
    void jsonInsideProseParses() {
        Optional<AiRecommendation> recommendation = parser.parseRecommendation("""
                Sure! Based on the metrics I would restart the instance. Here is the JSON:
                {"action": "REBOOT_EC2_INSTANCE", "title": "Reboot demo-web-01",
                 "rationale": "Clears the stuck worker", "expected_impact": "~60s downtime",
                 "risk": "MEDIUM", "confidence": "0.8", "priority": "1",
                 "alternatives": ["NOTIFY_ONCALL", "COLLECT_MORE_METRICS"]}
                Let me know if you want me to proceed.
                """);

        assertThat(recommendation).isPresent();
        assertThat(recommendation.get().action()).isEqualTo("REBOOT_EC2_INSTANCE");
        assertThat(recommendation.get().expectedImpact()).isEqualTo("~60s downtime");
        assertThat(recommendation.get().riskLevel()).isEqualTo("MEDIUM");
        assertThat(recommendation.get().confidence()).isEqualTo(0.8);
        assertThat(recommendation.get().priority()).isEqualTo(1);
        assertThat(recommendation.get().alternativeActions())
                .containsExactly("NOTIFY_ONCALL", "COLLECT_MORE_METRICS");
    }

    @Test
    @DisplayName("nested objects and braces inside strings do not confuse the scanner")
    void nestedBracesAreHandled() {
        Optional<AiDiagnosis> diagnosis = parser.parseDiagnosis("""
                {"summary": "Contains a brace } and a \\"quote\\"",
                 "rootCause": "x", "evidence": {"metric": "CPUUtilization", "nested": {"deep": 1}},
                 "confidence": 0.5}
                """);

        assertThat(diagnosis).isPresent();
        assertThat(diagnosis.get().summary()).isEqualTo("Contains a brace } and a \"quote\"");
        assertThat(diagnosis.get().rootCause()).isEqualTo("x");
    }

    @Test
    @DisplayName("yes/no answers are read as booleans")
    void yesNoIsReadAsBoolean() {
        Optional<AiJsonParser.Verification> verification = parser.parseVerification(
                "{\"recovered\": \"yes\", \"safe_to_close\": \"no\", \"summary\": \"CPU back to 12%\"}");

        assertThat(verification).isPresent();
        assertThat(verification.get().recovered()).isTrue();
        assertThat(verification.get().safeToClose()).isFalse();
    }

    @Test
    @DisplayName("confidence is clamped into 0..1 and percentages are accepted")
    void confidenceIsNormalised() {
        assertThat(parser.parseTriage("{\"confidence\": \"92%\"}").orElseThrow().confidence())
                .isEqualTo(0.92);
        assertThat(parser.parseTriage("{\"confidence\": 7.5}").orElseThrow().confidence())
                .isEqualTo(0.075);
        assertThat(parser.parseTriage("{\"confidence\": 350}").orElseThrow().confidence())
                .isEqualTo(1.0);
        assertThat(parser.parseTriage("{\"confidence\": -2}").orElseThrow().confidence())
                .isEqualTo(0.0);
        assertThat(parser.parseTriage("{\"confidence\": \"very high\"}").orElseThrow().confidence())
                .isNull();
    }

    // ------------------------------------------------------------------
    // refusing to guess
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "I'm sorry, I cannot help with that.",
            "The CPU is high. You should probably reboot it.",
            "{ this is not json at all }",
            "{\"unterminated\": \"value\"",
            "[]",
            "null",
            "   ",
            ""
    })
    @DisplayName("an unusable reply yields empty, never a guessed value")
    void unusableRepliesYieldEmpty(String reply) {
        assertThat(parser.extractObject(reply)).isEmpty();
        assertThat(parser.parseRecommendation(reply)).isEmpty();
        assertThat(parser.parseTriage(reply)).isEmpty();
        assertThat(parser.parseDiagnosis(reply)).isEmpty();
        assertThat(parser.parseVerification(reply)).isEmpty();
    }

    @Test
    @DisplayName("an object inside a JSON array is still read - models sometimes wrap the reply")
    void arrayWrappedObjectIsTolerated() {
        Optional<AiRecommendation> recommendation =
                parser.parseRecommendation("[{\"action\": \"NOTIFY_ONCALL\", \"priority\": 2}]");

        assertThat(recommendation).isPresent();
        assertThat(recommendation.get().action()).isEqualTo("NOTIFY_ONCALL");
        assertThat(recommendation.get().priority()).isEqualTo(2);
    }

    @Test
    @DisplayName("a null reply yields empty rather than throwing")
    void nullReplyYieldsEmpty() {
        assertThat(parser.extractObject(null)).isEmpty();
        assertThat(parser.parseRecommendation(null)).isEmpty();
        assertThat(parser.parseVerification(null)).isEmpty();
    }

    @Test
    @DisplayName("missing fields become null - the caller decides what to do about it")
    void missingFieldsBecomeNull() {
        Optional<AiRecommendation> recommendation = parser.parseRecommendation("{\"unrelated\": 1}");

        assertThat(recommendation).isPresent();
        assertThat(recommendation.get().action()).isNull();
        assertThat(recommendation.get().riskLevel()).isNull();
        assertThat(recommendation.get().priority()).isNull();
        assertThat(recommendation.get().alternativeActions()).isEmpty();
    }

    @Test
    @DisplayName("an unrecognised severity is null rather than a default")
    void unknownSeverityIsNull() {
        assertThat(parser.parseTriage("{\"severity\": \"SEVERE\"}").orElseThrow()
                .suggestedSeverity()).isNull();
        assertThat(parser.parseTriage("{\"severity\": \"medium\"}").orElseThrow()
                .suggestedSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("triage defaults to treating the signal as genuine when the model omits the flag")
    void triageDefaultsToGenuine() {
        // the metric already breached a threshold, so silence must not mean "ignore it"
        assertThat(parser.parseTriage("{\"summary\": \"looks hot\"}").orElseThrow()
                .genuineAnomaly()).isTrue();
    }
}

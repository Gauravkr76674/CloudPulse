package com.cloudops.agent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Texts#redactSecrets} is the reason this class has tests: every AI prompt and every
 * provider error message passes through it before being logged or stored, and the project
 * requirement is logging without exposing secrets.
 */
class TextsTest {

    @Test
    @DisplayName("an OpenAI-style key is masked")
    void masksApiKeyLiteral() {
        String redacted = Texts.redactSecrets(
                "Request failed with Authorization: Bearer sk-abc123DEF456ghi789 (401)");

        assertThat(redacted).doesNotContain("sk-abc123DEF456ghi789");
        assertThat(redacted).contains("REDACTED");
    }

    @Test
    @DisplayName("an AWS access key id is masked")
    void masksAwsAccessKeyId() {
        String redacted = Texts.redactSecrets("User: AKIAIOSFODNN7EXAMPLE is not authorized");

        assertThat(redacted).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted).contains("AKIA***REDACTED***");
    }

    @Test
    @DisplayName("key-value secrets are masked whatever they are called")
    void masksLabelledSecrets() {
        assertThat(Texts.redactSecrets("{\"api_key\": \"hunter2\"}"))
                .doesNotContain("hunter2");
        assertThat(Texts.redactSecrets("password=letmein123"))
                .doesNotContain("letmein123");
        assertThat(Texts.redactSecrets("token: eyJhbGciOiJIUzI1NiJ9.body.sig"))
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(Texts.redactSecrets("SECRET = swordfish"))
                .doesNotContain("swordfish");
    }

    @Test
    @DisplayName("ordinary text survives redaction unchanged")
    void leavesNormalTextAlone() {
        String message = "CPUUtilization on i-0abc123 reached 97.4% at 10:05";

        assertThat(Texts.redactSecrets(message)).isEqualTo(message);
        assertThat(Texts.redactSecrets(null)).isNull();
    }

    @Test
    @DisplayName("truncate respects the column length and marks that it cut")
    void truncateFitsColumns() {
        assertThat(Texts.truncate("short", 200)).isEqualTo("short");
        assertThat(Texts.truncate("  padded  ", 200)).isEqualTo("padded");
        assertThat(Texts.truncate("abcdefghij", 5)).isEqualTo("ab...").hasSize(5);
        assertThat(Texts.truncate("abcdefghij", 3)).isEqualTo("abc");
        assertThat(Texts.truncate(null, 10)).isNull();
    }

    @Test
    @DisplayName("blank handling")
    void blankHelpers() {
        assertThat(Texts.hasText("x")).isTrue();
        assertThat(Texts.hasText("   ")).isFalse();
        assertThat(Texts.hasText(null)).isFalse();
        assertThat(Texts.defaultIfBlank("  ", "fallback")).isEqualTo("fallback");
        assertThat(Texts.defaultIfBlank(null, "fallback")).isEqualTo("fallback");
        assertThat(Texts.defaultIfBlank("value", "fallback")).isEqualTo("value");
    }

    @Test
    @DisplayName("numbers are formatted for prompts without scientific notation")
    void numberFormatting() {
        // the decimal separator follows the JVM locale, so match either form
        assertThat(Texts.number(97.4321)).matches("97[.,]43");
        assertThat(Texts.number(52_428_800.0)).isEqualTo("52428800");
        assertThat(Texts.number(null)).isEqualTo("n/a");
    }
}

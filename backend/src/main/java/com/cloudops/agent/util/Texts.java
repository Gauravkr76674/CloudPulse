package com.cloudops.agent.util;

/** Small string helpers used to keep values inside their column lengths. */
public final class Texts {

    private Texts() {
    }

    /** Truncates to {@code max} characters, appending an ellipsis when it had to cut. */
    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        if (max <= 3) {
            return trimmed.substring(0, max);
        }
        return trimmed.substring(0, max - 3) + "...";
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static String defaultIfBlank(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    /**
     * Replaces anything that looks like a secret with a mask, so AI prompts and log
     * lines can never carry an API key or AWS access key id.
     */
    public static String redactSecrets(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?i)(sk-[A-Za-z0-9_\\-]{8,})", "sk-***REDACTED***")
                .replaceAll("(AKIA[0-9A-Z]{12,})", "AKIA***REDACTED***")
                .replaceAll("(?i)(\"?(api[_-]?key|secret|password|token)\"?\\s*[:=]\\s*)\"?[^\",\\s]+\"?",
                        "$1***REDACTED***");
    }

    /** Formats a double for prompts and titles without scientific notation noise. */
    public static String number(Double value) {
        if (value == null) {
            return "n/a";
        }
        if (Math.abs(value) >= 1000d) {
            return String.format("%.0f", value);
        }
        return String.format("%.2f", value);
    }
}

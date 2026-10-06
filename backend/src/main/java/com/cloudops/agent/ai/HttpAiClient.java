package com.cloudops.agent.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.cloudops.agent.config.AiProperties;
import com.cloudops.agent.util.Texts;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.annotation.PostConstruct;

/**
 * Calls any OpenAI-compatible {@code POST /chat/completions} endpoint. Active when
 * {@code cloudops.ai.provider=http}.
 *
 * Configuration is entirely environment-driven: AI_BASE_URL, AI_API_KEY, AI_MODEL,
 * AI_TIMEOUT_SECONDS, AI_MAX_RETRIES. The key is read once into memory, sent only in the
 * Authorization header, and never written to a log, an exception message, or the database.
 *
 * Failure handling: connect/read timeouts, 429 and 5xx are retried with exponential
 * backoff; 4xx (bad key, unknown model) fails immediately because retrying cannot help.
 */
@Component
@ConditionalOnProperty(name = "cloudops.ai.provider", havingValue = "http")
public class HttpAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiClient.class);

    private final AiProperties aiProperties;
    private final RestClient restClient;

    public HttpAiClient(AiProperties aiProperties) {
        this.aiProperties = aiProperties;

        int timeoutMillis = Math.max(1, aiProperties.getTimeoutSeconds()) * 1000;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(aiProperties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    @PostConstruct
    void logConfiguration() {
        // Log the endpoint and model, never the key
        log.info("AI HTTP provider ready: baseUrl={}, defaultModel={}, timeout={}s, retries={}",
                normalizeBaseUrl(aiProperties.getBaseUrl()), aiProperties.getDefaultModel(),
                aiProperties.getTimeoutSeconds(), aiProperties.getMaxRetries());
        if (!configured()) {
            log.error("AI_API_KEY is not set. AI analysis will fail until it is provided; "
                    + "set AI_PROVIDER=stub to run the pipeline offline instead.");
        }
    }

    @Override
    public String providerName() {
        return "http";
    }

    @Override
    public boolean configured() {
        return Texts.hasText(aiProperties.getApiKey());
    }

    @Override
    public AiResponse complete(AiRequest request) {
        long startedAt = System.currentTimeMillis();

        if (!configured()) {
            return AiResponse.failed(providerName(), request.model(), 0L,
                    "AI_API_KEY is not configured");
        }

        Map<String, Object> payload = Map.of(
                "model", request.model(),
                "temperature", request.temperature(),
                "max_tokens", request.maxOutputTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())));

        int attempts = Math.max(1, aiProperties.getMaxRetries() + 1);
        String lastError = "unknown error";

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                JsonNode body = restClient.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + aiProperties.getApiKey())
                        .body(payload)
                        .retrieve()
                        .body(JsonNode.class);

                long latency = System.currentTimeMillis() - startedAt;
                return readCompletion(body, request, latency);

            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                lastError = "HTTP " + status + " from AI provider: "
                        + Texts.truncate(Texts.redactSecrets(ex.getResponseBodyAsString()), 300);

                boolean retryable = status == 429 || status >= 500;
                log.warn("AI call failed (attempt {}/{}): {}", attempt, attempts, lastError);
                if (!retryable || attempt == attempts) {
                    break;
                }
                backoff(attempt);

            } catch (ResourceAccessException ex) {
                // Connect/read timeout or DNS failure
                lastError = "AI endpoint unreachable or timed out: " + ex.getMessage();
                log.warn("AI call failed (attempt {}/{}): {}", attempt, attempts, lastError);
                if (attempt == attempts) {
                    break;
                }
                backoff(attempt);

            } catch (Exception ex) {
                lastError = "Unexpected AI client error: " + Texts.redactSecrets(ex.getMessage());
                log.warn("AI call failed (attempt {}/{}): {}", attempt, attempts, lastError);
                break;
            }
        }

        return AiResponse.failed(providerName(), request.model(),
                System.currentTimeMillis() - startedAt, lastError);
    }

    private AiResponse readCompletion(JsonNode body, AiRequest request, long latency) {
        if (body == null) {
            return AiResponse.failed(providerName(), request.model(), latency, "empty AI response");
        }

        JsonNode choices = body.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return AiResponse.failed(providerName(), request.model(), latency,
                    "AI response contained no choices");
        }

        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return AiResponse.failed(providerName(), request.model(), latency,
                    "AI response contained empty content");
        }

        JsonNode usage = body.path("usage");
        Integer promptTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.hasNonNull("completion_tokens")
                ? usage.get("completion_tokens").asInt()
                : null;
        String model = body.hasNonNull("model") ? body.get("model").asText() : request.model();

        log.debug("AI {} stage answered in {} ms using {}", request.stage(), latency, model);
        return AiResponse.ok(content, providerName(), model, latency, promptTokens, completionTokens);
    }

    private void backoff(int attempt) {
        try {
            // 500 ms, 1 s, 2 s ...
            Thread.sleep(Duration.ofMillis(500L * (1L << (attempt - 1))).toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}

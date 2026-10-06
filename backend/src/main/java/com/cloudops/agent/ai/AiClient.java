package com.cloudops.agent.ai;

/**
 * Transport for model calls. Two implementations:
 *
 * <ul>
 *   <li>{@link HttpAiClient} — any OpenAI-compatible /chat/completions endpoint
 *       (OpenAI, Groq, Together, OpenRouter, a local Ollama gateway...). Active when
 *       {@code cloudops.ai.provider=http}.</li>
 *   <li>{@link StubAiClient} — deterministic offline analyser so the pipeline is
 *       demonstrable without an API key. Active when {@code cloudops.ai.provider=stub}.</li>
 * </ul>
 *
 * Implementations must never throw for a remote failure: return
 * {@link AiResponse#failed} instead, and never include the API key in a message or log.
 */
public interface AiClient {

    String providerName();

    /** False when required configuration (such as the API key) is missing. */
    boolean configured();

    AiResponse complete(AiRequest request);
}

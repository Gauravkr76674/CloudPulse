package com.cloudops.agent.ai;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudops.agent.ai.model.AiDiagnosis;
import com.cloudops.agent.ai.model.AiRecommendation;
import com.cloudops.agent.ai.model.AiStageOutcome;
import com.cloudops.agent.ai.model.AiTriage;
import com.cloudops.agent.ai.model.IncidentContext;
import com.cloudops.agent.config.AiProperties;
import com.cloudops.agent.entity.AiStage;
import com.cloudops.agent.util.Texts;

/**
 * The single entry point the rest of the application uses to talk to a model.
 *
 * Responsibilities, in order: pick the model for the stage ({@link AiModelRouter}), build
 * the specialised prompt ({@link PromptLibrary}), send an {@link AiRequest} through the
 * active {@link AiClient}, parse the reply ({@link AiJsonParser}), and hand back an
 * {@link AiStageOutcome} carrying either a typed payload or the reason it is missing.
 *
 * Two deliberate properties:
 * <ul>
 *   <li><b>No exceptions for model problems.</b> A dead endpoint, a timeout, or a reply
 *       full of prose all come back as an unsuccessful outcome, so monitoring and incident
 *       tracking keep working when the AI does not.</li>
 *   <li><b>No execution authority.</b> This class returns text and enum-free records. The
 *       recommendation's {@code action} stays a raw string; mapping it to a real operation
 *       is the remediation layer's job, and only names in {@code ActionType} survive that
 *       mapping. There is no code path from a model reply to an AWS call or a shell.</li>
 * </ul>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiClient aiClient;
    private final AiModelRouter modelRouter;
    private final PromptLibrary promptLibrary;
    private final AiJsonParser parser;
    private final AiProperties aiProperties;

    public AiService(AiClient aiClient,
                     AiModelRouter modelRouter,
                     PromptLibrary promptLibrary,
                     AiJsonParser parser,
                     AiProperties aiProperties) {
        this.aiClient = aiClient;
        this.modelRouter = modelRouter;
        this.promptLibrary = promptLibrary;
        this.parser = parser;
        this.aiProperties = aiProperties;
    }

    // ---------------------------------------------------------------------
    // stage 1 — detection / triage
    // ---------------------------------------------------------------------

    public AiStageOutcome<AiTriage> triage(IncidentContext context) {
        return run(AiStage.DETECTION,
                promptLibrary.userPrompt(AiStage.DETECTION, context),
                parser::parseTriage);
    }

    // ---------------------------------------------------------------------
    // stage 2 — diagnosis
    // ---------------------------------------------------------------------

    public AiStageOutcome<AiDiagnosis> diagnose(IncidentContext context) {
        return run(AiStage.DIAGNOSIS,
                promptLibrary.userPrompt(AiStage.DIAGNOSIS, context),
                parser::parseDiagnosis);
    }

    // ---------------------------------------------------------------------
    // stage 3 — decision / recommendation
    // ---------------------------------------------------------------------

    /**
     * Asks for one action out of {@code context.allowedActions()}. The diagnosis from the
     * previous stage is passed in so the decision is grounded in it rather than re-derived.
     */
    public AiStageOutcome<AiRecommendation> recommend(IncidentContext context, AiDiagnosis diagnosis) {
        return run(AiStage.RECOMMENDATION,
                promptLibrary.recommendationPrompt(context, diagnosis),
                parser::parseRecommendation);
    }

    // ---------------------------------------------------------------------
    // stage 4 — post-remediation verification
    // ---------------------------------------------------------------------

    public AiStageOutcome<AiJsonParser.Verification> verify(IncidentContext context) {
        return run(AiStage.VERIFICATION,
                promptLibrary.userPrompt(AiStage.VERIFICATION, context),
                parser::parseVerification);
    }

    // ---------------------------------------------------------------------
    // introspection for the System page
    // ---------------------------------------------------------------------

    public String providerName() {
        return aiClient.providerName();
    }

    public boolean available() {
        return aiClient.configured();
    }

    public Map<String, String> routingTable() {
        return modelRouter.routingTable();
    }

    public boolean multiModelConfigured() {
        return modelRouter.multiModelConfigured();
    }

    public boolean autoAnalyzeEnabled() {
        return aiProperties.isAutoAnalyzeNewIncidents();
    }

    // ---------------------------------------------------------------------
    // shared execution path
    // ---------------------------------------------------------------------

    /**
     * The one place a model is actually called. Every stage funnels through here so
     * routing, prompt assembly, timing, parsing and failure semantics stay identical.
     */
    private <T> AiStageOutcome<T> run(AiStage stage, String userPrompt, Parser<T> parseFunction) {
        String model = modelRouter.modelFor(stage);

        AiRequest request = new AiRequest(
                stage,
                model,
                promptLibrary.systemPrompt(stage),
                userPrompt,
                aiProperties.getTemperature(),
                aiProperties.getMaxOutputTokens());

        AiResponse response;
        try {
            response = aiClient.complete(request);
        } catch (RuntimeException ex) {
            // A client should never throw, but the pipeline must survive one that does.
            String reason = "AI client threw " + ex.getClass().getSimpleName() + ": "
                    + Texts.redactSecrets(String.valueOf(ex.getMessage()));
            log.warn("{} stage failed: {}", stage, reason);
            return AiStageOutcome.transportFailure(
                    AiResponse.failed(aiClient.providerName(), model, 0L, reason));
        }

        if (!response.success()) {
            log.warn("{} stage returned no usable answer: {}", stage,
                    Texts.redactSecrets(String.valueOf(response.errorMessage())));
            return AiStageOutcome.transportFailure(response);
        }

        Optional<T> parsed = parseFunction.parse(response.content());
        if (parsed.isEmpty()) {
            log.warn("{} stage reply from {} could not be parsed as JSON", stage, model);
            return AiStageOutcome.unparsable(response,
                    "Model reply was not valid JSON for the " + stage + " contract");
        }

        log.debug("{} stage answered by {} in {} ms", stage, response.model(), response.latencyMs());
        return AiStageOutcome.of(parsed.get(), response);
    }

    /** Method reference target for the stage-specific parse call. */
    @FunctionalInterface
    private interface Parser<T> {
        Optional<T> parse(String content);
    }
}

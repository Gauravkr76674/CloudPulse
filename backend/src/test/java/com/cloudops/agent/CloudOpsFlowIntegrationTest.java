package com.cloudops.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cloudops.agent.ai.model.AiRecommendation;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;
import com.cloudops.agent.service.RecommendationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The whole loop, over HTTP, against H2 with the mock cloud provider and the stub model:
 *
 * <pre>
 *   login -> discover resources -> collect CloudWatch metrics -> anomaly detected
 *         -> incident opened -> AI analysis -> recommendation -> ADMIN approval
 *         -> safe predefined action -> action result -> history + audit trail
 * </pre>
 *
 * It runs as one ordered flow because that is what the workflow is: each step consumes what
 * the previous one produced. The mock provider deliberately keeps demo-web-01 above the 90%
 * CPU threshold, so detection has a real signal to find rather than a hand-written fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class CloudOpsFlowIntegrationTest {

    private static final String ADMIN = "admin";
    private static final String OPERATOR = "operator";
    private static final String VIEWER = "viewer";
    private static final String PASSWORD = "Admin@12345";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecommendationService recommendationService;

    private String adminToken;
    private String operatorToken;
    private String viewerToken;
    private Long incidentId;
    private Long resourceId;
    private Long recommendationId;
    private Long actionId;

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String login(String username) throws Exception {
        JsonNode body = json(mockMvc.perform(jsonBody(post("/api/auth/login"),
                        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresInSeconds").asLong()).isPositive();
        assertThat(body.path("user").path("username").asText()).isEqualTo(username);

        String token = body.path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private List<String> textValues(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.path(field).asText());
        }
        return values;
    }

    // ------------------------------------------------------------------
    // 1. security
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("business endpoints require a token; health and login stay open")
    void unauthenticatedRequestsAreRejected() throws Exception {
        for (String path : List.of("/api/resources", "/api/incidents", "/api/dashboard",
                "/api/audit", "/api/actions", "/api/notifications")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/metrics/collect")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @Order(2)
    @DisplayName("the seeded accounts log in and a wrong password does not")
    void seededAccountsCanLogIn() throws Exception {
        adminToken = login(ADMIN);
        operatorToken = login(OPERATOR);
        viewerToken = login(VIEWER);

        mockMvc.perform(jsonBody(post("/api/auth/login"),
                        "{\"username\":\"admin\",\"password\":\"WrongPassword1\"}"))
                .andExpect(status().isUnauthorized());

        JsonNode me = json(mockMvc.perform(as(get("/api/auth/me"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(me.path("username").asText()).isEqualTo(ADMIN);
        assertThat(me.path("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    @Order(3)
    @DisplayName("a VIEWER can read but cannot change anything")
    void viewerIsReadOnly() throws Exception {
        mockMvc.perform(as(get("/api/incidents"), viewerToken)).andExpect(status().isOk());
        mockMvc.perform(as(post("/api/resources/sync"), viewerToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/users"), viewerToken)).andExpect(status().isForbidden());
        mockMvc.perform(as(get("/api/audit"), operatorToken)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 2. discovery and collection
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("sync discovers the provider's instances")
    void syncDiscoversResources() throws Exception {
        JsonNode sync = json(mockMvc.perform(as(post("/api/resources/sync"), adminToken))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(sync.path("provider").asText()).isEqualTo("mock");
        assertThat(sync.path("discovered").asInt()).isPositive();
        assertThat(sync.path("created").asInt()).isPositive();

        JsonNode resources = json(mockMvc.perform(as(get("/api/resources"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(resources.isArray()).isTrue();
        assertThat(resources).isNotEmpty();

        // syncing again must be idempotent, not create duplicates
        JsonNode again = json(mockMvc.perform(as(post("/api/resources/sync"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(again.path("created").asInt()).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("collecting metrics detects the anomaly and opens an incident")
    void collectionOpensAnIncident() throws Exception {
        JsonNode first = json(mockMvc.perform(as(post("/api/metrics/collect"), adminToken))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(first.path("provider").asText()).isEqualTo("mock");
        assertThat(first.path("resourcesPolled").asInt()).isPositive();
        assertThat(first.path("datapointsStored").asInt()).isPositive();
        assertThat(first.path("anomaliesDetected").asInt()).isPositive();
        assertThat(first.path("errors")).isEmpty();

        // a second pass exercises the "incident already open, bump the occurrence count" path
        mockMvc.perform(as(post("/api/metrics/collect"), adminToken)).andExpect(status().isOk());

        JsonNode page = json(mockMvc.perform(as(
                        get("/api/incidents").param("activeOnly", "true"), adminToken))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode incidents = page.path("content");
        assertThat(incidents).as("the hot mock instance must produce an incident").isNotEmpty();

        JsonNode incident = incidents.get(0);
        incidentId = incident.path("id").asLong();
        resourceId = incident.path("resourceId").asLong();
        assertThat(incident.path("metricName").asText()).isEqualTo("CPUUtilization");
        assertThat(incident.path("ruleName").asText()).isNotBlank();
        assertThat(incident.path("severity").asText()).isNotBlank();
        assertThat(incident.path("triggerValue").asDouble()).isGreaterThan(90.0);
        assertThat(incident.path("thresholdValue").asDouble()).isEqualTo(90.0);

        // the series behind the incident is readable for the chart
        JsonNode series = json(mockMvc.perform(as(get("/api/metrics/resource/" + resourceId + "/series")
                        .param("metricName", "CPUUtilization"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(series.path("points")).isNotEmpty();
        assertThat(series.path("thresholdValue").asDouble()).isEqualTo(90.0);

        JsonNode detail = json(mockMvc.perform(as(get("/api/incidents/" + incidentId), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(detail.path("incident").path("id").asLong()).isEqualTo(incidentId);
    }

    // ------------------------------------------------------------------
    // 3. the AI pipeline
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("the AI pipeline records one analysis per stage and proposes a catalog action")
    void aiPipelineProducesARecommendation() throws Exception {
        JsonNode analyses = json(mockMvc.perform(as(
                        post("/api/incidents/" + incidentId + "/analyze"), adminToken))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(analyses).hasSizeGreaterThanOrEqualTo(3);
        for (JsonNode analysis : analyses) {
            assertThat(analysis.path("successful").asBoolean()).isTrue();
            assertThat(analysis.path("provider").asText()).isNotBlank();
            assertThat(analysis.path("modelName").asText()).isNotBlank();
        }
        assertThat(textValues(analyses, "stage"))
                .contains("DETECTION", "DIAGNOSIS", "RECOMMENDATION");

        JsonNode recommendations = json(mockMvc.perform(as(
                        get("/api/incidents/" + incidentId + "/recommendations"), adminToken))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(recommendations).isNotEmpty();
        for (JsonNode recommendation : recommendations) {
            if ("PROPOSED".equals(recommendation.path("status").asText())
                    && !recommendation.path("actionType").isNull()) {
                recommendationId = recommendation.path("id").asLong();
                break;
            }
        }
        assertThat(recommendationId)
                .as("the model must propose an action from the safe catalog")
                .isNotNull();

        JsonNode pending = json(mockMvc.perform(as(get("/api/recommendations/pending"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(pending).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // 4. the approval gate
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("only an ADMIN may approve an action")
    void nonAdminsCannotApprove() throws Exception {
        for (String token : List.of(viewerToken, operatorToken)) {
            mockMvc.perform(as(jsonBody(
                            post("/api/recommendations/" + recommendationId + "/approve"),
                            "{\"dryRun\":true}"), token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @Order(8)
    @DisplayName("an ADMIN dry run walks the whole path without calling the provider")
    void adminDryRunApprovalSucceeds() throws Exception {
        JsonNode action = json(mockMvc.perform(as(jsonBody(
                        post("/api/recommendations/" + recommendationId + "/approve"),
                        "{\"dryRun\":true,\"note\":\"integration test\"}"), adminToken))
                .andExpect(status().isOk())
                .andReturn());

        actionId = action.path("id").asLong();
        assertThat(action.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(action.path("dryRun").asBoolean()).isTrue();
        assertThat(action.path("approvedBy").asText()).isEqualTo(ADMIN);
        assertThat(action.path("requestedBy").asText()).isEqualTo(ADMIN);
        assertThat(action.path("actionType").asText()).isNotBlank();
        assertThat(action.path("resultMessage").asText()).startsWith("DRY RUN:");
        assertThat(action.path("errorMessage").isNull()).isTrue();

        // the same recommendation cannot be approved twice
        mockMvc.perform(as(jsonBody(
                        post("/api/recommendations/" + recommendationId + "/approve"),
                        "{\"dryRun\":true}"), adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(9)
    @DisplayName("an action the model invented can never be approved")
    void unsafeActionIsRefusedEndToEnd() throws Exception {
        // exactly what a hallucinating or prompt-injected model would return
        Recommendation unsafe = recommendationService.createFromAi(incidentId, null,
                new AiRecommendation("aws ec2 terminate-instances --instance-ids i-0abc123",
                        "Terminate the instance", "It is misbehaving", "Removes the problem",
                        "LOW", 0.99, 1, List.of()));

        assertThat(unsafe.getStatus()).isEqualTo(RecommendationStatus.REJECTED_UNSAFE);
        assertThat(unsafe.getActionType()).isNull();

        mockMvc.perform(as(jsonBody(
                        post("/api/recommendations/" + unsafe.getId() + "/approve"),
                        "{\"dryRun\":false}"), adminToken))
                .andExpect(status().isBadRequest());

        // it is kept as evidence rather than silently dropped
        JsonNode blocked = json(mockMvc.perform(as(get("/api/recommendations/blocked"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(textValues(blocked, "aiSuggestedAction"))
                .anyMatch(text -> text.contains("terminate-instances"));
        assertThat(textValues(blocked, "actionType")).allMatch(String::isEmpty);
    }

    // ------------------------------------------------------------------
    // 5. history, audit trail, dashboard
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("the action appears in history and in the audit trail")
    void actionIsRecordedEverywhere() throws Exception {
        JsonNode history = json(mockMvc.perform(as(get("/api/actions"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(history.path("content")).isNotEmpty();
        assertThat(history.path("totalElements").asLong()).isPositive();

        JsonNode one = json(mockMvc.perform(as(get("/api/actions/" + actionId), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(one.path("id").asLong()).isEqualTo(actionId);

        JsonNode incidentActions = json(mockMvc.perform(as(
                        get("/api/incidents/" + incidentId + "/actions"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(incidentActions).isNotEmpty();

        JsonNode audit = json(mockMvc.perform(as(
                        get("/api/audit").param("size", "200"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(textValues(audit.path("content"), "action"))
                .contains("ACTION_APPROVED", "ACTION_EXECUTED", "AI_ACTION_REJECTED_UNSAFE");
    }

    @Test
    @Order(11)
    @DisplayName("the dashboard, catalog, notifications and system status all answer")
    void supportingScreensWork() throws Exception {
        JsonNode dashboard = json(mockMvc.perform(as(get("/api/dashboard"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(dashboard.path("resources").path("total").asLong()).isPositive();
        assertThat(dashboard.path("incidents").path("active").asLong()).isPositive();
        assertThat(dashboard.path("actions").path("succeeded").asLong()).isPositive();
        assertThat(dashboard.path("ai").path("recommendationsRejectedUnsafe").asLong()).isPositive();
        assertThat(dashboard.path("system").path("cloudProvider").asText()).isEqualTo("mock");

        JsonNode catalog = json(mockMvc.perform(as(get("/api/actions/catalog"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(catalog).isNotEmpty();
        // the catalog is the whitelist, so nothing outside it may appear
        assertThat(textValues(catalog, "actionType"))
                .allMatch(name -> com.cloudops.agent.entity.ActionType.parseSafely(name).isPresent());

        JsonNode status = json(mockMvc.perform(as(get("/api/system/status"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(status.path("cloudProvider").asText()).isEqualTo("mock");
        assertThat(status.path("aiProvider").asText()).isEqualTo("stub");
        assertThat(status.path("approvalRequired").asBoolean()).isTrue();

        JsonNode notifications = json(mockMvc.perform(as(get("/api/notifications"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(notifications).isNotEmpty();

        JsonNode unread = json(mockMvc.perform(as(get("/api/notifications/unread-count"), adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(unread.path("unread").asLong()).isPositive();
    }
}

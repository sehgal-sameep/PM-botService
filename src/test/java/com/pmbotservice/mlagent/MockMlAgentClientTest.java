package com.pmbotservice.mlagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.cmbotservice.mlagent.grpc.v1.AnswerEvent;
import com.cmbotservice.mlagent.grpc.v1.AnswerEvent.EventCase;
import com.cmbotservice.mlagent.grpc.v1.Error;
import com.cmbotservice.mlagent.grpc.v1.PolicyCitation;
import com.cmbotservice.mlagent.grpc.v1.PolicyDefinition;
import com.cmbotservice.mlagent.grpc.v1.PolicyManagerAnswerPayload;
import com.cmbotservice.mlagent.grpc.v1.PolicyManagerAnswerPayload.Status;
import com.cmbotservice.mlagent.grpc.v1.SimulationSummary;
import com.cmbotservice.mlagent.grpc.v1.ToolResult;
import com.pmbotservice.common.MlAgentCommunicationException;
import com.pmbotservice.common.MlAgentRejectedException;
import com.pmbotservice.mlagent.rule.CandidateRule;
import com.pmbotservice.mlagent.rule.CandidateRules;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.test.StepVerifier;

class MockMlAgentClientTest {

  private final MockMlAgentClient client = new MockMlAgentClient(5L, 5L);

  private MlAgentRequest request(String message) {
    return new MlAgentRequest(
        "tenant-1",
        "org-1",
        List.of(),
        "msg-1",
        "analyst-1",
        "corr-1",
        "req-1",
        message,
        null,
        null);
  }

  @Test
  void successScenario_emitsTheRealContractsEvents_toolTraceThenChunksThenPayloadThenDone() {
    StepVerifier.create(
            client.streamResponse(request("Give me an overview of the active policies")))
        .assertNext(
            e -> {
              assertThat(e.getEventCase()).isEqualTo(EventCase.TOOL_CALL);
              assertThat(e.getToolCall().getName()).isEqualTo("AgenticGetPolicyContext");
            })
        .assertNext(
            e -> {
              assertThat(e.getEventCase()).isEqualTo(EventCase.TOOL_RESULT);
              assertThat(e.getToolResult().getStatus()).isEqualTo(ToolResult.Status.STATUS_OK);
            })
        .thenConsumeWhile(e -> e.getEventCase() == EventCase.CHUNK)
        .assertNext(
            e -> {
              assertThat(e.getEventCase()).isEqualTo(EventCase.PAYLOAD);
              PolicyManagerAnswerPayload payload = e.getPayload().getPolicyManagerAnswerPayload();
              assertThat(payload.getStatus()).isEqualTo(Status.STATUS_CANDIDATE_READY);
              assertThat(payload.getRecommendation().getCandidateId())
                  .isEqualTo(MockMlAgentClient.CANDIDATE_ID);
              assertThat(payload.getRecommendation().hasPolicyDefinition()).isTrue();
              assertThat(payload.hasSimulation()).isFalse();
              // Every evidence reference resolves to a returned citation.
              assertThat(payload.getCitationsList().stream().map(PolicyCitation::getCitationId))
                  .containsAll(payload.getRecommendation().getCitationIdsList());
            })
        .assertNext(e -> assertThat(e.getEventCase()).isEqualTo(EventCase.DONE))
        .verifyComplete();
  }

  @Test
  void successScenario_toolCallAndResultShareTheSameToolCallId() {
    List<AnswerEvent> events =
        client.streamResponse(request("hello")).collectList().block(Duration.ofSeconds(2));

    assertThat(events).isNotNull();
    assertThat(events.get(0).getToolCall().getToolCallId())
        .isEqualTo(events.get(1).getToolResult().getToolCallId());
  }

  @Test
  void successScenario_chunksCarryTheCannedAnswerText() {
    StepVerifier.create(client.streamResponse(request("Recommend a policy for missed fraud")))
        .expectNextCount(2) // tool_call, tool_result
        .assertNext(
            e -> assertThat(e.getChunk().getDelta()).contains("missed fraud on new devices"))
        .assertNext(e -> assertThat(e.getChunk().getDelta()).contains("device-age signal"))
        .thenCancel()
        .verify(Duration.ofSeconds(2));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "trigger:needs-input, STATUS_NEEDS_INPUT, false, ",
    "trigger:insufficient-data, STATUS_INSUFFICIENT_DATA, false, ",
    "trigger:simulation-pending, STATUS_SIMULATION_PENDING, true, STATUS_RUNNING",
    "trigger:evaluated, STATUS_EVALUATED, true, STATUS_COMPLETED",
    "trigger:simulation-failed, STATUS_SIMULATION_FAILED, true, STATUS_FAILED"
  })
  void statusTrigger_emitsExactlyOnePayloadWithThatStatus_followedByDone(
      String trigger, Status expectedStatus, boolean hasCandidate, String simulationStatus) {
    List<AnswerEvent> events =
        client.streamResponse(request(trigger)).collectList().block(Duration.ofSeconds(2));

    assertThat(events).isNotNull();
    assertThat(events.getLast().getEventCase()).isEqualTo(EventCase.DONE);
    List<AnswerEvent> payloads =
        events.stream().filter(e -> e.getEventCase() == EventCase.PAYLOAD).toList();
    assertThat(payloads).hasSize(1);
    assertThat(events.get(events.size() - 2)).isEqualTo(payloads.getFirst());
    PolicyManagerAnswerPayload payload =
        payloads.getFirst().getPayload().getPolicyManagerAnswerPayload();
    assertThat(payload.getStatus()).isEqualTo(expectedStatus);
    assertThat(payload.hasRecommendation()).isEqualTo(hasCandidate);
    if (simulationStatus == null) {
      assertThat(payload.hasSimulation()).isFalse();
    } else {
      SimulationSummary simulation = payload.getSimulation();
      assertThat(simulation.getStatus())
          .isEqualTo(SimulationSummary.Status.valueOf(simulationStatus));
      assertThat(simulation.getSimulationId()).isEqualTo(MockMlAgentClient.SIMULATION_ID);
      // Metrics only for completed results, matching the referenced candidate version.
      boolean completed = simulation.getStatus() == SimulationSummary.Status.STATUS_COMPLETED;
      assertThat(simulation.hasBaselineMetrics()).isEqualTo(completed);
      assertThat(simulation.hasCandidateMetrics()).isEqualTo(completed);
      assertThat(simulation.getCandidateVersion())
          .isEqualTo(payload.getRecommendation().getCandidateVersion());
    }
  }

  @Test
  void needsInput_carriesFollowUpQuestions() {
    List<AnswerEvent> events =
        client
            .streamResponse(request("trigger:needs-input"))
            .collectList()
            .block(Duration.ofSeconds(2));

    assertThat(events)
        .filteredOn(e -> e.getEventCase() == EventCase.PAYLOAD)
        .singleElement()
        .satisfies(
            e ->
                assertThat(
                        e.getPayload().getPolicyManagerAnswerPayload().getFollowUpQuestionsList())
                    .isNotEmpty());
  }

  @Test
  void errorScenario_failsTheFluxWithACommunicationException() {
    StepVerifier.create(client.streamResponse(request("trigger:error please")))
        .expectError(MlAgentCommunicationException.class)
        .verify(Duration.ofSeconds(2));
  }

  @Test
  void rejectedScenario_failsTheFluxWithMlAgentRejectedException() {
    StepVerifier.create(client.streamResponse(request("trigger:rejected")))
        .expectError(MlAgentRejectedException.class)
        .verify(Duration.ofSeconds(2));
  }

  @Test
  void agentErrorScenario_emitsTheAgentsOwnErrorEvent_ratherThanFailingTheFlux() {
    StepVerifier.create(client.streamResponse(request("trigger:agent-error")))
        .assertNext(
            e -> {
              assertThat(e.getEventCase()).isEqualTo(EventCase.ERROR);
              assertThat(e.getError().getCode()).isEqualTo(Error.Code.ERROR_CODE_DATA_UNAVAILABLE);
              assertThat(e.getError().getRetryable()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void emptyScenario_emitsOnlyDone() {
    StepVerifier.create(client.streamResponse(request("trigger:empty")))
        .assertNext(e -> assertThat(e.getEventCase()).isEqualTo(EventCase.DONE))
        .verifyComplete();
  }

  @Test
  void timeoutScenario_neverEmitsAnything() {
    // The mock has no timeout logic of its own (ChatOrchestrationService's .timeout()
    // operator is what ends this in production) — this just proves it hangs cleanly.
    StepVerifier.create(client.streamResponse(request("trigger:timeout")))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(50))
        .thenCancel()
        .verify(Duration.ofSeconds(2));
  }

  @Test
  void slowScenario_usesTheConfiguredSlowerDelayBetweenChunks() {
    long slowDelayMs = 10_000L;
    MockMlAgentClient slowClient = new MockMlAgentClient(5L, slowDelayMs);

    StepVerifier.withVirtualTime(() -> slowClient.streamResponse(request("trigger:slow")))
        .expectNextCount(2) // tool_call, tool_result arrive immediately
        .expectNoEvent(Duration.ofMillis(slowDelayMs - 100))
        .thenAwait(Duration.ofMillis(200))
        .assertNext(e -> assertThat(e.getEventCase()).isEqualTo(EventCase.CHUNK))
        .thenCancel()
        .verify(Duration.ofSeconds(2));
  }

  private static PolicyManagerAnswerPayload payloadOf(MlAgentRequest request) {
    return client(request).stream()
        .filter(e -> e.getEventCase() == EventCase.PAYLOAD)
        .findFirst()
        .orElseThrow()
        .getPayload()
        .getPolicyManagerAnswerPayload();
  }

  private static List<AnswerEvent> client(MlAgentRequest request) {
    return new MockMlAgentClient(1L, 1L)
        .streamResponse(request)
        .collectList()
        .block(Duration.ofSeconds(2));
  }

  @Test
  void candidatePolicyDefinition_isACompleteRuleFormatRule_scopedToTheCallersTenant() {
    PolicyDefinition definition =
        payloadOf(request("hello")).getRecommendation().getPolicyDefinition();

    assertThat(definition.getFormat()).isEqualTo(CandidateRules.FORMAT);
    assertThat(definition.getSchemaVersion()).isEqualTo(CandidateRules.SCHEMA_VERSION);
    CandidateRule rule = CandidateRules.fromJson(definition.getDefinition());
    assertThat(rule.context()).isEqualTo(new CandidateRule.RuleContext("tenant-1", "org-1"));
    assertThat(rule.id()).as("candidate rules carry a position, not an id").isNull();
    assertThat(rule.position()).isPositive();
    assertThat(rule.details().name()).isNotBlank();
    assertThat(rule.details().choosenStatus()).isNotBlank();
    assertThat(rule.conditions().conditions())
        .allSatisfy(c -> assertThat(c.expressions()).isNotEmpty());
    // The spec's conditional rules: list expressions name a list, others carry a value, and
    // CHALLENGE comes with auth methods.
    assertThat(rule.conditions().conditions().stream().flatMap(c -> c.expressions().stream()))
        .allSatisfy(
            x ->
                assertThat(
                        Boolean.TRUE.equals(x.isListExpression()) ? x.listName() : x.valueString())
                    .isNotBlank());
    assertThat(rule.actions().outcomeAction()).isEqualTo("CHALLENGE");
    assertThat(rule.actions().authMethods()).isNotEmpty();
  }

  @Test
  void identifiersFromThePolicyContext_areEchoedLikeTheRealAgent() {
    MlAgentRequest withContext =
        new MlAgentRequest(
            "tenant-1",
            "org-1",
            List.of(),
            "msg-1",
            "analyst-1",
            "corr-1",
            "req-1",
            "trigger:evaluated",
            new MlAgentRequest.PolicyContext(
                "baseline-09",
                "12",
                "scenario-09",
                null,
                "candidate-09",
                "4",
                null,
                List.of(),
                null),
            null);

    List<AnswerEvent> events = client(withContext);
    PolicyManagerAnswerPayload payload = payloadOf(withContext);

    assertThat(events.getFirst().getToolCall().getArgsJson())
        .isEqualTo("{\"policySetId\":\"baseline-09\",\"scenarioId\":\"scenario-09\"}");
    assertThat(payload.getRecommendation().getCandidateId()).isEqualTo("candidate-09");
    assertThat(payload.getRecommendation().getCandidateVersion()).isEqualTo("4");
    assertThat(payload.getSimulation().getBaselinePolicySetId()).isEqualTo("baseline-09");
    assertThat(payload.getSimulation().getBaselinePolicySetVersion()).isEqualTo("12");
    assertThat(payload.getSimulation().getCandidateVersion()).isEqualTo("4");
  }
}

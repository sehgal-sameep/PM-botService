package com.pmbotservice.mlagent;

import com.cmbotservice.mlagent.grpc.v1.AnswerEvent;
import com.cmbotservice.mlagent.grpc.v1.AnswerPayload;
import com.cmbotservice.mlagent.grpc.v1.Chunk;
import com.cmbotservice.mlagent.grpc.v1.Done;
import com.cmbotservice.mlagent.grpc.v1.Error;
import com.cmbotservice.mlagent.grpc.v1.PolicyCitation;
import com.cmbotservice.mlagent.grpc.v1.PolicyDefinition;
import com.cmbotservice.mlagent.grpc.v1.PolicyImpact;
import com.cmbotservice.mlagent.grpc.v1.PolicyManagerAnswerPayload;
import com.cmbotservice.mlagent.grpc.v1.PolicyManagerAnswerPayload.Status;
import com.cmbotservice.mlagent.grpc.v1.PolicyMetrics;
import com.cmbotservice.mlagent.grpc.v1.PolicyRecommendation;
import com.cmbotservice.mlagent.grpc.v1.SimulationSummary;
import com.cmbotservice.mlagent.grpc.v1.ToolCall;
import com.cmbotservice.mlagent.grpc.v1.ToolResult;
import com.pmbotservice.common.ErrorCode;
import com.pmbotservice.common.MlAgentCommunicationException;
import com.pmbotservice.common.MlAgentRejectedException;
import com.pmbotservice.mlagent.rule.CandidateRule;
import com.pmbotservice.mlagent.rule.CandidateRules;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stand-in for the real ML Agent, used until the ML team's API is available.
 *
 * <p>Fully reactive and cold: nothing runs — no thread, no timer — until the returned {@code Flux}
 * is subscribed, and cancelling that subscription (e.g. the orchestrator's timeout firing, or a
 * client disconnect) stops everything downstream of it for free via Reactor's own cancellation
 * propagation. Behavior is selected via {@link MockScenario} keywords in the message text so every
 * failure mode the real integration must handle is reachable from Swagger/curl with plain text,
 * without polluting the request contract with mock-only fields.
 *
 * <p>Emits the real contract's own {@link AnswerEvent} messages — the exact type {@code
 * GrpcMlAgentClient} relays — so what the frontend receives in mock mode is byte-for-byte the shape
 * it will receive from the real agent: a {@code tool_call}/{@code tool_result} trace, {@code chunk}
 * events, exactly one {@code payload} (a {@code PolicyManagerAnswerPayload}), then {@code done}.
 * The payload status is {@code STATUS_CANDIDATE_READY} by default; each other status has its own
 * trigger keyword (see {@link MockScenario}). The candidate's {@code policy_definition} is a rule
 * in the candidate rule format ({@link CandidateRule}, {@code docs/COMPLETE_RULE_FORMAT.md}), and
 * the baseline/scenario/candidate identifiers echo the request's {@code policyContext} like the
 * real agent's would; everything else (IDs, metrics) is a labeled mock fixture. Transport failures
 * ({@code trigger:error}/{@code trigger:rejected}) are {@code Flux} errors, exactly as {@code
 * GrpcMlAgentClient} surfaces a gRPC status; {@code trigger:agent-error} instead emits the agent's
 * own model-level {@code error} event.
 *
 * <p>Active whenever {@code ml-agent.mode} is {@code mock} (the default).
 */
@Component
@ConditionalOnProperty(
    prefix = "ml-agent",
    name = "mode",
    havingValue = "mock",
    matchIfMissing = true)
@Slf4j
public class MockMlAgentClient implements MlAgentClient {

  static final String CANDIDATE_ID = "mock-candidate-01";
  static final String SIMULATION_ID = "mock-sim-01";
  private static final String POLICY_SET_ID = "mock-baseline-01";
  private static final String POLICY_SET_VERSION = "7";
  private static final String SNAPSHOT_ID = "mock-evaluation-01";
  static final String CANDIDATE_VERSION = "1";
  private static final String SCENARIO_ID = "mock-scenario-01";

  private static final String CANDIDATE_READY_RESPONSE =
      "Historical discovery evidence shows missed fraud on new devices that the current policy "
          + "set does not cover. The proposed refinement adds a device-age signal to the existing "
          + "high-risk transaction policy. It is ready for simulation; no improvement is claimed "
          + "until backtest results are available.";

  private static final String NEEDS_INPUT_RESPONSE =
      "I need to know which baseline policy set and historical scenario to analyze before I can "
          + "recommend a policy.";

  private static final String INSUFFICIENT_DATA_RESPONSE =
      "The selected scenario does not have enough labeled outcomes to support a recommendation.";

  private static final String SIMULATION_PENDING_RESPONSE =
      "The candidate was submitted for backtesting. The simulation is still running; ask again "
          + "shortly to retrieve the results.";

  private static final String EVALUATED_RESPONSE =
      "The candidate raised recall from 0.80 to 0.90 on the evaluation snapshot. The "
          + "false-positive rate rose from 0.018 to 0.020. This is a measured trade-off, not an "
          + "approval.";

  private static final String SIMULATION_FAILED_RESPONSE =
      "The candidate is ready, but its simulation failed, so it has not been evaluated.";

  private final Duration chunkDelay;
  private final Duration slowChunkDelay;

  public MockMlAgentClient(
      @Value("${app.mock-ml-agent.chunk-delay-ms}") long chunkDelayMs,
      @Value("${app.mock-ml-agent.slow-chunk-delay-ms}") long slowChunkDelayMs) {
    this.chunkDelay = Duration.ofMillis(chunkDelayMs);
    this.slowChunkDelay = Duration.ofMillis(slowChunkDelayMs);
    log.info(
        "ML_AGENT_MOCK_CONFIGURED ml-agent.mode=mock — no real ML Agent will be called"
            + " chunkDelayMs={} slowChunkDelayMs={}",
        chunkDelayMs,
        slowChunkDelayMs);
  }

  @Override
  public Flux<AnswerEvent> streamResponse(MlAgentRequest request) {
    MockScenario scenario = MockScenario.fromPrompt(request.message());
    log.info("MOCK_ML_AGENT_CALL_STARTED messageId={} scenario={}", request.messageId(), scenario);

    return switch (scenario) {
      case ERROR ->
          Flux.error(
              new MlAgentCommunicationException("Simulated ML Agent failure (trigger:error)"));
      case REJECTED ->
          Flux.error(
              new MlAgentRejectedException(
                  ErrorCode.NOT_FOUND,
                  "Simulated rejection: resource not found in that tenant (trigger:rejected)"));
      case AGENT_ERROR ->
          Flux.just(
              AnswerEvent.newBuilder()
                  .setError(
                      Error.newBuilder()
                          .setCode(Error.Code.ERROR_CODE_DATA_UNAVAILABLE)
                          .setRetryable(true))
                  .build());
      case EMPTY -> Flux.just(done(0, 0, 0));
      // Deliberately never emits — the orchestrator's own first-response/idle-timeout
      // operator is what ends this stream; the mock has no timer of its own.
      case TIMEOUT -> Flux.never();
      case NEEDS_INPUT ->
          streamAnswer(
              request,
              NEEDS_INPUT_RESPONSE,
              PolicyManagerAnswerPayload.newBuilder()
                  .setStatus(Status.STATUS_NEEDS_INPUT)
                  .addFollowUpQuestions("Which baseline policy set should I compare against?")
                  .addFollowUpQuestions("Which historical scenario should I analyze?")
                  .build(),
              chunkDelay);
      case INSUFFICIENT_DATA ->
          streamAnswer(
              request,
              INSUFFICIENT_DATA_RESPONSE,
              PolicyManagerAnswerPayload.newBuilder()
                  .setStatus(Status.STATUS_INSUFFICIENT_DATA)
                  .addLimitations("Label coverage for the selected scenario is below the minimum.")
                  .build(),
              chunkDelay);
      case SIMULATION_PENDING ->
          streamAnswer(
              request,
              SIMULATION_PENDING_RESPONSE,
              withCandidate(request, Status.STATUS_SIMULATION_PENDING)
                  .setSimulation(simulation(request, SimulationSummary.Status.STATUS_RUNNING))
                  .build(),
              chunkDelay);
      case EVALUATED ->
          streamAnswer(
              request,
              EVALUATED_RESPONSE,
              withCandidate(request, Status.STATUS_EVALUATED)
                  .setSimulation(
                      simulation(request, SimulationSummary.Status.STATUS_COMPLETED)
                          .setBaselineMetrics(metrics(80, 180, 9720, 20, 0.307692, 0.8, 0.018182))
                          .setCandidateMetrics(metrics(90, 200, 9700, 10, 0.310345, 0.9, 0.020202))
                          // net_impact is left absent: its unit is not yet agreed.
                          .setImpact(
                              PolicyImpact.newBuilder()
                                  .setFraudCaptureUplift(0.1)
                                  .setFalsePositiveDelta(20)))
                  .build(),
              chunkDelay);
      case SIMULATION_FAILED ->
          streamAnswer(
              request,
              SIMULATION_FAILED_RESPONSE,
              withCandidate(request, Status.STATUS_SIMULATION_FAILED)
                  .setSimulation(
                      simulation(request, SimulationSummary.Status.STATUS_FAILED)
                          .addLimitations("The simulator rejected the run; it can be retried."))
                  .build(),
              chunkDelay);
      case SLOW ->
          streamAnswer(
              request,
              CANDIDATE_READY_RESPONSE,
              withCandidate(request, Status.STATUS_CANDIDATE_READY).build(),
              slowChunkDelay);
      case SUCCESS ->
          streamAnswer(
              request,
              CANDIDATE_READY_RESPONSE,
              withCandidate(request, Status.STATUS_CANDIDATE_READY).build(),
              chunkDelay);
    };
  }

  private Flux<AnswerEvent> streamAnswer(
      MlAgentRequest request, String text, PolicyManagerAnswerPayload payload, Duration delay) {
    List<String> sentences = splitIntoSentences(text);
    Flux<AnswerEvent> chunks =
        Flux.fromIterable(sentences)
            .delayElements(delay)
            .map(
                sentence ->
                    AnswerEvent.newBuilder()
                        .setChunk(Chunk.newBuilder().setDelta(sentence))
                        .build());
    long approxTokensIn = Math.max(1, text.length() / 4);
    long approxTokensOut = sentences.size() * 10L;
    long approxLatencyMs = sentences.size() * delay.toMillis();
    return Flux.concat(
        Flux.fromIterable(toolTrace(request)),
        chunks,
        Mono.just(
            AnswerEvent.newBuilder()
                .setPayload(AnswerPayload.newBuilder().setPolicyManagerAnswerPayload(payload))
                .build()),
        Mono.just(done(approxLatencyMs, approxTokensIn, approxTokensOut)));
  }

  private static List<AnswerEvent> toolTrace(MlAgentRequest request) {
    String toolCallId = "mock-tool-" + UUID.randomUUID().toString().substring(0, 8);
    return List.of(
        AnswerEvent.newBuilder()
            .setToolCall(
                ToolCall.newBuilder()
                    .setToolCallId(toolCallId)
                    .setName("AgenticGetPolicyContext")
                    .setArgsJson(
                        "{\"policySetId\":\""
                            + policySetId(request)
                            + "\",\"scenarioId\":\""
                            + scenarioId(request)
                            + "\"}"))
            .build(),
        AnswerEvent.newBuilder()
            .setToolResult(
                ToolResult.newBuilder()
                    .setToolCallId(toolCallId)
                    .setStatus(ToolResult.Status.STATUS_OK)
                    .setMs(42)
                    .setRowCount(3))
            .build());
  }

  private static AnswerEvent done(long latencyMs, long tokensIn, long tokensOut) {
    return AnswerEvent.newBuilder()
        .setDone(
            Done.newBuilder()
                .setStopReason(Done.StopReason.STOP_REASON_COMPLETED)
                .setLatencyMs(latencyMs)
                .setTokensIn(tokensIn)
                .setTokensOut(tokensOut))
        .build();
  }

  /** A payload with the fixture candidate, whose every citation id resolves to a citation. */
  private static PolicyManagerAnswerPayload.Builder withCandidate(
      MlAgentRequest request, Status status) {
    String citationId = "mock-evidence-01";
    return PolicyManagerAnswerPayload.newBuilder()
        .setStatus(status)
        .setRecommendation(
            PolicyRecommendation.newBuilder()
                .setCandidateId(candidateId(request))
                .setCandidateVersion(candidateVersion(request))
                .setChangeType(PolicyRecommendation.ChangeType.CHANGE_TYPE_REFINE)
                .addTargetPolicyIds("mock-policy-17")
                .setTitle("Review high-risk transactions on new devices")
                .setDescription("Add a supported device signal to the existing condition.")
                .setRationale("Historical discovery evidence indicates a gap.")
                .setPolicyDefinition(
                    PolicyDefinition.newBuilder()
                        .setFormat(CandidateRules.FORMAT)
                        .setSchemaVersion(CandidateRules.SCHEMA_VERSION)
                        .setDefinition(CandidateRules.toJson(candidateRule(request))))
                .addCitationIds(citationId)
                .addAssumptions("Device age is populated for all card-not-present transactions.")
                .setConfidence(0.72))
        .addCitations(
            PolicyCitation.newBuilder()
                .setCitationId(citationId)
                .setSourceTool("AgenticGetPolicyContext")
                .setSourceRecordId("mock-segment-01")
                .setSnapshotId("mock-discovery-01")
                .addFieldPaths("missedFraudCount"))
        .addLimitations("Mock fixture — not real policy data.");
  }

  private static SimulationSummary.Builder simulation(
      MlAgentRequest request, SimulationSummary.Status status) {
    return SimulationSummary.newBuilder()
        .setSimulationId(SIMULATION_ID)
        .setStatus(status)
        .setCandidateId(candidateId(request))
        .setCandidateVersion(candidateVersion(request))
        .setBaselinePolicySetId(policySetId(request))
        .setBaselinePolicySetVersion(policySetVersion(request))
        .setSnapshotId(SNAPSHOT_ID);
  }

  private static PolicyMetrics metrics(
      long tp, long fp, long tn, long fn, double precision, double recall, double fpr) {
    return PolicyMetrics.newBuilder()
        .setTruePositives(tp)
        .setFalsePositives(fp)
        .setTrueNegatives(tn)
        .setFalseNegatives(fn)
        .setPrecision(precision)
        .setRecall(recall)
        .setFalsePositiveRate(fpr)
        .build();
  }

  /**
   * The fixture candidate in the candidate rule format: a refinement of {@code mock-policy-17}
   * adding a device-age signal, with the CHALLENGE action's auth methods the spec asks for. No
   * {@code id} (that is for existing rules only); {@code position} places it in the policy set.
   */
  static CandidateRule candidateRule(MlAgentRequest request) {
    return new CandidateRule(
        null,
        5,
        null,
        "ai-orchestrator",
        null,
        null,
        null,
        new CandidateRule.RuleContext(request.tenantId(), request.organization()),
        request.tenantId(),
        null,
        false,
        false,
        true,
        List.of("new_device_risk"),
        new CandidateRule.RuleDetails(
            "Review high-risk transactions on new devices",
            "Challenges high-value transfers from devices first seen in the last 2 days.",
            "WORK_IN_PROGRESS",
            null,
            5,
            100,
            List.of(new CandidateRule.EventType("TRANSFER"), new CandidateRule.EventType("WIRE")),
            List.of("WEB", "MOBILE"),
            "AI-generated candidate; not yet backtested.",
            null,
            false),
        new CandidateRule.RuleConditions(
            List.of(
                new CandidateRule.RuleCondition(
                    "cond-1",
                    true,
                    false,
                    List.of(
                        new CandidateRule.RuleExpression(
                            "riskScore",
                            "GREATER_OR_EQUALS",
                            "INTEGER",
                            "DEFAULT",
                            "700",
                            false,
                            null),
                        new CandidateRule.RuleExpression(
                            "deviceAgeDays", "LESS_THAN", "INTEGER", "DEFAULT", "2", false, null),
                        new CandidateRule.RuleExpression(
                            "destinationCountry",
                            "NOT_IN",
                            "STRING",
                            "DEFAULT",
                            null,
                            true,
                            "user-known-countries"))))),
        new CandidateRule.RuleActions(
            "CHALLENGE",
            false,
            true,
            List.of(new CandidateRule.AuthMethod("OTP", "One-Time Password")),
            "new-device-challenge",
            List.of(new CandidateRule.CaseCreation("SUSPICIOUS", "HIGH"))));
  }

  // The real agent echoes the identifiers it was asked about; the mock does the same, falling
  // back to labeled fixtures when the caller sent none.

  private static String policySetId(MlAgentRequest request) {
    return orDefault(request, MlAgentRequest.PolicyContext::policySetId, POLICY_SET_ID);
  }

  private static String policySetVersion(MlAgentRequest request) {
    return orDefault(request, MlAgentRequest.PolicyContext::policySetVersion, POLICY_SET_VERSION);
  }

  private static String scenarioId(MlAgentRequest request) {
    return orDefault(request, MlAgentRequest.PolicyContext::scenarioId, SCENARIO_ID);
  }

  private static String candidateId(MlAgentRequest request) {
    return orDefault(request, MlAgentRequest.PolicyContext::candidateId, CANDIDATE_ID);
  }

  private static String candidateVersion(MlAgentRequest request) {
    return orDefault(request, MlAgentRequest.PolicyContext::candidateVersion, CANDIDATE_VERSION);
  }

  private static String orDefault(
      MlAgentRequest request,
      Function<MlAgentRequest.PolicyContext, String> field,
      String fallback) {
    String value = request.policyContext() == null ? null : field.apply(request.policyContext());
    return value == null ? fallback : value;
  }

  private static List<String> splitIntoSentences(String text) {
    return List.of(text.split("(?<=[.?])\\s+"));
  }
}

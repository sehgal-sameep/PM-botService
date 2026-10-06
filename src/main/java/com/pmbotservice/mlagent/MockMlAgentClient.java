package com.pmbotservice.mlagent;

import com.pmbotservice.common.ErrorCode;
import com.pmbotservice.common.MlAgentCommunicationException;
import com.pmbotservice.common.MlAgentRejectedException;
import com.pmbotservice.mlagent.grpc.v1.AnswerEvent;
import com.pmbotservice.mlagent.grpc.v1.AnswerPayload;
import com.pmbotservice.mlagent.grpc.v1.Chunk;
import com.pmbotservice.mlagent.grpc.v1.Done;
import com.pmbotservice.mlagent.grpc.v1.Error;
import com.pmbotservice.mlagent.grpc.v1.GeneratedPolicy;
import com.pmbotservice.mlagent.grpc.v1.PolicyManagerAnswerPayload;
import com.pmbotservice.mlagent.grpc.v1.ToolCall;
import com.pmbotservice.mlagent.grpc.v1.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
 * events, a {@code payload} (with a citation on every key signal), then {@code done}. A message
 * asking to generate/create a policy (or containing {@code trigger:generate-policy}) gets a {@code
 * generated_policy} event in place of the {@code payload}, carrying a sample policy JSON. Transport
 * failures ({@code trigger:error}/{@code trigger:rejected}) are {@code Flux} errors, exactly as
 * {@code GrpcMlAgentClient} surfaces a gRPC status; {@code trigger:agent-error} instead emits the
 * agent's own model-level {@code error} event.
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

  private static final String OVERVIEW_RESPONSE =
      "The active policy set contains three transaction-monitoring policies. The high-value "
          + "transfer policy flags single transfers above the configured amount threshold. The "
          + "velocity policy flags an unusual number of transactions in a short time window. The "
          + "geolocation policy flags a mismatch between the transaction origin and the "
          + "customer's known location history.";

  private static final String RULES_RESPONSE =
      "The velocity policy is made of two rules. The first counts transactions per customer "
          + "over a rolling one-hour window. The second raises an alert when that count exceeds "
          + "the configured limit for the customer's segment.";

  private static final String GENERIC_RESPONSE =
      "Based on the current policy configuration, existing policies already cover high-value "
          + "transfers, transaction velocity, and geolocation mismatches. Ask me to generate a "
          + "new policy if you need coverage for a scenario these do not address.";

  private static final String GENERATED_POLICY_RESPONSE =
      "Here is a new policy based on your request. It flags transfers above 10000 to a "
          + "beneficiary added within the last 24 hours. Review the thresholds before saving it.";

  static final String GENERATED_POLICY_JSON =
      "{\"name\":\"New beneficiary high-value transfer\","
          + "\"description\":\"Flags high-value transfers to recently added beneficiaries\","
          + "\"enabled\":false,"
          + "\"conditions\":["
          + "{\"field\":\"amount\",\"operator\":\"GREATER_THAN\",\"value\":10000},"
          + "{\"field\":\"beneficiary_age_hours\",\"operator\":\"LESS_THAN\",\"value\":24}],"
          + "\"action\":\"CREATE_ALERT\"}";

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
      case GENERATE_POLICY -> streamGeneratedPolicy(chunkDelay);
      case SLOW -> streamChunks(request.message(), slowChunkDelay);
      default ->
          wantsGeneratedPolicy(request.message())
              ? streamGeneratedPolicy(chunkDelay)
              : streamChunks(request.message(), chunkDelay);
    };
  }

  private Flux<AnswerEvent> streamChunks(String message, Duration delay) {
    return streamAnswer(
        selectCannedResponse(message),
        AnswerEvent.newBuilder().setPayload(buildPayload()).build(),
        delay);
  }

  /** The policy-generation flow: tool trace, explanatory chunks, then the policy itself. */
  private Flux<AnswerEvent> streamGeneratedPolicy(Duration delay) {
    return streamAnswer(
        GENERATED_POLICY_RESPONSE,
        AnswerEvent.newBuilder()
            .setGeneratedPolicy(GeneratedPolicy.newBuilder().setPolicyJson(GENERATED_POLICY_JSON))
            .build(),
        delay);
  }

  private Flux<AnswerEvent> streamAnswer(String text, AnswerEvent structured, Duration delay) {
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
        Flux.fromIterable(toolTrace()),
        chunks,
        Mono.just(structured),
        Mono.just(done(approxLatencyMs, approxTokensIn, approxTokensOut)));
  }

  private static List<AnswerEvent> toolTrace() {
    String toolCallId = "mock-tool-" + UUID.randomUUID().toString().substring(0, 8);
    return List.of(
        AnswerEvent.newBuilder()
            .setToolCall(
                ToolCall.newBuilder()
                    .setToolCallId(toolCallId)
                    .setName("searchPolicies")
                    .setArgsJson("{\"status\":\"ACTIVE\"}"))
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

  /** Every key signal carries a citation, as the real contract requires. */
  private static AnswerPayload buildPayload() {
    String citationId =
        "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    return AnswerPayload.newBuilder()
        .setPolicyManagerAnswerPayload(
            PolicyManagerAnswerPayload.newBuilder()
                .addKeySignals(
                    PolicyManagerAnswerPayload.KeySignal.newBuilder()
                        .setSignal("Velocity policy is active for all customer segments")
                        .addCitations(citationId))
                .addCitations(
                    PolicyManagerAnswerPayload.Citation.newBuilder()
                        .setId(citationId)
                        .setSource("searchPolicies")
                        .addFields("policies[1].rules")))
        .build();
  }

  private static List<String> splitIntoSentences(String text) {
    return List.of(text.split("(?<=[.])\\s+"));
  }

  private static String selectCannedResponse(String message) {
    String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
    if (lower.contains("summar") || lower.contains("list") || lower.contains("overview")) {
      return OVERVIEW_RESPONSE;
    }
    if (lower.contains("rule")) {
      return RULES_RESPONSE;
    }
    return GENERIC_RESPONSE;
  }

  /** e.g. "Generate a policy for ..." / "Create a new policy that ...". */
  private static boolean wantsGeneratedPolicy(String message) {
    String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
    return (lower.contains("generate") || lower.contains("create")) && lower.contains("policy");
  }
}

package com.pmbotservice.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.cmbotservice.mlagent.grpc.v1.AnswerEvent;
import com.cmbotservice.mlagent.grpc.v1.AnswerPayload;
import com.cmbotservice.mlagent.grpc.v1.Chunk;
import com.cmbotservice.mlagent.grpc.v1.Done;
import com.cmbotservice.mlagent.grpc.v1.Error;
import com.cmbotservice.mlagent.grpc.v1.Ping;
import com.cmbotservice.mlagent.grpc.v1.PolicyCitation;
import com.cmbotservice.mlagent.grpc.v1.PolicyManagerAnswerPayload;
import com.cmbotservice.mlagent.grpc.v1.PolicyMetrics;
import com.cmbotservice.mlagent.grpc.v1.PolicyRecommendation;
import com.cmbotservice.mlagent.grpc.v1.SimulationSummary;
import com.cmbotservice.mlagent.grpc.v1.ToolCall;
import com.cmbotservice.mlagent.grpc.v1.ToolResult;
import com.google.protobuf.util.JsonFormat;
import com.pmbotservice.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

/**
 * Pins the frontend-facing wire format: each ML Agent {@link AnswerEvent} becomes one SSE event
 * whose name is the agent's own oneof field name and whose data is the agent's own message in
 * canonical proto3 JSON — no renamed fields, no renamed events, no reshaping, nothing dropped.
 */
class SseEventsTest {

  private static final AnswerEvent CHUNK =
      AnswerEvent.newBuilder().setChunk(Chunk.newBuilder().setDelta("Hello")).build();

  private static final AnswerEvent TOOL_CALL =
      AnswerEvent.newBuilder()
          .setToolCall(
              ToolCall.newBuilder()
                  .setToolCallId("t-1")
                  .setName("searchPolicies")
                  .setArgsJson("{\"status\":\"ACTIVE\"}"))
          .build();

  private static final AnswerEvent TOOL_RESULT =
      AnswerEvent.newBuilder()
          .setToolResult(
              ToolResult.newBuilder()
                  .setToolCallId("t-1")
                  .setStatus(ToolResult.Status.STATUS_OK)
                  .setMs(42)
                  .setRowCount(7))
          .build();

  private static final AnswerEvent PAYLOAD =
      AnswerEvent.newBuilder()
          .setPayload(
              AnswerPayload.newBuilder()
                  .setPolicyManagerAnswerPayload(
                      PolicyManagerAnswerPayload.newBuilder()
                          .setStatus(PolicyManagerAnswerPayload.Status.STATUS_CANDIDATE_READY)
                          .setRecommendation(
                              PolicyRecommendation.newBuilder()
                                  .setCandidateId("candidate-01")
                                  .setCandidateVersion("1")
                                  .setChangeType(PolicyRecommendation.ChangeType.CHANGE_TYPE_NEW)
                                  .setTitle("t")
                                  .addCitationIds("e-1"))
                          .addCitations(
                              PolicyCitation.newBuilder()
                                  .setCitationId("e-1")
                                  .setSourceTool("AgenticGetPolicyContext")
                                  .addFieldPaths("missedFraudCount")
                                  .setSourceRecordId("segment-01")
                                  .setSnapshotId("discovery-01"))))
          .build();

  /** Completed run whose candidate precision is undefined (zero denominator). */
  private static final AnswerEvent EVALUATED_PAYLOAD =
      AnswerEvent.newBuilder()
          .setPayload(
              AnswerPayload.newBuilder()
                  .setPolicyManagerAnswerPayload(
                      PolicyManagerAnswerPayload.newBuilder()
                          .setStatus(PolicyManagerAnswerPayload.Status.STATUS_EVALUATED)
                          .setSimulation(
                              SimulationSummary.newBuilder()
                                  .setSimulationId("sim-01")
                                  .setStatus(SimulationSummary.Status.STATUS_COMPLETED)
                                  .setBaselineMetrics(
                                      PolicyMetrics.newBuilder()
                                          .setTruePositives(80)
                                          .setFalsePositives(180)
                                          .setTrueNegatives(9720)
                                          .setFalseNegatives(20)
                                          .setPrecision(0.307692)
                                          .setRecall(0.8)
                                          .setFalsePositiveRate(0.018182))
                                  .setCandidateMetrics(
                                      PolicyMetrics.newBuilder()
                                          .setTrueNegatives(9900)
                                          .setFalseNegatives(100)
                                          .setRecall(0.0)
                                          .setFalsePositiveRate(0.0)))))
          .build();

  private static final AnswerEvent DONE =
      AnswerEvent.newBuilder()
          .setDone(
              Done.newBuilder()
                  .setStopReason(Done.StopReason.STOP_REASON_COMPLETED)
                  .setLatencyMs(1800)
                  .setTokensIn(12)
                  .setTokensOut(140))
          .build();

  private static final AnswerEvent ERROR =
      AnswerEvent.newBuilder()
          .setError(
              Error.newBuilder().setCode(Error.Code.ERROR_CODE_MODEL_REFUSED).setRetryable(false))
          .build();

  private static final AnswerEvent PING =
      AnswerEvent.newBuilder().setPing(Ping.newBuilder()).build();

  @Test
  void eventName_isTheAgentsOwnOneofFieldName_forEveryEventType() {
    assertThat(SseEvents.fromAnswerEvent(CHUNK).event()).isEqualTo("chunk");
    assertThat(SseEvents.fromAnswerEvent(TOOL_CALL).event()).isEqualTo("tool_call");
    assertThat(SseEvents.fromAnswerEvent(TOOL_RESULT).event()).isEqualTo("tool_result");
    assertThat(SseEvents.fromAnswerEvent(PAYLOAD).event()).isEqualTo("payload");
    assertThat(SseEvents.fromAnswerEvent(DONE).event()).isEqualTo("done");
    assertThat(SseEvents.fromAnswerEvent(ERROR).event()).isEqualTo("error");
    assertThat(SseEvents.fromAnswerEvent(PING).event()).isEqualTo("ping");
  }

  @Test
  void everyOneofArmOfTheContract_hasANameDerivedFromTheDescriptor() {
    // Guards against a new arm being added to chat_agent.proto without flowing through:
    // every oneof field is exercised by name, none via a hand-written mapping.
    List<String> contractArms =
        AnswerEvent.getDescriptor().getOneofs().getFirst().getFields().stream()
            .map(f -> f.getName())
            .toList();
    assertThat(contractArms)
        .containsExactly("chunk", "tool_call", "tool_result", "payload", "done", "error", "ping");
  }

  @Test
  void data_keepsTheAgentsFieldNamesNestingAndEnumNames_untranslated() {
    assertThat(data(CHUNK)).isEqualTo("{\"chunk\":{\"delta\":\"Hello\"}}");
    assertThat(data(TOOL_CALL))
        .isEqualTo(
            "{\"tool_call\":{\"tool_call_id\":\"t-1\",\"name\":\"searchPolicies\","
                + "\"args_json\":\"{\\\"status\\\":\\\"ACTIVE\\\"}\"}}");
    assertThat(data(TOOL_RESULT))
        .isEqualTo(
            "{\"tool_result\":{\"tool_call_id\":\"t-1\",\"status\":\"STATUS_OK\","
                + "\"ms\":\"42\",\"row_count\":\"7\"}}");
    assertThat(data(PAYLOAD))
        .isEqualTo(
            "{\"payload\":{\"policy_manager_answer_payload\":{"
                // Printed in field-number order, which is the contract's listing order.
                + "\"status\":\"STATUS_CANDIDATE_READY\","
                + "\"recommendation\":{\"candidate_id\":\"candidate-01\",\"candidate_version\":\"1\","
                + "\"change_type\":\"CHANGE_TYPE_NEW\",\"target_policy_ids\":[],\"title\":\"t\","
                + "\"description\":\"\",\"rationale\":\"\",\"citation_ids\":[\"e-1\"],"
                + "\"assumptions\":[]},"
                + "\"citations\":[{\"citation_id\":\"e-1\","
                + "\"source_tool\":\"AgenticGetPolicyContext\","
                + "\"source_record_id\":\"segment-01\",\"snapshot_id\":\"discovery-01\","
                + "\"field_paths\":[\"missedFraudCount\"]}],"
                + "\"follow_up_questions\":[],\"limitations\":[]}}}");
    assertThat(data(DONE))
        .isEqualTo(
            "{\"done\":{\"stop_reason\":\"STOP_REASON_COMPLETED\",\"latency_ms\":\"1800\","
                + "\"tokens_in\":\"12\",\"tokens_out\":\"140\"}}");
    assertThat(data(ERROR))
        .isEqualTo("{\"error\":{\"code\":\"ERROR_CODE_MODEL_REFUSED\",\"retryable\":false}}");
    assertThat(data(PING)).isEqualTo("{\"ping\":{}}");
  }

  @Test
  void payload_absentMessagesAndUndefinedMetrics_areOmitted_neverZeroFilled() {
    String json = data(EVALUATED_PAYLOAD);

    // No recommendation was produced: absent, not an empty object.
    assertThat(json).doesNotContain("\"recommendation\"");
    // No impact was reported: absent, not an empty object.
    assertThat(json).doesNotContain("\"impact\"");
    // Candidate precision is undefined (zero denominator): omitted, not 0.
    assertThat(json)
        .contains(
            "\"candidate_metrics\":{\"true_positives\":\"0\",\"false_positives\":\"0\","
                + "\"true_negatives\":\"9900\",\"false_negatives\":\"100\",\"recall\":0.0,"
                + "\"false_positive_rate\":0.0}")
        // Counts are int64, so JSON strings; rates are numbers.
        .contains(
            "\"baseline_metrics\":{\"true_positives\":\"80\",\"false_positives\":\"180\","
                + "\"true_negatives\":\"9720\",\"false_negatives\":\"20\","
                + "\"precision\":0.307692,\"recall\":0.8,\"false_positive_rate\":0.018182}");
  }

  @Test
  void payloadArm_usesFieldNumber2_leaving1ForTheSharedContractsCaseManagerArm() {
    // The Policy Manager arm takes a new number; 1 stays the shared contract's Case Manager arm.
    assertThat(
            AnswerPayload.getDescriptor()
                .findFieldByName("policy_manager_answer_payload")
                .getNumber())
        .isEqualTo(2);
    assertThat(AnswerPayload.getDescriptor().findFieldByNumber(1)).isNull();
  }

  @Test
  void data_neverDropsFieldsThatHoldTheirDefaultValue() {
    AnswerEvent allDefaults =
        AnswerEvent.newBuilder()
            .setToolResult(ToolResult.newBuilder()) // status UNSPECIFIED, ms 0, no row_count
            .build();

    assertThat(data(allDefaults))
        .isEqualTo(
            "{\"tool_result\":{\"tool_call_id\":\"\",\"status\":\"STATUS_UNSPECIFIED\",\"ms\":\"0\"}}");
  }

  @Test
  void data_roundTripsBackToTheIdenticalProtoMessage_forEveryEventType() throws Exception {
    for (AnswerEvent original :
        List.of(CHUNK, TOOL_CALL, TOOL_RESULT, PAYLOAD, EVALUATED_PAYLOAD, DONE, ERROR, PING)) {
      AnswerEvent.Builder parsed = AnswerEvent.newBuilder();
      JsonFormat.parser().merge(data(original), parsed);
      assertThat(parsed.build())
          .as("round trip of %s", original.getEventCase())
          .isEqualTo(original);
    }
  }

  @Test
  void serviceError_isTheOnlyBackendDefinedEvent_andUsesItsOwnDistinctName() {
    ServiceErrorEvent error =
        new ServiceErrorEvent("m-1", ErrorCode.ML_AGENT_TIMEOUT, "timed out", Instant.EPOCH);

    ServerSentEvent<Object> sse = SseEvents.fromServiceError(error);

    assertThat(sse.event()).isEqualTo("service_error").isNotEqualTo("error");
    assertThat(sse.data()).isEqualTo(error);
  }

  @Test
  void withId_addsOnlyTheTransportFrameId_leavingNameAndDataUntouched() {
    ServerSentEvent<Object> original = SseEvents.fromAnswerEvent(TOOL_CALL);

    ServerSentEvent<Object> numbered = SseEvents.withId(3, original);

    assertThat(numbered.id()).isEqualTo("3");
    assertThat(numbered.event()).isEqualTo(original.event());
    assertThat(numbered.data()).isEqualTo(original.data());
  }

  private static String data(AnswerEvent event) {
    Object data = SseEvents.fromAnswerEvent(event).data();
    assertThat(data).isInstanceOf(String.class);
    return (String) data;
  }
}

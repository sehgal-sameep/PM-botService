package com.pmbotservice.web.controller;

import com.pmbotservice.context.RequestContext;
import com.pmbotservice.context.RequestContextResolver;
import com.pmbotservice.service.ChatOrchestrationService;
import com.pmbotservice.web.ApiPaths;
import com.pmbotservice.web.dto.ChatRequest;
import com.pmbotservice.web.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

/**
 * The one endpoint this stateless orchestrator exposes: forward a chat message to the ML Agent and
 * stream its response back over SSE, event for event, untranslated. There is no conversation
 * resource here to create, fetch, or close — see {@link ChatRequest} and the SSE contract
 * documented on {@link #sendMessage}. Fully non-blocking: the returned {@code Flux} is subscribed
 * and written by Reactor Netty as elements arrive, never buffered.
 */
@RestController
@Tag(
    name = "Chat",
    description =
        "Stateless chat message orchestration — forwards each message to the "
            + "ML Agent (through a circuit breaker, bulkhead, timeout, and limited retry) and streams its "
            + "response events back over SSE exactly as the ML Agent sent them. This backend holds no conversation state between requests; conversation "
            + "memory (if any) is owned by the ML Agent.")
public class ChatController {

  static final String SSE_EXAMPLE_CANDIDATE_READY =
      """
      id:0
      event:tool_call
      data:{"tool_call":{"tool_call_id":"t-1","name":"AgenticGetPolicyContext","args_json":"{\\"policySetId\\":\\"baseline-01\\",\\"scenarioId\\":\\"scenario-01\\"}"}}

      id:1
      event:tool_result
      data:{"tool_result":{"tool_call_id":"t-1","status":"STATUS_OK","ms":"42","row_count":"3"}}

      id:2
      event:chunk
      data:{"chunk":{"delta":"The proposed refinement is ready for simulation."}}

      id:3
      event:ping
      data:{"ping":{}}

      id:4
      event:payload
      data:{"payload":{"policy_manager_answer_payload":{"status":"STATUS_CANDIDATE_READY","recommendation":{"candidate_id":"candidate-01","candidate_version":"1","change_type":"CHANGE_TYPE_REFINE","target_policy_ids":["policy-17"],"title":"Review high-risk transactions on new devices","description":"Add a supported device signal to the existing condition.","rationale":"Historical discovery evidence indicates a gap.","policy_definition":{"format":"candidate-rule-format","schema_version":"1.0","definition":"{\\"position\\":5,\\"context\\":{\\"name\\":\\"tenant-123\\",\\"id\\":\\"org-123\\"},\\"details\\":{\\"name\\":\\"Review high-risk transactions on new devices\\",\\"choosenStatus\\":\\"WORK_IN_PROGRESS\\",\\"ruleOrder\\":5,\\"eventTypes\\":[{\\"name\\":\\"TRANSFER\\"}]},\\"conditions\\":{\\"conditions\\":[{\\"isConjunctive\\":true,\\"expressions\\":[{\\"operand\\":\\"deviceAgeDays\\",\\"operator\\":\\"LESS_THAN\\",\\"factType\\":\\"INTEGER\\",\\"factCategory\\":\\"DEFAULT\\",\\"valueString\\":\\"2\\",\\"isListExpression\\":false}]}]},\\"actions\\":{\\"outcomeAction\\":\\"CHALLENGE\\",\\"authMethods\\":[{\\"name\\":\\"OTP\\",\\"displayName\\":\\"One-Time Password\\"}]}}"},"citation_ids":["evidence-01"],"assumptions":[],"confidence":0.72},"citations":[{"citation_id":"evidence-01","source_tool":"AgenticGetPolicyContext","source_record_id":"segment-01","snapshot_id":"discovery-01","field_paths":["missedFraudCount"]}],"follow_up_questions":[],"limitations":[]}}}

      id:5
      event:done
      data:{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"1800","tokens_in":"12","tokens_out":"140"}}
      """;

  static final String SSE_EXAMPLE_EVALUATED =
      """
      id:0
      event:chunk
      data:{"chunk":{"delta":"The candidate raised recall from 0.80 to 0.90 on the evaluation snapshot."}}

      id:1
      event:payload
      data:{"payload":{"policy_manager_answer_payload":{"status":"STATUS_EVALUATED","recommendation":{"candidate_id":"candidate-01","candidate_version":"1","change_type":"CHANGE_TYPE_REFINE","target_policy_ids":["policy-17"],"title":"Review high-risk transactions on new devices","description":"","rationale":"","citation_ids":[],"assumptions":[]},"simulation":{"simulation_id":"sim-01","status":"STATUS_COMPLETED","candidate_id":"candidate-01","candidate_version":"1","baseline_policy_set_id":"baseline-01","baseline_policy_set_version":"7","snapshot_id":"evaluation-01","baseline_metrics":{"true_positives":"80","false_positives":"180","true_negatives":"9720","false_negatives":"20","precision":0.307692,"recall":0.8,"false_positive_rate":0.018182},"candidate_metrics":{"true_positives":"90","false_positives":"200","true_negatives":"9700","false_negatives":"10","precision":0.310345,"recall":0.9,"false_positive_rate":0.020202},"limitations":[],"impact":{"fraud_capture_uplift":0.1,"false_positive_delta":"20"}},"citations":[],"follow_up_questions":[],"limitations":[]}}}

      id:2
      event:done
      data:{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"2400","tokens_in":"30","tokens_out":"160"}}
      """;

  static final String SSE_EXAMPLE_NEEDS_INPUT =
      """
      id:0
      event:chunk
      data:{"chunk":{"delta":"Which baseline policy set and historical scenario should I analyze?"}}

      id:1
      event:payload
      data:{"payload":{"policy_manager_answer_payload":{"status":"STATUS_NEEDS_INPUT","citations":[],"follow_up_questions":["Which baseline policy set should I compare against?"],"limitations":[]}}}

      id:2
      event:done
      data:{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"900","tokens_in":"10","tokens_out":"40"}}
      """;

  static final String SSE_EXAMPLE_AGENT_ERROR =
      """
      id:0
      event:error
      data:{"error":{"code":"ERROR_CODE_DATA_UNAVAILABLE","retryable":true}}
      """;

  static final String SSE_EXAMPLE_SERVICE_ERROR =
      """
      id:0
      event:service_error
      data:{"messageId":"5b1e...","errorCode":"ML_AGENT_TIMEOUT","errorMessage":"The AI agent did not respond in time.","timestamp":"2026-09-23T10:15:30Z"}
      """;

  private final ChatOrchestrationService chatOrchestrationService;
  private final RequestContextResolver requestContextResolver;

  public ChatController(
      ChatOrchestrationService chatOrchestrationService,
      RequestContextResolver requestContextResolver) {
    this.chatOrchestrationService = chatOrchestrationService;
    this.requestContextResolver = requestContextResolver;
  }

  @PostMapping(value = ApiPaths.CHAT_MESSAGES, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Send a chat message and stream the ML Agent's response over SSE",
      description =
          """
                    Stateless pass-through: this endpoint validates the request, forwards it to \
                    the ML Agent (through a circuit breaker, bulkhead, timeout, and limited retry — \
                    see the architecture doc), and streams the response back. It does not store the \
                    message, the response, or any conversation history — nothing here persists \
                    between requests.

                    The `X-Tenant-Id` request header and the body's `message` are required; the \
                    `X-Org-Id` header, `operatorId`, `policyContext`, `requestId` and `history` are \
                    optional. Every field the ML Agent asks for comes from this body and is \
                    forwarded as-is: `operatorId` (the authenticated operator) becomes \
                    `operator_id`, and `policyContext` (policySetId, policySetVersion, scenarioId, \
                    caseId, candidateId, candidateVersion, simulationId, missedFraudIndicatorIds, \
                    historicalSummary — all optional) becomes \
                    `policy_context`. The ML Agent's \
                    contract has no conversation/continuation \
                    identifier at all — `history` is the sole resumption mechanism: omit it to start \
                    a new conversation, or resend the full transcript (oldest turn first) to continue \
                    one. `requestId` is an optional caller-generated identifier forwarded for \
                    tracing/correlation only (idempotency-friendly, not deduplicated anywhere). The \
                    caller — not this backend — is responsible for remembering and resending `history`.

                    SSE event contract — the ML Agent's (`ChatAgent.AskPolicyManager`)                     response, forwarded as-is. This backend does not rename events or fields,                     reshape payloads, or add its own wrapper. For every ML Agent `AnswerEvent`:
                    - `event:` is the name of the `AnswerEvent` oneof field that is set — one of                     `chunk`, `tool_call`, `tool_result`, `payload`, `done`, `error`, `ping`.
                    - `data:` is that whole `AnswerEvent` as canonical proto3 JSON, with the                     original `.proto` field names and nesting (see `src/main/proto/**/*.proto`): e.g.                     `{"tool_call":{"tool_call_id":"t-1","name":"searchPolicies","args_json":"{...}"}}`.                     Enums are their proto names (`STATUS_OK`, `STOP_REASON_TRUNCATED`,                     `ERROR_CODE_MODEL_REFUSED`), fields at their default value are still present,                     and `int64` fields (`ms`, `row_count`, `latency_ms`, `tokens_in`, `tokens_out`,                     the confusion-matrix counts and `false_positive_delta`) are JSON strings, per the proto3 JSON mapping.                     Unset `optional` scalars (`precision`, `recall`, `false_positive_rate`, `confidence`,                     `fraud_capture_uplift`, `net_impact`, citation `snapshot_id`) and absent messages                     (`recommendation`, `simulation`, `policy_definition`, metrics, `impact`) are                     omitted rather than zero-filled.
                    - `id:` is a 0-based frame counter added by this backend (SSE transport only).

                    Events arrive in the order the ML Agent produced them. The ML Agent's own                     contract rules apply unchanged: consecutive `chunk` events form one text block;                     `payload` may arrive at any point; `done` and `error` are terminal; `ping` is a                     keepalive with no content; clients must ignore event names they don't recognise.                     The natural-language answer streams through `chunk`; the structured result                     arrives through `payload` (`policy_manager_answer_payload`): a `status`                     (STATUS_NEEDS_INPUT, STATUS_CANDIDATE_READY, STATUS_SIMULATION_PENDING,                     STATUS_EVALUATED, STATUS_INSUFFICIENT_DATA, STATUS_SIMULATION_FAILED), an                     optional `recommendation` (with an optional executable `policy_definition` whose `definition` is a JSON string holding a                     rule in the candidate rule format — see the `CandidateRule` schema below; `format`                     `candidate-rule-format`, `schema_version` `1.0` — plus `assumptions` and an                     optional `confidence`),                     an optional `simulation` summary (with an optional baseline-vs-candidate `impact`), `citations`, `follow_up_questions` and                     `limitations`. A successful turn emits one `payload`, followed by `done`;                     `done` means the chat turn finished — a simulation can still be pending.                     The ML Agent's `error` event (a model-level failure: `code`, `retryable`) is                     forwarded like any other event, never translated.

                    The one event this backend adds: `service_error` —                     { messageId, errorCode, errorMessage, timestamp } — sent (terminal) only when a                     failure produced no ML Agent event of its own: the agent could not be reached,                     timed out, or rejected the call at the transport level, or this backend's own                     circuit breaker/bulkhead/validation stopped it. `errorCode` is one of                     ML_AGENT_TIMEOUT, ML_AGENT_UNAVAILABLE, ML_AGENT_ERROR, NOT_FOUND,                     VALIDATION_ERROR, CONCURRENCY_LIMIT_REACHED (bulkhead full or circuit breaker                     open), INTERNAL_ERROR. Because the HTTP status is already committed to 200 by                     then, these always arrive on this same stream, never as a different HTTP status.

                    For local testing, the mock ML Agent recognizes these keywords anywhere in \
                    `message` to simulate each failure mode: `trigger:slow`, `trigger:timeout`, \
                    `trigger:error`, `trigger:empty`, `trigger:rejected`, `trigger:agent-error`, and \
                    each payload status: `trigger:needs-input`, `trigger:insufficient-data`, \
                    `trigger:simulation-pending`, `trigger:evaluated`, `trigger:simulation-failed` \
                    (default: STATUS_CANDIDATE_READY).

                    Swagger UI's "Try it out" does not render a live SSE stream well (it waits \
                    for the connection to end, then shows the buffered body). To watch events \
                    arrive in real time, use curl instead:

                    ```
                    curl -N -X POST "http://localhost:8080/back-office-ai-pm/api/v1/chat/messages" \\
                      -H "Content-Type: application/json" \\
                      -H "Accept: text/event-stream" \\
                      -H "X-User-Id: analyst-1" \\
                      -H "X-Tenant-Id: tenant-123" \\
                      -H "X-Org-Id: org-123" \\
                      -d '{"operatorId":"analyst-1","message":"Recommend a policy for missed fraud and compare it with our current policies.","policyContext":{"policySetId":"baseline-01","scenarioId":"scenario-01"}}'
                    ```
                    """)
  @ApiResponse(
      responseCode = "200",
      description =
          "SSE stream of the ML Agent's AnswerEvents, forwarded as-is (plus `service_error` for "
              + "backend/transport failures)",
      content =
          @Content(
              mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
              schema = @Schema(type = "string"),
              examples = {
                @ExampleObject(
                    name = "Candidate ready",
                    summary = "tool trace, streamed text, recommendation payload, done",
                    value = SSE_EXAMPLE_CANDIDATE_READY),
                @ExampleObject(
                    name = "Evaluated",
                    summary = "recommendation with completed baseline/candidate simulation metrics",
                    value = SSE_EXAMPLE_EVALUATED),
                @ExampleObject(
                    name = "Needs input",
                    summary = "no baseline/scenario resolvable: follow-up questions",
                    value = SSE_EXAMPLE_NEEDS_INPUT),
                @ExampleObject(
                    name = "ML Agent error event",
                    summary = "the agent's own model-level error, forwarded untouched",
                    value = SSE_EXAMPLE_AGENT_ERROR),
                @ExampleObject(
                    name = "Backend/transport failure",
                    summary = "no agent event to forward, so this backend emits service_error",
                    value = SSE_EXAMPLE_SERVICE_ERROR)
              }))
  @ApiResponse(
      responseCode = "400",
      description =
          "Validation error (e.g. blank/missing X-Tenant-Id or message, or an oversized"
              + " field)",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  public Flux<ServerSentEvent<Object>> sendMessage(
      @RequestBody @Valid ChatRequest request, ServerWebExchange exchange) {
    RequestContext context = requestContextResolver.resolve(exchange);
    return chatOrchestrationService.streamMessage(context, request);
  }
}

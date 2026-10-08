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

  static final String SSE_EXAMPLE_SUCCESS =
      """
      id:0
      event:tool_call
      data:{"tool_call":{"tool_call_id":"t-1","name":"searchPolicies","args_json":"{\\"status\\":\\"ACTIVE\\"}"}}

      id:1
      event:tool_result
      data:{"tool_result":{"tool_call_id":"t-1","status":"STATUS_OK","ms":"42","row_count":"7"}}

      id:2
      event:chunk
      data:{"chunk":{"delta":"The active policy set contains three transaction-monitoring policies."}}

      id:3
      event:ping
      data:{"ping":{}}

      id:4
      event:payload
      data:{"payload":{"policy_manager_answer_payload":{"key_signals":[{"signal":"Velocity policy is active","citations":["pol-2"]}],"citations":[{"id":"pol-2","source":"searchPolicies","fields":["policies[1].rules"]}]}}}

      id:5
      event:done
      data:{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"1800","tokens_in":"12","tokens_out":"140"}}
      """;

  static final String SSE_EXAMPLE_GENERATED_POLICY =
      """
      id:0
      event:chunk
      data:{"chunk":{"delta":"Here is a new policy based on your request."}}

      id:1
      event:generated_policy
      data:{"generated_policy":{"policy_json":"{\\"name\\":\\"New beneficiary high-value transfer\\",\\"enabled\\":false}"}}

      id:2
      event:done
      data:{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"1500","tokens_in":"20","tokens_out":"180"}}
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
                    `X-Org-Id` header, `operatorId`, `requestId` and `history` are optional. Each message \
                    is plain question/answer. The ML Agent's contract has no conversation/continuation \
                    identifier at all — `history` is the sole resumption mechanism: omit it to start \
                    a new conversation, or resend the full transcript (oldest turn first) to continue \
                    one. `requestId` is an optional caller-generated identifier forwarded for \
                    tracing/correlation only (idempotency-friendly, not deduplicated anywhere). The \
                    caller — not this backend — is responsible for remembering and resending `history`.

                    SSE event contract — the ML Agent's (`ChatAgent.AskPolicyManager`)                     response, forwarded as-is. This backend does not rename events or fields,                     reshape payloads, or add its own wrapper. For every ML Agent `AnswerEvent`:
                    - `event:` is the name of the `AnswerEvent` oneof field that is set — one of                     `chunk`, `tool_call`, `tool_result`, `payload`, `generated_policy`, `done`,                     `error`, `ping`.
                    - `data:` is that whole `AnswerEvent` as canonical proto3 JSON, with the                     original `.proto` field names and nesting (see `src/main/proto/*.proto`): e.g.                     `{"tool_call":{"tool_call_id":"t-1","name":"searchPolicies","args_json":"{...}"}}`.                     Enums are their proto names (`STATUS_OK`, `STOP_REASON_TRUNCATED`,                     `ERROR_CODE_MODEL_REFUSED`), fields at their default value are still present,                     and `int64` fields (`ms`, `row_count`, `latency_ms`, `tokens_in`, `tokens_out`)                     are JSON strings, per the proto3 JSON mapping.
                    - `id:` is a 0-based frame counter added by this backend (SSE transport only).

                    Events arrive in the order the ML Agent produced them. The ML Agent's own                     contract rules apply unchanged: consecutive `chunk` events form one text block;                     `payload` may arrive at any point; `done` and `error` are terminal; `ping` is a                     keepalive with no content; clients must ignore event names they don't recognise.                     `generated_policy` is sent when the user asked the agent to create a policy:                     `policy_json` is the new policy as a JSON string (parse it client-side). It is                     not terminal — the stream still ends with `done` (keep the policy) or `error`                     (discard it with the rest of the turn).                     The ML Agent's `error` event (a model-level failure: `code`, `retryable`) is                     forwarded like any other event, never translated.

                    The one event this backend adds: `service_error` —                     { messageId, errorCode, errorMessage, timestamp } — sent (terminal) only when a                     failure produced no ML Agent event of its own: the agent could not be reached,                     timed out, or rejected the call at the transport level, or this backend's own                     circuit breaker/bulkhead/validation stopped it. `errorCode` is one of                     ML_AGENT_TIMEOUT, ML_AGENT_UNAVAILABLE, ML_AGENT_ERROR, NOT_FOUND,                     VALIDATION_ERROR, CONCURRENCY_LIMIT_REACHED (bulkhead full or circuit breaker                     open), INTERNAL_ERROR. Because the HTTP status is already committed to 200 by                     then, these always arrive on this same stream, never as a different HTTP status.

                    For local testing, the mock ML Agent recognizes these keywords anywhere in \
                    `message` to simulate each failure mode: `trigger:slow`, `trigger:timeout`, \
                    `trigger:error`, `trigger:empty`, `trigger:rejected`, `trigger:agent-error`,                     `trigger:generate-policy` (or just ask it to "generate a policy").

                    Swagger UI's "Try it out" does not render a live SSE stream well (it waits \
                    for the connection to end, then shows the buffered body). To watch events \
                    arrive in real time, use curl instead:

                    ```
                    curl -N -X POST "http://localhost:8080/back-office-ai/pm/api/v1/chat/messages" \\
                      -H "Content-Type: application/json" \\
                      -H "Accept: text/event-stream" \\
                      -H "X-User-Id: analyst-1" \\
                      -H "X-Tenant-Id: tenant-123" \\
                      -H "X-Org-Id: org-123" \\
                      -d '{"message":"Generate a policy that flags high-value transfers to new beneficiaries"}'
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
                    name = "Successful answer",
                    summary = "tool trace, streamed text, structured payload, done",
                    value = SSE_EXAMPLE_SUCCESS),
                @ExampleObject(
                    name = "Generated policy",
                    summary = "the user asked for a new policy: text, generated_policy, done",
                    value = SSE_EXAMPLE_GENERATED_POLICY),
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

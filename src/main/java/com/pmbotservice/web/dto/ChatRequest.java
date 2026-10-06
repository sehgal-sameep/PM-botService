package com.pmbotservice.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The single request shape for this stateless orchestrator: everything the ML Agent needs for one
 * message, self-contained. There is nothing to look up server-side — {@code history} is just
 * forwarded, not validated against any stored record. A Policy Manager conversation is plain
 * question/answer. {@code tenantId}/{@code organization} are <b>not</b> here — the frontend sends
 * those as the {@code X-Tenant-Id}/{@code X-Org-Id} request headers instead (see {@code
 * RequestContextResolver}), not the body.
 */
public record ChatRequest(
    @Size(max = 50, message = "history must contain at most 50 turns")
        @Schema(
            description =
                "Explicit conversation transcript, oldest turn first. The ML Agent's contract has "
                    + "no continuation token or conversation id — resending the full transcript on every "
                    + "message is the only resumption mechanism. Omit (or send empty) to start a new "
                    + "conversation. This backend does not assemble, store, or interpret this transcript — "
                    + "the caller owns remembering and resending it.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@Valid HistoryTurn> history,
    @Size(max = 100, message = "requestId must be at most 100 characters")
        @Schema(
            description =
                "Optional caller-generated request identifier, forwarded to the ML Agent "
                    + "and included in logs for tracing/correlation only (idempotency-friendly by "
                    + "design, but not enforced or deduplicated anywhere in this stateless service).",
            example = "req-a1b2c3",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String requestId,
    @Size(max = 200, message = "operatorId must be at most 200 characters")
        @Schema(
            description =
                "Optional identifier of the operator making the request, forwarded as-is to the "
                    + "ML Agent (operator_id), only when sent. Never defaulted, and never filled "
                    + "from the X-User-Id header or the BFF session.",
            example = "analyst-1",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String operatorId,
    @NotBlank(message = "message must not be blank")
        @Size(max = 4000, message = "message must be at most 4000 characters")
        @Schema(
            description =
                "The user's message — a question about policies, or a request to generate a new "
                    + "policy. For local testing, the mock ML Agent recognizes the keywords "
                    + "'trigger:slow', 'trigger:timeout', 'trigger:error', 'trigger:empty', "
                    + "'trigger:rejected' and 'trigger:generate-policy' anywhere in this text to "
                    + "simulate that scenario.",
            example = "Generate a policy that flags high-value transfers to new beneficiaries")
        String message) {

  /** A blank {@code operatorId} means "not sent" — normalized to {@code null}, never forwarded. */
  public ChatRequest {
    operatorId = blankToNull(operatorId);
  }

  private static String blankToNull(String value) {
    return value != null && value.isBlank() ? null : value;
  }
}

package com.pmbotservice.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The single request shape for this stateless orchestrator: everything the ML Agent needs for one
 * message, self-contained. There is nothing to look up server-side — {@code history} is just
 * forwarded, not validated against any stored record. {@code tenantId}/{@code organization} are
 * <b>not</b> here — the frontend sends those as the {@code X-Tenant-Id}/{@code X-Org-Id} request
 * headers instead (see {@code RequestContextResolver}), not the body. Every other field the ML
 * Agent contract asks for ({@code operator_id}, {@code policy_context}) is taken from this body and
 * forwarded as-is — this backend derives none of them.
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
                "Optional identifier of the authenticated operator making the request, forwarded"
                    + " as-is to the ML Agent (operator_id), only when sent. Never defaulted, and"
                    + " never filled from the X-User-Id header or the BFF session.",
            example = "458272",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String operatorId,
    @Valid
        @Schema(
            description =
                "Optional Policy Manager context — baseline policy set, historical scenario, and"
                    + " any case seed, candidate or simulation being discussed. Forwarded as-is to"
                    + " the ML Agent (policy_context).",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        PolicyContext policyContext,
    @NotBlank(message = "message must not be blank")
        @Size(max = 4000, message = "message must be at most 4000 characters")
        @Schema(
            description =
                "The user's question — e.g. a request to recommend or refine a policy and compare"
                    + " it with the baseline. For local testing, the mock ML Agent recognizes the"
                    + " keywords 'trigger:slow', 'trigger:timeout', 'trigger:error',"
                    + " 'trigger:agent-error', 'trigger:empty', 'trigger:rejected',"
                    + " 'trigger:needs-input', 'trigger:insufficient-data',"
                    + " 'trigger:simulation-pending', 'trigger:evaluated' and"
                    + " 'trigger:simulation-failed' anywhere in this text to simulate that scenario.",
            example =
                "Recommend a policy for missed fraud and compare it with our current policies.")
        String message) {

  /** A blank {@code operatorId} means "not sent" — normalized to {@code null}, never forwarded. */
  public ChatRequest {
    operatorId = blankToNull(operatorId);
  }

  private static String blankToNull(String value) {
    return value != null && value.isBlank() ? null : value;
  }
}

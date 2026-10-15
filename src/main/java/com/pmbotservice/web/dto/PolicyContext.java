package com.pmbotservice.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What a Policy Manager question is about — forwarded as-is to the ML Agent's {@code
 * PolicyManagerContext} (see {@code src/main/proto/policy_manager/v1/policy_manager.proto}). Every
 * field is optional here: this backend never resolves defaults or checks that the resources exist.
 * When the agent cannot resolve an unambiguous baseline/scenario, it answers with status {@code
 * STATUS_NEEDS_INPUT} and a follow-up question instead. Values are not interpreted or
 * range-checked. Blank strings mean "not supplied" and are normalized to {@code null}; an absent
 * {@code missedFraudIndicatorIds} is normalized to an empty list.
 */
@Schema(description = "Policy Manager context for the question. Every field is optional.")
public record PolicyContext(
    @Size(max = 200, message = "policyContext.policySetId must be at most 200 characters")
        @Schema(description = "Baseline policy set.", example = "baseline-01")
        String policySetId,
    @Size(max = 100, message = "policyContext.policySetVersion must be at most 100 characters")
        @Schema(description = "Exact baseline policy set version.", example = "7")
        String policySetVersion,
    @Size(max = 200, message = "policyContext.scenarioId must be at most 200 characters")
        @Schema(
            description = "Historical scenario selected for analysis/backtesting.",
            example = "scenario-01")
        String scenarioId,
    @Size(max = 200, message = "policyContext.caseId must be at most 200 characters")
        @Schema(
            description =
                "Optional case that prompted the investigation. An investigation seed only; it"
                    + " does not define the evaluation population.",
            example = "case-123")
        String caseId,
    @Size(max = 200, message = "policyContext.candidateId must be at most 200 characters")
        @Schema(
            description = "Optional existing recommendation being discussed.",
            example = "candidate-01")
        String candidateId,
    @Size(max = 100, message = "policyContext.candidateVersion must be at most 100 characters")
        @Schema(description = "Optional version of candidateId being refined.", example = "1")
        String candidateVersion,
    @Size(max = 200, message = "policyContext.simulationId must be at most 200 characters")
        @Schema(
            description = "Optional simulation run whose results are being discussed.",
            example = "sim-01")
        String simulationId,
    @Size(
            max = 100,
            message = "policyContext.missedFraudIndicatorIds must contain at most 100 entries")
        @Schema(
            description =
                "Optional missed-fraud indicators the analysis starts from (PRD 8.3 LLM input).",
            example = "[\"indicator-01\", \"indicator-02\"]")
        List<
                @NotBlank(
                    message = "policyContext.missedFraudIndicatorIds entries must not be blank")
                @Size(
                    max = 200,
                    message =
                        "policyContext.missedFraudIndicatorIds entries must be at most 200"
                            + " characters")
                String>
            missedFraudIndicatorIds,
    @Size(max = 8000, message = "policyContext.historicalSummary must be at most 8000 characters")
        @Schema(
            description = "Optional summary of the relevant history (PRD 8.3).",
            example = "Card-not-present fraud on new devices rose 18% over the last 30 days.")
        String historicalSummary) {

  public PolicyContext {
    policySetId = blankToNull(policySetId);
    policySetVersion = blankToNull(policySetVersion);
    scenarioId = blankToNull(scenarioId);
    caseId = blankToNull(caseId);
    candidateId = blankToNull(candidateId);
    candidateVersion = blankToNull(candidateVersion);
    simulationId = blankToNull(simulationId);
    // Not List.copyOf: it throws on a null entry before bean validation can reject it as a 400.
    missedFraudIndicatorIds = missedFraudIndicatorIds == null ? List.of() : missedFraudIndicatorIds;
    historicalSummary = blankToNull(historicalSummary);
  }

  private static String blankToNull(String value) {
    return value != null && value.isBlank() ? null : value;
  }
}

package com.pmbotservice.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRequestTest {

  @Test
  void blankPolicyContextStrings_areNormalizedToNull_andAbsentIndicatorsToEmpty() {
    PolicyContext context = new PolicyContext("  ", "", " ", "\t", "  ", " ", "", null, " ");

    assertThat(context)
        .isEqualTo(new PolicyContext(null, null, null, null, null, null, null, List.of(), null));
  }

  @Test
  void suppliedPolicyContextValues_areKeptAsIs() {
    PolicyContext context =
        new PolicyContext(
            "baseline-01",
            "7",
            "scenario-01",
            "case-9",
            "candidate-01",
            "2",
            "sim-01",
            List.of("indicator-01", "indicator-02"),
            "Fraud on new devices rose 18%.");

    ChatRequest request = new ChatRequest(null, null, null, context, "hello");

    assertThat(request.policyContext().policySetId()).isEqualTo("baseline-01");
    assertThat(request.policyContext().policySetVersion()).isEqualTo("7");
    assertThat(request.policyContext().scenarioId()).isEqualTo("scenario-01");
    assertThat(request.policyContext().caseId()).isEqualTo("case-9");
    assertThat(request.policyContext().candidateId()).isEqualTo("candidate-01");
    assertThat(request.policyContext().candidateVersion()).isEqualTo("2");
    assertThat(request.policyContext().simulationId()).isEqualTo("sim-01");
    assertThat(request.policyContext().missedFraudIndicatorIds())
        .containsExactly("indicator-01", "indicator-02");
    assertThat(request.policyContext().historicalSummary())
        .isEqualTo("Fraud on new devices rose 18%.");
  }
}

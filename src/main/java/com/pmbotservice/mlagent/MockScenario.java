package com.pmbotservice.mlagent;

import java.util.Locale;

/**
 * Behaviors {@link MockMlAgentClient} can simulate, selected by a keyword anywhere in the analyst's
 * message text (e.g. {@code "trigger:timeout"}). Keeping the trigger in the message body — rather
 * than a separate mock-only request field — keeps the public request contract identical to what the
 * real ML Agent will eventually receive, while still making every failure mode trivially
 * reproducible from Swagger or curl.
 */
public enum MockScenario {
  /** Payload status {@code STATUS_CANDIDATE_READY} — the default. */
  SUCCESS(null),
  SLOW("trigger:slow"),
  TIMEOUT("trigger:timeout"),
  ERROR("trigger:error"),
  /** The agent's own in-stream {@code error} event (model-level), as opposed to {@link #ERROR}. */
  AGENT_ERROR("trigger:agent-error"),
  EMPTY("trigger:empty"),
  /** Payload status {@code STATUS_NEEDS_INPUT}, with follow-up questions. */
  NEEDS_INPUT("trigger:needs-input"),
  /** Payload status {@code STATUS_INSUFFICIENT_DATA}, with no recommendation. */
  INSUFFICIENT_DATA("trigger:insufficient-data"),
  /** Payload status {@code STATUS_SIMULATION_PENDING}: a candidate plus a running simulation. */
  SIMULATION_PENDING("trigger:simulation-pending"),
  /**
   * Payload status {@code STATUS_EVALUATED}: a candidate plus completed baseline/candidate metrics.
   */
  EVALUATED("trigger:evaluated"),
  /** Payload status {@code STATUS_SIMULATION_FAILED}: a candidate plus a failed simulation. */
  SIMULATION_FAILED("trigger:simulation-failed"),
  REJECTED("trigger:rejected");

  private final String keyword;

  MockScenario(String keyword) {
    this.keyword = keyword;
  }

  public static MockScenario fromPrompt(String prompt) {
    if (prompt == null) {
      return SUCCESS;
    }
    String lower = prompt.toLowerCase(Locale.ROOT);
    for (MockScenario scenario : values()) {
      if (scenario.keyword != null && lower.contains(scenario.keyword)) {
        return scenario;
      }
    }
    return SUCCESS;
  }
}

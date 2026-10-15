package com.pmbotservice.mlagent.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Reads and writes {@link CandidateRule}s as the JSON string carried in {@code
 * PolicyDefinition.definition}. Parsing is as forgiving as the format allows: unknown properties
 * are ignored, property names match case-insensitively, a single value is accepted where a list is
 * expected, and scalars are coerced (e.g. {@code "5"} → {@code 5}).
 */
public final class CandidateRules {

  /**
   * {@code PolicyDefinition.format} for a rule in this representation. TF Labs' contract leaves
   * {@code format} free-form (their open question 1); this is the value to agree with them.
   */
  public static final String FORMAT = "candidate-rule-format";

  /** {@code PolicyDefinition.schema_version}: the spec's version. */
  public static final String SCHEMA_VERSION = "1.0";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
          .build();

  private CandidateRules() {}

  /** The rule as compact JSON, absent fields omitted. */
  public static String toJson(CandidateRule rule) {
    try {
      return MAPPER.writeValueAsString(rule);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Candidate rule could not be serialized", e);
    }
  }

  /**
   * Parses a {@code definition} string.
   *
   * @throws IllegalArgumentException if it is not a JSON object
   */
  public static CandidateRule fromJson(String json) {
    try {
      CandidateRule rule = MAPPER.readValue(json, CandidateRule.class);
      if (rule == null) {
        throw new IllegalArgumentException("Candidate rule definition is JSON null");
      }
      return rule;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Candidate rule definition is not valid rule JSON: " + e.getOriginalMessage(), e);
    }
  }
}

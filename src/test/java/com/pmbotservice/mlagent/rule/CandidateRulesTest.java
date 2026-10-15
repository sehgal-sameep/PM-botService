package com.pmbotservice.mlagent.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CandidateRulesTest {

  /** The full-rule examples in the spec: every ```json block with no "..." placeholder. */
  private static List<String> specExamples() throws IOException {
    return examplesIn("docs/COMPLETE_RULE_FORMAT.md");
  }

  private static List<String> examplesIn(String file) throws IOException {
    String spec = Files.readString(Path.of(file));
    Matcher m = Pattern.compile("```json\\r?\\n(.*?)```", Pattern.DOTALL).matcher(spec);
    List<String> blocks = new java.util.ArrayList<>();
    while (m.find()) {
      String block = m.group(1);
      if (block.contains("\"details\"")
          && block.contains("\"actions\"")
          && !block.contains("...")) {
        blocks.add(block);
      }
    }
    return blocks;
  }

  @Test
  void everyCompleteExampleInTheSpec_parses_andSurvivesARoundTrip() throws IOException {
    List<String> examples = specExamples();
    assertThat(examples).hasSize(3);

    for (String example : examples) {
      CandidateRule rule = CandidateRules.fromJson(example);
      assertThat(rule.details().name()).isNotBlank();
      assertThat(rule.actions().outcomeAction()).isNotBlank();
      assertThat(rule.conditions().conditions()).isNotEmpty();
      assertThat(CandidateRules.fromJson(CandidateRules.toJson(rule))).isEqualTo(rule);
    }
  }

  @Test
  void specExample2_mapsEveryNestedField() throws IOException {
    CandidateRule rule = CandidateRules.fromJson(specExamples().get(1));

    assertThat(rule.position()).isEqualTo(5);
    assertThat(rule.isCtoRule()).isFalse();
    assertThat(rule.outcomeCustomFacts())
        .containsExactly("high_velocity_detected", "new_account_risk");
    assertThat(rule.details().eventTypes())
        .extracting(CandidateRule.EventType::name)
        .containsExactly("TRANSFER", "WIRE");
    assertThat(rule.details().comments().getFirst().createdBy()).isEqualTo("reviewer@bank.com");
    CandidateRule.RuleCondition second = rule.conditions().conditions().get(1);
    assertThat(second.isConjunctive()).isFalse();
    assertThat(second.expressions().getFirst().isListExpression()).isTrue();
    assertThat(second.expressions().getFirst().listName()).isEqualTo("user-known-countries");
    assertThat(rule.actions().authMethods()).hasSize(2);
    assertThat(rule.actions().caseCreations().getFirst().urgencyLevel()).isEqualTo("HIGH");
  }

  @Test
  void everyFieldIsOptional_evenTheOnesTheSpecCallsRequired() {
    CandidateRule rule = CandidateRules.fromJson("{}");

    assertThat(rule.details()).isNull();
    assertThat(rule.conditions()).isNull();
    assertThat(rule.actions()).isNull();
    assertThat(CandidateRules.toJson(rule)).isEqualTo("{}");
  }

  @Test
  void parsing_isLenient_unknownFields_aliases_caseAndScalarCoercion_singleValueLists() {
    CandidateRule rule =
        CandidateRules.fromJson(
            """
            {"futureField": {"x": 1}, "Position": "7", "ctoRule": true,
             "details": {"name": "n", "channelIndicators": "WEB", "choosenStatus": "BRAND_NEW"},
             "conditions": {"conditions": [{"conjunctive": true, "negativeRule": false,
               "expressions": [{"operand": "EMAIL_DOMAIN", "operator": "EQUALS",
                                "value": "test@example.com", "listExpression": false}]}]}}
            """);

    assertThat(rule.position()).isEqualTo(7);
    assertThat(rule.isCtoRule()).isTrue();
    assertThat(rule.details().channelIndicators()).containsExactly("WEB");
    assertThat(rule.details().choosenStatus()).isEqualTo("BRAND_NEW");
    CandidateRule.RuleCondition condition = rule.conditions().conditions().getFirst();
    assertThat(condition.isConjunctive()).isTrue();
    assertThat(condition.isNegativeRule()).isFalse();
    assertThat(condition.expressions().getFirst().valueString()).isEqualTo("test@example.com");
    assertThat(condition.expressions().getFirst().isListExpression()).isFalse();
  }

  @Test
  void serialization_usesTheSpecsPropertyNames_andOmitsAbsentFields() {
    String json =
        CandidateRules.toJson(
            new CandidateRule(
                null,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                new CandidateRule.RuleConditions(
                    List.of(
                        new CandidateRule.RuleCondition(
                            null,
                            true,
                            null,
                            List.of(
                                new CandidateRule.RuleExpression(
                                    "a", "IN", null, null, null, true, "l"))))),
                null));

    assertThat(json)
        .isEqualTo(
            "{\"position\":3,\"isCtoRule\":false,\"conditions\":{\"conditions\":[{"
                + "\"isConjunctive\":true,\"expressions\":[{\"operand\":\"a\",\"operator\":\"IN\","
                + "\"isListExpression\":true,\"listName\":\"l\"}]}]}}");
  }

  @Test
  void nonJsonOrNonObjectDefinitions_areRejected() {
    assertThatThrownBy(() -> CandidateRules.fromJson("risk_score >= 700"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CandidateRules.fromJson("[1,2]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CandidateRules.fromJson("null"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theExampleSharedWithTfLabs_parsesToTheDocumentedRule() throws IOException {
    List<String> examples = examplesIn("docs/CANDIDATE_RULE_FORMAT.md");
    assertThat(examples).hasSize(1);

    CandidateRule rule = CandidateRules.fromJson(examples.getFirst());
    assertThat(rule.id()).isNull();
    assertThat(rule.position()).isEqualTo(5);
    assertThat(rule.context().name()).isEqualTo("tenant-01");
    assertThat(rule.details().choosenStatus()).isEqualTo("WORK_IN_PROGRESS");
    assertThat(rule.conditions().conditions().getFirst().expressions()).hasSize(3);
    assertThat(rule.actions().authMethods()).isNotEmpty();
    assertThat(CandidateRules.fromJson(CandidateRules.toJson(rule))).isEqualTo(rule);
  }
}

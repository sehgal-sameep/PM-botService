package com.pmbotservice.mlagent.rule;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A candidate policy rule in the simulation engine's candidate rule format (v1.0, see {@code
 * docs/COMPLETE_RULE_FORMAT.md}) — the JSON carried, serialized, in the contract's {@code
 * PolicyRecommendation.policy_definition.definition} when {@code format} is {@link
 * CandidateRules#FORMAT}.
 *
 * <p>Deliberately lenient, so the model never rejects a rule the spec (or a later revision of it)
 * would accept: every field is optional and nullable — including the ones the spec marks required,
 * which are the simulator's to enforce, not ours — enum-like values ({@code choosenStatus}, {@code
 * operator}, {@code outcomeAction}, ...) are plain strings, unknown properties are ignored, and the
 * {@code is}-prefixed booleans also accept their unprefixed spelling. Absent fields are omitted
 * when serialized, never written as {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    name = "CandidateRule",
    description =
        "Candidate policy rule in the candidate rule format v1.0 (docs/COMPLETE_RULE_FORMAT.md),"
            + " carried as a JSON string in"
            + " payload.policy_manager_answer_payload.recommendation.policy_definition.definition"
            + " when policy_definition.format is \""
            + CandidateRules.FORMAT
            + "\". Every field is optional.")
public record CandidateRule(
    String id,
    Integer position,
    Long creationDate,
    String createdBy,
    Long modificationDate,
    String modifiedBy,
    Long deletedDate,
    RuleContext context,
    String contextName,
    CandidateRule originalRule,
    @JsonProperty("isCtoRule") @JsonAlias("ctoRule") Boolean isCtoRule,
    Boolean publicRule,
    Boolean omniChannelEditable,
    List<String> outcomeCustomFacts,
    RuleDetails details,
    RuleConditions conditions,
    RuleActions actions) {

  /** Organization/tenant context ({@code ContextDTO}). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleContext(String name, String id) {}

  /** Rule metadata and configuration. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleDetails(
      String name,
      String description,
      String choosenStatus,
      String pendingStatus,
      Integer ruleOrder,
      Integer userSamplingPercentage,
      List<EventType> eventTypes,
      List<String> channelIndicators,
      String commentRemark,
      List<RuleComment> comments,
      Boolean publicRule) {}

  /** {@code EventTypeDTO}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EventType(String name) {}

  /** {@code RuleCommentDTO}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleComment(String id, String comment, Long creationDate, String createdBy) {}

  /** The rule's evaluation logic: a wrapper around the list of conditions. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleConditions(List<RuleCondition> conditions) {}

  /** A group of expressions joined by AND ({@code isConjunctive}) or OR. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleCondition(
      String id,
      @JsonProperty("isConjunctive") @JsonAlias("conjunctive") Boolean isConjunctive,
      @JsonProperty("isNegativeRule") @JsonAlias("negativeRule") Boolean isNegativeRule,
      List<RuleExpression> expressions) {}

  /** One comparison: {@code operand operator (valueString | listName)}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleExpression(
      String operand,
      String operator,
      String factType,
      String factCategory,
      @JsonAlias("value") String valueString,
      @JsonProperty("isListExpression") @JsonAlias("listExpression") Boolean isListExpression,
      String listName) {}

  /** What happens when the rule fires. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RuleActions(
      String outcomeAction,
      Boolean lockOutUserAccount,
      Boolean excludeLockedUsers,
      List<AuthMethod> authMethods,
      String templateName,
      List<CaseCreation> caseCreations) {}

  /** {@code UIAuthMethod}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AuthMethod(String name, String displayName) {}

  /** {@code CreateCaseForActionDTO}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CaseCreation(String outcome, String urgencyLevel) {}
}

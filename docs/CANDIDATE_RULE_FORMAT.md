# Candidate Rule Format (v1.0)

How the agent returns a candidate policy rule in the Policy Manager chat contract.

## Where it goes

The rule is carried in `PolicyRecommendation.policy_definition`:

| Field | Value |
|---|---|
| `format` | `"candidate-rule-format"` |
| `schema_version` | `"1.0"` |
| `definition` | One rule object (below), serialized as a JSON string |

`policy_definition` is omitted for `CHANGE_TYPE_NO_CHANGE` and when the agent is asking for
more input. For changes to existing policies, the affected policy IDs go in
`PolicyRecommendation.target_policy_ids`, not in the rule.

## Rule object

```json
{
  "position": 5,
  "context": { "name": "tenant-01", "id": "org-01" },
  "outcomeCustomFacts": ["new_device_risk"],
  "details": { ... },
  "conditions": { "conditions": [ ... ] },
  "actions": { ... }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `position` | Integer | Yes | 1-based position at which the rule is inserted into the policy set |
| `context` | Object | Yes | Tenant the rule belongs to: `name` (required) is the request's tenant, `id` (optional) its organization |
| `outcomeCustomFacts` | Array[String] | No | Custom facts produced when the rule fires |
| `details` | Object | Yes | Rule metadata, see [Details](#details) |
| `conditions` | Object | Yes | Evaluation logic, see [Conditions](#conditions) |
| `actions` | Object | Yes | What happens when the rule fires, see [Actions](#actions) |

Do not set `id`; it identifies existing rules only.

### Details

| Field | Type | Required | Description / valid values |
|---|---|---|---|
| `name` | String | Yes | Rule name, max 80 chars |
| `description` | String | No | Max 500 chars |
| `choosenStatus` | String | Yes | `ACTIVE`, `INACTIVE`, `PRODUCTION`, `WORK_IN_PROGRESS`, `TEST`, `SUSPENDED` |
| `ruleOrder` | Integer | Yes | Execution order, positive integer |
| `userSamplingPercentage` | Integer | No | 0–100 |
| `eventTypes` | Array[Object] | No | Events the rule applies to, e.g. `[{ "name": "TRANSFER" }]` |
| `channelIndicators` | Array[String] | No | `WEB`, `MOBILE`, `API`, `BRANCH`, `ATM`, `IVR` |

Note the spelling `choosenStatus`.

### Conditions

`conditions` wraps a list: `{ "conditions": [ <condition>, ... ] }`, with at least one condition.

**Condition**

| Field | Type | Required | Description |
|---|---|---|---|
| `isConjunctive` | Boolean | Yes | `true`: all expressions must match (AND); `false`: any expression (OR) |
| `isNegativeRule` | Boolean | No | Negates the condition's result |
| `expressions` | Array[Expression] | Yes | At least one |

**Expression**

| Field | Type | Required | Description |
|---|---|---|---|
| `operand` | String | Yes | Fact name, e.g. `transactionAmount`, `sourceCountry` |
| `operator` | String | Yes | See [Operators](#operators) |
| `factType` | String | Yes | `STRING`, `DOUBLE`, `INTEGER`, `BOOLEAN`, `DATE` |
| `factCategory` | String | Yes | `DEFAULT` or a custom category |
| `isListExpression` | Boolean | No | `true` when comparing against a policy list (default `false`) |
| `valueString` | String | If `isListExpression` is `false` | Comparison value as a string, parseable as `factType` |
| `listName` | String | If `isListExpression` is `true` | Name of an existing policy list |

### Operators

| Operator | Fact types |
|---|---|
| `EQUALS`, `NOT_EQUALS` | All |
| `GREATER_THAN`, `GREATER_OR_EQUALS`, `LESS_THAN`, `LESS_OR_EQUALS` | `DOUBLE`, `INTEGER`, `DATE` |
| `IN`, `NOT_IN`, `WITHIN` (synonym for `IN`) | `STRING`, list expressions |
| `CONTAINS`, `NOT_CONTAINS`, `STARTS_WITH`, `ENDS_WITH` | `STRING` |
| `IS_EMPTY`, `IS_NOT_EMPTY` | `STRING`, `DOUBLE`, `INTEGER` |

### Actions

| Field | Type | Required | Description / valid values |
|---|---|---|---|
| `outcomeAction` | String | Yes | `ALLOW`, `CHALLENGE`, `DENY`, `REVIEW`, `REDIRECT_COLLECT`, `INTERVENE`, `NONE` |
| `lockOutUserAccount` | Boolean | No | Lock the user's account |
| `excludeLockedUsers` | Boolean | No | Skip the rule for already-locked users |
| `authMethods` | Array[Object] | With `CHALLENGE` | `[{ "name": "OTP", "displayName": "One-Time Password" }]`; names such as `OTP`, `SECURITY_QUESTIONS`, `BIOMETRIC`, `DEVICE_VERIFICATION` |
| `templateName` | String | No | Communication/intervention template ID |
| `caseCreations` | Array[Object] | No | Cases to create: `outcome` (`FRAUD`, `REVIEW`, `VERIFY`, `SUSPICIOUS`) and `urgencyLevel` (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), both required per entry |

## Example

Rule object:

```json
{
  "position": 5,
  "context": { "name": "tenant-01", "id": "org-01" },
  "outcomeCustomFacts": ["new_device_risk"],
  "details": {
    "name": "Review high-risk transactions on new devices",
    "description": "Challenges high-value transfers from devices first seen in the last 2 days.",
    "choosenStatus": "WORK_IN_PROGRESS",
    "ruleOrder": 5,
    "userSamplingPercentage": 100,
    "eventTypes": [{ "name": "TRANSFER" }, { "name": "WIRE" }],
    "channelIndicators": ["WEB", "MOBILE"]
  },
  "conditions": {
    "conditions": [
      {
        "isConjunctive": true,
        "isNegativeRule": false,
        "expressions": [
          {
            "operand": "riskScore",
            "operator": "GREATER_OR_EQUALS",
            "factType": "INTEGER",
            "factCategory": "DEFAULT",
            "valueString": "700",
            "isListExpression": false
          },
          {
            "operand": "deviceAgeDays",
            "operator": "LESS_THAN",
            "factType": "INTEGER",
            "factCategory": "DEFAULT",
            "valueString": "2",
            "isListExpression": false
          },
          {
            "operand": "destinationCountry",
            "operator": "NOT_IN",
            "factType": "STRING",
            "factCategory": "DEFAULT",
            "isListExpression": true,
            "listName": "user-known-countries"
          }
        ]
      }
    ]
  },
  "actions": {
    "outcomeAction": "CHALLENGE",
    "lockOutUserAccount": false,
    "excludeLockedUsers": true,
    "authMethods": [{ "name": "OTP", "displayName": "One-Time Password" }],
    "templateName": "new-device-challenge",
    "caseCreations": [{ "outcome": "SUSPICIOUS", "urgencyLevel": "HIGH" }]
  }
}
```

As it appears in the payload:

```json
"policy_definition": {
  "format": "candidate-rule-format",
  "schema_version": "1.0",
  "definition": "{\"position\":5,\"context\":{\"name\":\"tenant-01\",\"id\":\"org-01\"},\"details\":{...},\"conditions\":{...},\"actions\":{...}}"
}
```

## Rules

- `definition` is a single JSON object, not an array and not a non-JSON string.
- Omit fields you have no value for; do not send them as `null`.
- Enum values are upper case, exactly as listed.
- Every `listName` must refer to an existing policy list.
- Fields not described here should not be sent.

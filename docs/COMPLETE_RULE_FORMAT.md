# Complete Rule Format Specification (v2.0)

## Overview

This document defines the **COMPLETE** format for rules in the simulation engine, including all attributes from the legacy PolicyManager system. This format is used for:

1. **Candidate Rules** - submitted by orchestrator team for merging during backtest
2. **Policy Data** - provided by dataLayerService to simulation engine
3. **Internal Representation** - how rules are stored and executed

**Version 2.0** - Based on complete legacy structure with all 50+ attributes across rule hierarchy.

---

## Table of Contents

1. [Rule Root Level](#rule-root-level)
2. [Rule Details Section](#rule-details-section)
3. [Conditions Section](#conditions-section)
4. [Actions Section](#actions-section)
5. [Supporting Objects](#supporting-objects)
6. [Complete Examples](#complete-examples)
7. [CSV to JSON Mapping](#csv-to-json-mapping)

---

## Rule Root Level

Complete structure of a rule with all metadata:

```json
{
  "id": "rule-uuid-f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "position": 5,
  "creationDate": 1697500000000,
  "createdBy": "john.doe@bank.com",
  "modificationDate": 1702900000000,
  "modifiedBy": "jane.smith@bank.com",
  "deletedDate": null,
  "context": {
    "name": "esab",
    "id": "esab-org"
  },
  "contextName": "esab",
  "originalRule": null,
  "isCtoRule": false,
  "publicRule": false,
  "omniChannelEditable": true,
  "outcomeCustomFacts": ["high_velocity_detected", "new_account_risk"],
  "details": { ... },
  "conditions": { ... },
  "actions": { ... }
}
```

### Root Level Fields

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `id` | String (UUID) | For existing rules only | Unique rule identifier | `rule-uuid-f47ac10b-58cc-4372-a567-0e02b2c3d479` |
| `position` | Integer | For candidate rules only | 1-based insertion position | `5` |
| `creationDate` | Long (epoch ms) | No | Unix timestamp when created | `1697500000000` |
| `createdBy` | String | No | User/system that created rule | `john.doe@bank.com` |
| `modificationDate` | Long (epoch ms) | No | Last modification timestamp | `1702900000000` |
| `modifiedBy` | String | No | User who last modified | `jane.smith@bank.com` |
| `deletedDate` | Long (epoch ms) | No | Soft delete timestamp (null if active) | `null` or epoch ms |
| `context` | ContextDTO | Yes | Organization/tenant context | See ContextDTO section |
| `contextName` | String | No | Context name (for sorting only) | `esab` |
| `originalRule` | Rule | No | Reference to original rule (for versioning) | Nested rule object or null |
| `isCtoRule` | Boolean | No | Customer-tunable-outcome flag | `false` |
| `publicRule` | Boolean | No | Public (shared) vs private rule | `false` |
| `omniChannelEditable` | Boolean | No | Whether rule is editable across all channels | `true` |
| `outcomeCustomFacts` | Array[String] | No | Custom facts produced by rule outcome | `["high_velocity", "new_account"]` |
| `details` | RuleDetails | Yes | Rule metadata and configuration | See Details section |
| `conditions` | RuleConditions | Yes | Rule evaluation conditions | See Conditions section |
| `actions` | RuleActions | Yes | Actions when rule fires | See Actions section |

### ContextDTO

```json
{
  "name": "esab",
  "id": "esab-org"
}
```

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `name` | String | Yes | Organization/tenant name | `esab`, `aviram`, `bendigo` |
| `id` | String | No | Organization/tenant ID | `esab-org`, `org-123` |

---

## Rule Details Section

Complete metadata about the rule:

```json
{
  "name": "Block High-Risk Countries",
  "description": "Deny all transactions from sanctioned countries per regulatory requirement",
  "choosenStatus": "ACTIVE",
  "pendingStatus": "PENDING_ACTIVE",
  "ruleOrder": 1,
  "userSamplingPercentage": 100,
  "eventTypes": [
    {
      "name": "TRANSFER"
    },
    {
      "name": "WIRE"
    }
  ],
  "channelIndicators": ["WEB", "MOBILE", "API"],
  "commentRemark": "Compliance requirement - updated for Q4 2024",
  "comments": [
    {
      "id": "comment-uuid-1",
      "comment": "Updated to include new sanctions list",
      "creationDate": 1702900000000,
      "createdBy": "compliance@bank.com"
    }
  ],
  "publicRule": false
}
```

### Details Fields

| Field | Type | Required | Description | Valid Values | Example |
|-------|------|----------|-------------|---------------|---------|
| `name` | String | Yes | Rule name | Max 80 chars, non-empty | `Block High-Risk Countries` |
| `description` | String | No | Detailed description | Max 500 chars | `Deny all transactions from...` |
| `choosenStatus` | String | Yes | Active/execution status | `ACTIVE`, `INACTIVE`, `PRODUCTION`, `WORK_IN_PROGRESS`, `TEST`, `SUSPENDED` | `ACTIVE` |
| `pendingStatus` | String | No | Pending approval status | `PENDING_ACTIVE`, `PENDING_INACTIVE`, `PENDING`, null | `PENDING_ACTIVE` |
| `ruleOrder` | Integer | Yes | Execution order | Positive integer, 1 to MAX_ORDER | `1` |
| `userSamplingPercentage` | Integer | No | Percentage of users | 0-100 | `100` |
| `eventTypes` | Array[EventType] | No | Events rule applies to | See EventTypeDTO | `[{name: "TRANSFER"}]` |
| `channelIndicators` | Array[String] | No | Channels rule applies to | `WEB`, `MOBILE`, `API`, `BRANCH`, `ATM`, `IVR` | `["WEB", "MOBILE"]` |
| `commentRemark` | String | No | Last/main reviewer comment | Max 160 chars | `Updated for Q4 2024` |
| `comments` | Array[Comment] | No | All revision comments | See RuleCommentDTO | Array of comments |
| `publicRule` | Boolean | No | Public/shared flag | `true`, `false` | `false` |

### EventTypeDTO

```json
{
  "name": "TRANSFER"
}
```

| Field | Type | Description | Examples |
|-------|------|-------------|----------|
| `name` | String | Event type name | `TRANSFER`, `LOGIN`, `WIRE`, `SESSION_SIGNIN`, `PAYMENT`, `WITHDRAWAL` |

### RuleCommentDTO

```json
{
  "id": "comment-uuid-1",
  "comment": "Updated to include new sanctions list",
  "creationDate": 1702900000000,
  "createdBy": "compliance@bank.com"
}
```

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `id` | String (UUID) | For existing comments | Unique comment ID | `comment-uuid-1` |
| `comment` | String | Yes | The comment text | `Updated to include new...` |
| `creationDate` | Long (epoch ms) | No | When comment was added | `1702900000000` |
| `createdBy` | String | No | Who added the comment | `jane.smith@bank.com` |

---

## Conditions Section

Complete condition evaluation logic:

```json
{
  "conditions": [
    {
      "id": "condition-1",
      "isConjunctive": true,
      "isNegativeRule": false,
      "expressions": [
        {
          "operand": "sourceCountry",
          "operator": "IN",
          "factType": "STRING",
          "factCategory": "DEFAULT",
          "valueString": null,
          "isListExpression": true,
          "listName": "sanctioned-countries"
        },
        {
          "operand": "transactionAmount",
          "operator": "GREATER_THAN",
          "factType": "DOUBLE",
          "factCategory": "DEFAULT",
          "valueString": "10000",
          "isListExpression": false,
          "listName": null
        }
      ]
    },
    {
      "id": "condition-2",
      "isConjunctive": false,
      "isNegativeRule": false,
      "expressions": [
        {
          "operand": "deviceIP",
          "operator": "EQUALS",
          "factType": "STRING",
          "factCategory": "DEFAULT",
          "valueString": "178.171.89.129",
          "isListExpression": false,
          "listName": null
        }
      ]
    }
  ]
}
```

### Condition Fields

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `id` | String (UUID) | For existing conditions | Unique condition ID | `condition-1` |
| `isConjunctive` | Boolean | Yes | `true` = AND logic, `false` = OR logic | `true` |
| `isNegativeRule` | Boolean | No | Negate overall condition result | `false` |
| `expressions` | Array[Expression] | Yes | Individual comparisons (min 1) | See Expression section |

### Expression Fields

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `operand` | String | Yes | Fact name from fact model | `sourceCountry`, `transactionAmount`, `deviceIP` |
| `operator` | String | Yes | Comparison operator | See Operators table |
| `factType` | String | Yes | Declared fact type | `STRING`, `DOUBLE`, `INTEGER`, `BOOLEAN`, `DATE` |
| `factCategory` | String | Yes | Fact category/namespace | `DEFAULT`, custom category |
| `valueString` | String | Conditional | Literal comparison value. Required if `isListExpression=false` | `10000`, `178.171.89.129` |
| `isListExpression` | Boolean | No | `true` if comparing against policy list | `false` |
| `listName` | String | Conditional | Policy list name. Required if `isListExpression=true` | `sanctioned-countries` |

### Supported Operators

| Operator | Fact Types | Description | Example |
|----------|-----------|-------------|---------|
| `EQUALS` | All | Exact match | country EQUALS "US" |
| `NOT_EQUALS` | All | Not equal | status NOT_EQUALS "ACTIVE" |
| `GREATER_THAN` | DOUBLE, INTEGER, DATE | Greater than | amount > 10000 |
| `GREATER_OR_EQUALS` | DOUBLE, INTEGER, DATE | Greater or equal | amount >= 5000 |
| `LESS_THAN` | DOUBLE, INTEGER, DATE | Less than | days < 30 |
| `LESS_OR_EQUALS` | DOUBLE, INTEGER, DATE | Less or equal | days <= 30 |
| `IN` | STRING, list types | Member of list | country IN high_risk |
| `NOT_IN` | STRING, list types | Not member of list | country NOT_IN whitelist |
| `WITHIN` | STRING, list types | Within list (synonym for IN) | country WITHIN list |
| `CONTAINS` | STRING | Contains substring | description CONTAINS "malware" |
| `NOT_CONTAINS` | STRING | Not contains | description NOT_CONTAINS "verified" |
| `STARTS_WITH` | STRING | Starts with | email STARTS_WITH "test_" |
| `ENDS_WITH` | STRING | Ends with | domain ENDS_WITH ".onion" |
| `IS_EMPTY` | STRING, DOUBLE, INTEGER | Is empty/null | field IS_EMPTY |
| `IS_NOT_EMPTY` | STRING, DOUBLE, INTEGER | Is not empty/null | field IS_NOT_EMPTY |

---

## Actions Section

Complete action configuration:

```json
{
  "outcomeAction": "CHALLENGE",
  "lockOutUserAccount": false,
  "excludeLockedUsers": true,
  "authMethods": [
    {
      "name": "OTP",
      "displayName": "One-Time Password"
    },
    {
      "name": "SECURITY_QUESTIONS",
      "displayName": "Security Questions"
    }
  ],
  "templateName": "high-velocity-challenge",
  "caseCreations": [
    {
      "outcome": "FRAUD",
      "urgencyLevel": "HIGH"
    },
    {
      "outcome": "REVIEW",
      "urgencyLevel": "MEDIUM"
    }
  ]
}
```

### Action Fields

| Field | Type | Required | Description | Valid Values | Example |
|-------|------|----------|-------------|---------------|---------|
| `outcomeAction` | String | Yes | Primary action type | `ALLOW`, `CHALLENGE`, `DENY`, `REVIEW`, `REDIRECT_COLLECT`, `INTERVENE`, `NONE` | `CHALLENGE` |
| `lockOutUserAccount` | Boolean | No | Lock user account | `true`, `false` | `false` |
| `excludeLockedUsers` | Boolean | No | Skip rule if user already locked | `true`, `false` | `true` |
| `authMethods` | Array[AuthMethod] | No | Auth methods for CHALLENGE action | See UIAuthMethod | See below |
| `templateName` | String | No | Communication/intervention template | Template ID | `high-velocity-challenge` |
| `caseCreations` | Array[CaseCreation] | No | Cases to create when rule fires | See CaseCreation | Array of cases |

### UIAuthMethod

```json
{
  "name": "OTP",
  "displayName": "One-Time Password"
}
```

| Field | Type | Description | Examples |
|-------|------|-------------|----------|
| `name` | String | Technical auth method name | `OTP`, `SECURITY_QUESTIONS`, `BIOMETRIC`, `DEVICE_VERIFICATION` |
| `displayName` | String | Human-readable name | `One-Time Password` |

### CaseCreation (CreateCaseForActionDTO)

```json
{
  "outcome": "FRAUD",
  "urgencyLevel": "HIGH"
}
```

| Field | Type | Required | Description | Valid Values | Example |
|-------|------|----------|-------------|---------------|---------|
| `outcome` | String | Yes | Case outcome type | `FRAUD`, `REVIEW`, `VERIFY`, `SUSPICIOUS`, ... | `FRAUD` |
| `urgencyLevel` | String | Yes | Case priority | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` | `HIGH` |

### Outcome Action Descriptions

| Action | Description | When Used |
|--------|-------------|-----------|
| `ALLOW` | Permit transaction | Low-risk, normal behavior |
| `CHALLENGE` | Require additional authentication | Medium-risk, verify identity |
| `DENY` | Reject transaction | High-risk, block immediately |
| `REVIEW` | Flag for manual review | Medium-high risk, human decision |
| `REDIRECT_COLLECT` | Redirect to info collection | Suspicious but recoverable |
| `INTERVENE` | Send communication/notice | Proactive user alert |
| `NONE` | No action (log only) | Informational tracking |

---

## Supporting Objects

### PolicyList (Referenced by expressions)

```json
{
  "name": "sanctioned-countries",
  "isPublicList": true,
  "values": ["KP", "IR", "SY", "CU", "CC"]
}
```

| Field | Type | Required | Description | Example |
|-------|------|----------|-------------|---------|
| `name` | String | Yes | Unique list identifier | `sanctioned-countries` |
| `isPublicList` | Boolean | Yes | Public (shared) vs private | `true` |
| `values` | Array[String] | Yes | List elements | `["KP", "IR", "SY"]` |

---

## Complete Examples

### Example 1: Simple Country Block Rule

```json
{
  "id": "rule-block-sanctions",
  "creationDate": 1697500000000,
  "createdBy": "compliance@bank.com",
  "modificationDate": 1702900000000,
  "modifiedBy": "admin@bank.com",
  "context": { "name": "esab", "id": "esab-org" },
  "contextName": "esab",
  "publicRule": false,
  "omniChannelEditable": true,
  "details": {
    "name": "Block Sanctioned Countries",
    "description": "Deny transactions from OFAC sanctioned countries",
    "choosenStatus": "ACTIVE",
    "ruleOrder": 1,
    "userSamplingPercentage": 100,
    "eventTypes": [{ "name": "TRANSFER" }],
    "channelIndicators": ["WEB", "MOBILE"],
    "commentRemark": "OFAC compliance requirement"
  },
  "conditions": {
    "conditions": [
      {
        "id": "cond-1",
        "isConjunctive": true,
        "isNegativeRule": false,
        "expressions": [
          {
            "operand": "sourceCountry",
            "operator": "IN",
            "factType": "STRING",
            "factCategory": "DEFAULT",
            "isListExpression": true,
            "listName": "sanctioned-countries"
          }
        ]
      }
    ]
  },
  "actions": {
    "outcomeAction": "DENY",
    "lockOutUserAccount": false,
    "excludeLockedUsers": false,
    "templateName": "transaction-blocked-sanctions"
  }
}
```

### Example 2: Complex High-Velocity Rule with Multiple Conditions

```json
{
  "id": "rule-high-velocity",
  "position": 5,
  "creationDate": 1697500000000,
  "createdBy": "ai-orchestrator@bank.com",
  "modificationDate": 1702900000000,
  "modifiedBy": "reviewer@bank.com",
  "context": { "name": "esab", "id": "esab-org" },
  "isCtoRule": false,
  "publicRule": false,
  "outcomeCustomFacts": ["high_velocity_detected", "new_account_risk"],
  "details": {
    "name": "High-Velocity International Transfers",
    "description": "Flags multiple international transfers from new accounts within short timeframe",
    "choosenStatus": "ACTIVE",
    "pendingStatus": "PENDING_ACTIVE",
    "ruleOrder": 5,
    "userSamplingPercentage": 100,
    "eventTypes": [
      { "name": "TRANSFER" },
      { "name": "WIRE" }
    ],
    "channelIndicators": ["WEB", "MOBILE"],
    "commentRemark": "AI-generated rule to catch emerging ATO patterns",
    "comments": [
      {
        "id": "comment-1",
        "comment": "Approved after testing on sample dataset",
        "creationDate": 1702900000000,
        "createdBy": "reviewer@bank.com"
      }
    ],
    "publicRule": false
  },
  "conditions": {
    "conditions": [
      {
        "id": "cond-1",
        "isConjunctive": true,
        "isNegativeRule": false,
        "expressions": [
          {
            "operand": "transferCountIn1Hour",
            "operator": "GREATER_THAN",
            "factType": "INTEGER",
            "factCategory": "DEFAULT",
            "valueString": "5",
            "isListExpression": false
          },
          {
            "operand": "totalAmountIn1Hour",
            "operator": "GREATER_THAN",
            "factType": "DOUBLE",
            "factCategory": "DEFAULT",
            "valueString": "50000",
            "isListExpression": false
          },
          {
            "operand": "daysSinceAccountCreation",
            "operator": "LESS_THAN",
            "factType": "INTEGER",
            "factCategory": "DEFAULT",
            "valueString": "90",
            "isListExpression": false
          }
        ]
      },
      {
        "id": "cond-2",
        "isConjunctive": false,
        "isNegativeRule": false,
        "expressions": [
          {
            "operand": "destinationCountry",
            "operator": "NOT_IN",
            "factType": "STRING",
            "factCategory": "DEFAULT",
            "isListExpression": true,
            "listName": "user-known-countries"
          },
          {
            "operand": "destinationCountry",
            "operator": "IN",
            "factType": "STRING",
            "factCategory": "DEFAULT",
            "isListExpression": true,
            "listName": "high-risk-countries"
          }
        ]
      }
    ]
  },
  "actions": {
    "outcomeAction": "CHALLENGE",
    "lockOutUserAccount": false,
    "excludeLockedUsers": true,
    "authMethods": [
      { "name": "OTP", "displayName": "One-Time Password" },
      { "name": "SECURITY_QUESTIONS", "displayName": "Security Questions" }
    ],
    "templateName": "intl-transfer-challenge",
    "caseCreations": [
      {
        "outcome": "SUSPICIOUS",
        "urgencyLevel": "HIGH"
      }
    ]
  }
}
```

---

## CSV to JSON Mapping

### CSV Column to JSON Field Mapping

| CSV Column | JSON Path | Type | Notes |
|------------|-----------|------|-------|
| Order ID | details.ruleOrder | Integer | 1-based position |
| Rule Name | details.name | String | Max 80 chars |
| Org Name | context.name | String | Organization identifier |
| Channel | details.channelIndicators | Array[String] | JSON array format |
| Event Type | details.eventTypes | Array[EventType] | JSON array format |
| Current Status | details.choosenStatus | String | Active/execution status |
| Pending Status | details.pendingStatus | String | Approval status |
| Action | actions.outcomeAction | String | Action type enum |
| Sampling % | details.userSamplingPercentage | Integer | 0-100 |
| Exclude Locked Users | actions.excludeLockedUsers | Boolean | true/false |
| Description | details.description | String | Max 500 chars |
| Conditions | conditions.conditions[] | Array[Condition] | JSON array of conditions |
| Modified Date | modificationDate | Long | Epoch milliseconds |
| Modified By | modifiedBy | String | User identifier |
| Created Date | creationDate | Long | Epoch milliseconds |
| Created By | createdBy | String | User identifier |
| Editable | omniChannelEditable | Boolean | true/false |

### Example CSV Row to JSON:

**CSV:**
```csv
25,bloquear CODT p4 app,esab,"[""MOBILE""]","[""REQUEST_NEW_PIN-PART_APP_VIEW_CARD_INFO""]",TEST,,DENY,100,FALSE,bloquear operativa,"[{""conjunctive"":true,""operand"":""EMAIL_DOMAIN"",""operator"":""EQUALS"",""value"":""test@example.com""}]",2026-01-14 11:15:14.53,ns_test_esab_1,2024-12-11 15:30:07.84,T334569,N
```

**Converts to JSON:**
```json
{
  "id": "rule-auto-uuid",
  "creationDate": 1733965807840,
  "createdBy": "T334569",
  "modificationDate": 1736854514530,
  "modifiedBy": "ns_test_esab_1",
  "context": { "name": "esab" },
  "contextName": "esab",
  "omniChannelEditable": false,
  "details": {
    "name": "bloquear CODT p4 app",
    "description": "bloquear operativa",
    "choosenStatus": "TEST",
    "ruleOrder": 25,
    "userSamplingPercentage": 100,
    "eventTypes": [{ "name": "REQUEST_NEW_PIN-PART_APP_VIEW_CARD_INFO" }],
    "channelIndicators": ["MOBILE"]
  },
  "conditions": {
    "conditions": [
      {
        "isConjunctive": true,
        "expressions": [
          {
            "operand": "EMAIL_DOMAIN",
            "operator": "EQUALS",
            "factType": "STRING",
            "factCategory": "DEFAULT",
            "valueString": "test@example.com",
            "isListExpression": false
          }
        ]
      }
    ]
  },
  "actions": {
    "outcomeAction": "DENY",
    "lockOutUserAccount": false,
    "excludeLockedUsers": false
  }
}
```

---

## Validation Rules

### Required Field Validation

- ✓ `details.name` - Must be non-empty, max 80 chars
- ✓ `details.choosenStatus` - Must be valid status enum
- ✓ `details.ruleOrder` - Must be positive integer
- ✓ `conditions.conditions[]` - Must have at least 1 condition
- ✓ `conditions.conditions[].expressions[]` - Must have at least 1 expression
- ✓ `actions.outcomeAction` - Must be valid action enum
- ✓ `details.userSamplingPercentage` - Must be 0-100

### Conditional Validation

- If `isListExpression=true` → `listName` must be provided and must exist in policy lists
- If `isListExpression=false` → `valueString` must be provided and parseable as `factType`
- If `outcomeAction=CHALLENGE` → `authMethods` should be provided
- If `actions.caseCreations` provided → each case must have valid `outcome` and `urgencyLevel`

---

## Notes & Best Practices

### For Orchestrator Team (Submitting Candidate Rules)

✅ **Include:**
- All details (name, description, choosenStatus required)
- All conditions with complete expressions
- All actions with appropriate outcome type
- Comments if rule is updated/modified
- outcomeCustomFacts if rule produces custom facts
- Metadata (createdBy, creationDate, etc.)

❌ **Avoid:**
- Referencing non-existent policy lists (will fail validation)
- Incomplete expressions (missing operand or operator)
- Invalid operators for fact types
- Rules with no conditions
- Sampling % outside 0-100 range

### For DataLayerService Team (Providing Policy Data)

✅ **Include:**
- Complete rule hierarchy with all metadata
- All referenced policy lists
- Audit trail (creationDate, createdBy, etc.)
- Comments for historical context
- Case creation details for complex actions

❌ **Avoid:**
- Duplicate rule IDs
- Orphaned policy lists (not referenced by any rule)
- Incomplete rule definitions
- Inconsistent timestamps
- Invalid status values

---

## Backward Compatibility

This format is **fully compatible** with:
- Legacy PolicyManager RuleDetailedDTO
- Existing Drools rule engine
- Proto buffer definitions
- CSV export format from PolicyManager

All existing policies should convert to/from this format without data loss.


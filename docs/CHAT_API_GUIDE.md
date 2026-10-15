# Chat API Guide — How a Message Flows Through This Backend

A plain-English walkthrough of the one API this service exposes: what the frontend
sends us, what we forward to the ML Agent, and what the ML Agent streams back, which
this backend passes on to the frontend unchanged. No code required to read this — just JSON examples.

For the "why" behind design decisions, see [`ARCHITECTURE.md`](ARCHITECTURE.md). This
doc is the "what" — a reference you'd hand to a frontend developer or a new teammate.

## 1. The big picture

```text
┌──────────┐   1. one HTTP POST    ┌─────────────┐   2. one gRPC call   ┌───────────┐
│          │  ───────────────────▶ │             │ ───────────────────▶│           │
│ Frontend │                        │ This Backend│                      │ ML Agent  │
│ (chat UI)│  ◀─────────────────── │ (this repo) │ ◀───────────────────│ (Thoughtful│
│          │   4. SSE stream back   │             │   3. gRPC stream back│  Labs)    │
└──────────┘                        └─────────────┘                      └───────────┘
```

This backend never stores anything. It takes one message in, forwards it, and streams
the ML Agent's answer straight back out, event for event, in the ML Agent's own format.
It's a pass-through proxy, not a translator, a database, or a session manager. Every request is independent; **the only thing
"remembered" between requests is whatever the frontend chooses to resend as `history`**
(see §5, "continuing a conversation") — there is no token or id to save and echo back.

## 2. Key terms (read this before the rest)

| Term | What it means |
|---|---|
| `tenantId` | Which customer this request belongs to. Sent as the `X-Tenant-Id` request header, not a body field. |
| `organization` | Which organization within that tenant this request belongs to. Sent as the optional `X-Org-Id` request header. |
| `history` | The full conversation transcript so far, oldest turn first. This is the **only** way to continue a conversation — the ML Agent's contract has no session/continuation token at all. Resend the growing transcript on every follow-up message. |
| `messageId` | A unique ID this backend generates per message, for tracing in logs. Not something the frontend sends; only appears in a `service_error` event (§6). |
| `requestId` | Optional, frontend-generated. Purely for your own tracing/support tickets — this backend logs it but doesn't use it for anything else. |
| `policyContext` | Optional. What the question is about: the baseline policy set, the historical scenario, and optionally a seed case, a candidate recommendation, or a simulation run being discussed (§3). |
| `operatorId` | Optional. The id of the authenticated operator making the request — forwarded as-is to the ML Agent as `operator_id`. The PM contract expects the authenticated operator here, so send the logged-in user's operator id. |

A Policy Manager conversation asks the agent to recommend new or improved fraud
policies, backtest them against a baseline, and explain the comparison. Every answer
streams as text plus one structured `payload` with a `status`
(e.g. `STATUS_CANDIDATE_READY`, `STATUS_EVALUATED`) — see §6.

## 3. Step 1 — What the frontend sends to this backend

**One endpoint, one method:**

```
POST /api/v1/chat/messages
Content-Type: application/json
Accept: text/event-stream
```

**Required headers:**

| Header | Purpose |
|---|---|
| `X-Tenant-Id` | Which customer this request belongs to. Missing or blank → `400 VALIDATION_ERROR`, request never reaches the ML Agent. |

**Optional headers:**

| Header | Purpose |
|---|---|
| `X-Org-Id` | Which organization within that tenant. Forwarded to the ML Agent only if sent; missing or blank means no organization is forwarded. |
| `X-User-Id` | The analyst's identity in local dev mode (`chatbot.security.mode: NONE`, a stand-in until real login is wired up) — used for logging only, never forwarded as `operator_id` (send `operatorId` in the body for that). In `BFF_SESSION` mode it's ignored; the session's `username` is used instead. |
| `X-Correlation-Id` | Your own tracing ID — if you don't send one, this backend generates one and echoes it back on the response header. |

**Request body:**

```json
{
  "history": [],
  "requestId": "req-a1b2c3",
  "operatorId": "analyst-1",
  "policyContext": {
    "policySetId": "baseline-01",
    "policySetVersion": "7",
    "scenarioId": "scenario-01",
    "caseId": null,
    "candidateId": null,
    "candidateVersion": null,
    "simulationId": null,
    "missedFraudIndicatorIds": ["indicator-01"],
    "historicalSummary": "Card-not-present fraud on new devices rose 18% over the last 30 days."
  },
  "message": "Recommend a policy for missed fraud and compare it with our current policies."
}
```

| Field | Required? | Notes |
|---|---|---|
| `history` | No | Omit (or send an empty array) to start a brand-new conversation. Otherwise, resend the full transcript so far — see §7. Each turn is `{ "role": "user"|"assistant", "content": "..." }`; at most 50 turns. `content` is optional and may be omitted, `null`, empty, or blank. |
| `requestId` | No | Your own tracking ID, for your logs only. |
| `operatorId` | No | The authenticated operator's id, forwarded as-is as `operator_id` only when sent; blank counts as not sent. Max 200 chars. Never filled in by this backend. |
| `policyContext` | No | Every field inside is optional; blank strings count as "not sent". `policySetId` (baseline policy set), `policySetVersion` (exact baseline version), `scenarioId` (historical scenario for analysis/backtesting), `caseId` (a case that prompted the investigation — a seed only, it doesn't define the evaluation population), `candidateId` + `candidateVersion` (an existing recommendation being discussed/refined; `candidateVersion` is a **string**, e.g. `"1"`, passed through unchecked), `simulationId` (a run whose results are being discussed), `missedFraudIndicatorIds` (array of missed-fraud indicator ids the analysis starts from; at most 100, each non-blank, max 200 chars), `historicalSummary` (summary of the relevant history; max 8000 chars). Ids max 200 chars (`policySetVersion`/`candidateVersion` max 100). If the agent can't resolve an unambiguous baseline/scenario it answers with status `STATUS_NEEDS_INPUT` and follow-up questions. |
| `message` | **Yes** | The user's question — e.g. a request to recommend or refine a policy and compare it with the baseline. Max 4000 characters. |

Unknown body fields are ignored,
not rejected — they are simply never forwarded.

**That's it — this is the entire contract the frontend needs to know.** §4 happens
inside this backend and is invisible to the frontend. §5–§6 describe what you receive,
which is the ML Agent's own event format.

## 4. Step 2 — What this backend forwards to the ML Agent

This backend re-shapes your request into the ML Agent's own contract before
forwarding it, over gRPC (not another HTTP call) — shown here as JSON since that's the
simplest way to read it. You never see this directly, but it's useful to know the
mapping if something looks wrong end-to-end:

```json
{
  "requestContext": {
    "tenant": "tenant-42",
    "organization": "org-7",
    "agentSessionId": "req-a1b2c3",
    "requestId": "<this backend's own correlation id>"
  },
  "operatorId": "analyst-1",
  "prompt": "Recommend a policy for missed fraud and compare it with our current policies.",
  "history": [],
  "policyContext": {
    "policySetId": "baseline-01",
    "policySetVersion": "7",
    "scenarioId": "scenario-01"
  }
}
```

| Your field | Becomes | Notes |
|---|---|---|
| `X-Tenant-Id` (header) | `requestContext.tenant` | Passed straight through. |
| `X-Org-Id` (header) | `requestContext.organization` | Only if you send it — left unset otherwise. Passed straight through — not looked up or validated against anything server-side. |
| `requestId` | `requestContext.agentSessionId` | Reused as the closest thing this backend has to a request-grouping id; blank if you didn't send one. |
| — | `requestContext.requestId` | This backend's own internal correlation id (from `X-Correlation-Id` or generated) — **not** your `requestId` field, despite the similar name. |
| `operatorId` | `operatorId` | Passed straight through, only if you send it — left unset otherwise. Never defaulted, never filled from `X-User-Id` or the BFF session. |
| `message` | `prompt` | Untouched. |
| `policyContext` | `policyContext` | Each field you sent is copied as-is; fields you didn't send stay unset (no defaults). Omit `policyContext` entirely and it's left unset on the wire. |
| `history` | `history` | Each `{role, content}` turn becomes a `user`/`agent` turn in the ML Agent's own shape — `role: "user"` maps to a user turn, anything else to an agent turn. |

Fields that **never** leave this backend: `X-Correlation-Id` (goes out as
`requestContext.requestId`, not literally the header value's name) and `messageId`. Those
exist purely for this backend's own logging/tracing — `correlationId`/`tenantId` are
already enough to trace one chatbot interaction end to end.

(The request message on the wire is `AskPolicyManagerRequest`, sent to the
`ChatAgent.AskPolicyManager` RPC — see `src/main/proto/policy_manager/v1/policy_manager.proto`.)

## 5. Step 3 — What the ML Agent streams back

The ML Agent responds with a live stream of `AnswerEvent` messages over gRPC
(`src/main/proto/orchestrator/v1/chat_agent.proto`). Each one has exactly one of seven event types set:

| Event | Meaning | How many? |
|---|---|---|
| `chunk` | One small piece of the answer text, arriving live as it's generated | Many |
| `tool_call` | The agent invoked a tool (a DB query, a rules check, etc.) | 0 or more, any point in the stream |
| `tool_result` | The result of that tool invocation (`tool_call_id` matches its `tool_call`) | One per `tool_call` |
| `ping` | A pure keepalive, no content | 0 or more |
| `payload` | The structured Policy Manager result for the UI to render: status, recommendation, simulation summary, citations, follow-up questions, limitations | Exactly one on a successful turn, before `done`; may arrive at any point |
| `done` | "I'm finished" — terminal, the answer is valid | One, last, on success |
| `error` | A model-level failure — terminal, no usable answer | Instead of `done` |

The ML Agent's own contract rules (from the `.proto` comments) apply as-is, because
this backend passes them straight through: consecutive `chunk` events form one text
block, any other event closes it; clients must treat an unrecognised `tool_result.status`
as `STATUS_FAILED` and an unrecognised `error.code` as `ERROR_CODE_INTERNAL`; clients
must ignore event types they don't recognise; after an `error`, discard the chunks
(and any `payload`) already received and don't persist the turn.

The Policy Manager contract adds: the natural-language answer streams through `chunk`;
the structured recommendation arrives through `payload`; a successful turn emits one
`payload`, followed by `done`; `error` terminates the stream without `done`. **`done`
means the chat turn finished — not that the policy passed backtesting.** A simulation
can still be pending (`STATUS_SIMULATION_PENDING`).

## 6. Step 4 — What this backend streams back to the frontend

**Exactly what the ML Agent sent, event for event.** This backend doesn't rename
events or fields, reshape payloads, drop fields, or add its own wrapper. Each
`AnswerEvent` becomes one SSE event:

| SSE line | Value |
|---|---|
| `event:` | The ML Agent's own event type name — `chunk`, `tool_call`, `tool_result`, `payload`, `done`, `error`, `ping` |
| `data:` | The whole `AnswerEvent`, as standard protobuf JSON ([proto3 JSON mapping](https://protobuf.dev/programming-guides/json/)) with the **original `.proto` field names** (`tool_call_id`, `args_json`, `stop_reason`, …) and the original nesting |
| `id:` | A frame counter (0, 1, 2, …) — the only thing this backend adds, and only as SSE transport metadata |

What that JSON looks like for each event type:

| `event:` | `data:` |
|---|---|
| `chunk` | `{"chunk":{"delta":"The proposed refinement is ready for simulation."}}` |
| `tool_call` | `{"tool_call":{"tool_call_id":"t-1","name":"AgenticGetPolicyContext","args_json":"{\"policySetId\":\"baseline-01\",\"scenarioId\":\"scenario-01\"}"}}` |
| `tool_result` | `{"tool_result":{"tool_call_id":"t-1","status":"STATUS_OK","ms":"42","row_count":"3"}}` |
| `payload` | `{"payload":{"policy_manager_answer_payload":{"status":"STATUS_CANDIDATE_READY","recommendation":{"candidate_id":"candidate-01","candidate_version":"1","change_type":"CHANGE_TYPE_REFINE","target_policy_ids":["policy-17"],"title":"Review high-risk transactions on new devices","description":"Add a supported device signal to the existing condition.","rationale":"Historical discovery evidence indicates a gap.","policy_definition":{"format":"candidate-rule-format","schema_version":"1.0","definition":"{\"position\":5,\"context\":{\"name\":\"tenant-42\",\"id\":\"org-7\"},\"details\":{\"name\":\"Review high-risk transactions on new devices\",\"choosenStatus\":\"WORK_IN_PROGRESS\",\"ruleOrder\":5},\"conditions\":{\"conditions\":[{\"isConjunctive\":true,\"expressions\":[{\"operand\":\"deviceAgeDays\",\"operator\":\"LESS_THAN\",\"factType\":\"INTEGER\",\"factCategory\":\"DEFAULT\",\"valueString\":\"2\",\"isListExpression\":false}]}]},\"actions\":{\"outcomeAction\":\"CHALLENGE\",\"authMethods\":[{\"name\":\"OTP\",\"displayName\":\"One-Time Password\"}]}}"},"citation_ids":["evidence-01"],"assumptions":[],"confidence":0.72},"citations":[{"citation_id":"evidence-01","source_tool":"AgenticGetPolicyContext","source_record_id":"segment-01","snapshot_id":"discovery-01","field_paths":["missedFraudCount"]}],"follow_up_questions":[],"limitations":[]}}}` |
| `done` | `{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"1800","tokens_in":"12","tokens_out":"140"}}` |
| `error` | `{"error":{"code":"ERROR_CODE_MODEL_REFUSED","retryable":false}}` |
| `ping` | `{"ping":{}}` |

Three things about that JSON format that frontend code needs to handle:

- **`int64` numbers come through as JSON strings** — `"ms":"42"`, `"row_count":"7"`,
  `"latency_ms":"1800"`, `"tokens_in"`, `"tokens_out"`, and the confusion-matrix counts
  (`"true_positives":"80"`, …). That's the standard proto3 JSON
  rule, since JavaScript numbers can't hold every `int64`. Use `Number(...)` to convert.
- **Enums are their full proto names** — `STATUS_OK`/`STATUS_FAILED`/`STATUS_UNSPECIFIED`,
  `STOP_REASON_COMPLETED`/`STOP_REASON_TRUNCATED`, `ERROR_CODE_MODEL_REFUSED`/
  `ERROR_CODE_DATA_UNAVAILABLE`/`ERROR_CODE_INTERNAL`, `STATUS_CANDIDATE_READY`,
  `CHANGE_TYPE_REFINE`, and so on.
- **Fields at their default value are still included** (`"ms":"0"`, `"retryable":false`,
  `"tool_call_id":""`), so no field is ever silently missing. The exception is
  `row_count`: it belongs to a `oneof` (`result_summary`), so it only appears when the
  agent actually set it. A `tool_result` without `row_count` means the agent didn't
  report one. Likewise, fields with explicit presence are **omitted** when unset, never
  zero-filled: `recommendation`, `simulation`, `policy_definition`, `baseline_metrics`/
  `candidate_metrics`, and the metric rates `precision`/`recall`/`false_positive_rate`
  (absent when undefined, e.g. a zero denominator).
- **Fields print in field-number order**, which matches the contract's listing order
  (`status`, `recommendation`, `simulation`, `citations`, `follow_up_questions`,
  `limitations`). JSON key order carries no meaning — read fields by name.

`args_json` is itself a JSON string — the tool's arguments exactly as the agent
serialized them. Parse it separately if you need it.

### `payload` — the structured Policy Manager result

`data.payload.policy_manager_answer_payload` (see `src/main/proto/policy_manager/v1/policy_manager.proto`):

| Field | What it holds |
|---|---|
| `status` | `STATUS_NEEDS_INPUT` (see `follow_up_questions`), `STATUS_CANDIDATE_READY` (a candidate, not yet evaluated), `STATUS_SIMULATION_PENDING` (submitted for backtesting, not finished this turn), `STATUS_EVALUATED` (backtested; `simulation` carries metrics), `STATUS_INSUFFICIENT_DATA`, `STATUS_SIMULATION_FAILED` (a candidate may be present but must **not** be shown as evaluated). `STATUS_UNSPECIFIED` = not set. |
| `recommendation` | Optional. `candidate_id`, `candidate_version`, `change_type` (`CHANGE_TYPE_NEW`/`REFINE`/`THRESHOLD_UPDATE`/`MERGE`/`RETIRE`/`NO_CHANGE`), `target_policy_ids` (empty for `NEW`), `title`, `description`, `rationale`, an optional executable `policy_definition` (`format`, `schema_version`, `definition` — a serialized string in that format), `citation_ids`, `assumptions` (what the recommendation rests on) and an optional `confidence` in [0, 1] (omitted when the model reports none). Advisory only — never a command to create/retire/deploy a policy. |
| `simulation` | Optional. `simulation_id`, `status` (`STATUS_QUEUED`/`RUNNING`/`COMPLETED`/`FAILED`/`CANCELLED`), `candidate_id`/`candidate_version`, `baseline_policy_set_id`/`baseline_policy_set_version`, `snapshot_id`, `limitations`, and — **only for completed results** — `baseline_metrics`/`candidate_metrics` (`true_positives`, `false_positives`, `true_negatives`, `false_negatives`, and optional `precision`, `recall`, `false_positive_rate` as fractions in [0,1]) and `impact` (candidate minus baseline: optional `fraud_capture_uplift`, `false_positive_delta`, optional `net_impact` — unit still to be agreed). |
| `citations` | Evidence: `citation_id`, `source_tool`, `source_record_id`, optional `snapshot_id`, `field_paths`. Each `recommendation.citation_ids` entry should resolve to one of these. |
| `follow_up_questions` | Questions for the user (e.g. with `STATUS_NEEDS_INPUT`). |
| `limitations` | Known limitations of the answer. |

### `policy_definition` — the candidate rule

`recommendation.policy_definition.definition` is a **JSON string** (parse it with
`JSON.parse`) holding one rule in the simulation engine's candidate rule format v1.0 —
see [`COMPLETE_RULE_FORMAT.md`](COMPLETE_RULE_FORMAT.md). `format` is
`candidate-rule-format` and `schema_version` is `1.0`. The parsed object is shaped like:

```json
{
  "position": 5,
  "context": { "name": "tenant-42", "id": "org-7" },
  "details": { "name": "Review high-risk transactions on new devices", "choosenStatus": "WORK_IN_PROGRESS",
               "ruleOrder": 5, "eventTypes": [{ "name": "TRANSFER" }], "channelIndicators": ["WEB", "MOBILE"] },
  "conditions": { "conditions": [ { "isConjunctive": true, "isNegativeRule": false, "expressions": [
      { "operand": "deviceAgeDays", "operator": "LESS_THAN", "factType": "INTEGER",
        "factCategory": "DEFAULT", "valueString": "2", "isListExpression": false },
      { "operand": "destinationCountry", "operator": "NOT_IN", "factType": "STRING",
        "factCategory": "DEFAULT", "isListExpression": true, "listName": "user-known-countries" } ] } ] },
  "actions": { "outcomeAction": "CHALLENGE", "excludeLockedUsers": true,
               "authMethods": [{ "name": "OTP", "displayName": "One-Time Password" }],
               "caseCreations": [{ "outcome": "SUSPICIOUS", "urgencyLevel": "HIGH" }] }
}
```

Treat **every** field as optional, including the ones the spec calls required, and
treat enum-like values (`choosenStatus`, `operator`, `outcomeAction`, …) as open
strings. A candidate rule carries `position` rather than `id`. The OpenAPI doc
(`/v3/api-docs`) includes this shape as the `CandidateRule` schema. This backend
forwards `definition` untouched; it only logs (`GRPC_PAYLOAD_RULE_UNPARSEABLE`) when
the real agent sends one that isn't a JSON object.

Show the candidate version and simulation status together so old metrics can't appear
under an edited candidate. An evaluated example:

```
event:payload
data:{"payload":{"policy_manager_answer_payload":{"status":"STATUS_EVALUATED","recommendation":{"candidate_id":"candidate-01","candidate_version":"1","change_type":"CHANGE_TYPE_REFINE","target_policy_ids":["policy-17"],"title":"Review high-risk transactions on new devices","description":"","rationale":"","citation_ids":[],"assumptions":[]},"simulation":{"simulation_id":"sim-01","status":"STATUS_COMPLETED","candidate_id":"candidate-01","candidate_version":"1","baseline_policy_set_id":"baseline-01","baseline_policy_set_version":"7","snapshot_id":"evaluation-01","baseline_metrics":{"true_positives":"80","false_positives":"180","true_negatives":"9720","false_negatives":"20","precision":0.307692,"recall":0.8,"false_positive_rate":0.018182},"candidate_metrics":{"true_positives":"90","false_positives":"200","true_negatives":"9700","false_negatives":"10","precision":0.310345,"recall":0.9,"false_positive_rate":0.020202},"limitations":[],"impact":{"fraud_capture_uplift":0.1,"false_positive_delta":"20"}},"citations":[],"follow_up_questions":[],"limitations":[]}}}
```

The only event the ML Agent sends that you won't see is one with **no** event type set
(an empty message, or a type newer than this backend's copy of the `.proto`). The
contract says to ignore those, and there's nothing in them to forward.

### The one event this backend adds: `service_error`

Some failures never produce an ML Agent event at all: the agent can't be reached, it
times out, it rejects the call at the gRPC level, or this backend's own protection
(circuit breaker, concurrency limit, message-length check) stops the request. The HTTP
status is already committed to `200` by then, so this backend sends one terminal event
of its own, under a name the ML Agent never uses:

```
event:service_error
data:{"messageId":"5b1e...","errorCode":"ML_AGENT_TIMEOUT","errorMessage":"The AI agent did not respond in time.","timestamp":"2026-09-23T10:15:30Z"}
```

This is **not** the ML Agent's `error` event, and the agent's `error` event is never
converted into it. Handle the two separately (see §8).

Every stream ends with exactly one of `done`, `error`, or `service_error`.

## 7. Full example: a two-turn conversation

**Turn 1 — analyst opens a new chat:**

```bash
curl -N -X POST http://localhost:8080/back-office-ai-pm/api/v1/chat/messages \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -H "X-Tenant-Id: tenant-42" -H "X-Org-Id: org-7" \
  -d '{"message":"Recommend a policy for missed fraud and compare it with our current policies.","policyContext":{"policySetId":"baseline-01","scenarioId":"scenario-01"}}'
```

```
id:0
event:tool_call
data:{"tool_call":{"tool_call_id":"t-1","name":"AgenticGetPolicyContext","args_json":"{\"policySetId\":\"baseline-01\",\"scenarioId\":\"scenario-01\"}"}}

id:1
event:tool_result
data:{"tool_result":{"tool_call_id":"t-1","status":"STATUS_OK","ms":"42","row_count":"3"}}

id:2
event:chunk
data:{"chunk":{"delta":"The proposed refinement is ready for simulation."}}

... more "chunk" events (and possibly "ping") ...

id:7
event:payload
data:{"payload":{"policy_manager_answer_payload":{"status":"STATUS_CANDIDATE_READY","recommendation":{...},"citations":[...],"follow_up_questions":[],"limitations":[]}}}

id:8
event:done
data:{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"1800","tokens_in":"12","tokens_out":"140"}}
```

In the browser (`EventSource` only supports GET, so use a fetch-based SSE reader such
as `@microsoft/fetch-event-source` for this POST endpoint), branch on the event name and
read the agent's own fields:

```js
onmessage(msg) {
  const data = JSON.parse(msg.data);
  switch (msg.event) {
    case "chunk":         appendText(data.chunk.delta); break;
    case "tool_call":     showTool(data.tool_call.tool_call_id, data.tool_call.name); break;
    case "tool_result":   finishTool(data.tool_result.tool_call_id, data.tool_result.status); break;
    case "payload":       renderResult(data.payload.policy_manager_answer_payload); break;
    case "done":          finish(data.done.stop_reason === "STOP_REASON_TRUNCATED"); break;
    case "error":         failModel(data.error.code, data.error.retryable); break;
    case "service_error": failService(data.errorCode, data.errorMessage); break;
    default:              /* ping, or a newer event type — ignore */ break;
  }
}
```

The frontend appends both this turn's prompt and the assembled answer text (from the
`chunk` events) to its own local transcript — this backend never remembers any of it
for you.

**Turn 2 — follow-up question, same conversation:**

```bash
curl -N -X POST http://localhost:8080/back-office-ai-pm/api/v1/chat/messages \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -H "X-Tenant-Id: tenant-42" -H "X-Org-Id: org-7" \
  -d '{"history":[{"role":"user","content":"Recommend a policy for missed fraud and compare it with our current policies."},{"role":"assistant","content":"The proposed refinement is ready for simulation..."}],"policyContext":{"policySetId":"baseline-01","scenarioId":"scenario-01","candidateId":"candidate-01","candidateVersion":"1"},"message":"Backtest it against the baseline."}'
```

There's no id to echo back or compare; `history` carrying the prior turn is what makes
this a continuation rather than a fresh conversation. `policyContext.candidateId`/
`candidateVersion` tell the agent which recommendation is being discussed. If the
backtest completes within the turn, the `payload` has status `STATUS_EVALUATED` with
baseline/candidate metrics; if not, `STATUS_SIMULATION_PENDING` with the run's
`simulation_id` — send that back as `policyContext.simulationId` on a later turn to
retrieve the results:

```
... tool_call / tool_result / chunk events, as in Turn 1 ...

id:6
event:payload
data:{"payload":{"policy_manager_answer_payload":{"status":"STATUS_EVALUATED","recommendation":{...},"simulation":{"simulation_id":"sim-01","status":"STATUS_COMPLETED",...,"baseline_metrics":{...},"candidate_metrics":{...}},"citations":[],"follow_up_questions":[],"limitations":[]}}}

id:7
event:done
data:{"done":{"stop_reason":"STOP_REASON_COMPLETED","latency_ms":"2400","tokens_in":"30","tokens_out":"160"}}
```

## 8. When things go wrong

Once streaming has started, this backend never returns an HTTP error status, so the
frontend should always watch for the two terminal failure events rather than expecting
an HTTP status code mid-stream. (A request that's invalid up front, such as a missing
`X-Tenant-Id` or a blank `message`, is still rejected with a plain HTTP `400`, before any SSE.)

**`error` — the ML Agent's own model-level failure**, forwarded exactly as sent:

| `error.code` | Meaning (per the ML Agent contract) |
|---|---|
| `ERROR_CODE_MODEL_REFUSED` | The agent refused to process the request. Never retryable. |
| `ERROR_CODE_DATA_UNAVAILABLE` | The agent's data source was unavailable. |
| `ERROR_CODE_INTERNAL` | An internal error inside the agent. Also how to treat any unrecognised code. |
| `ERROR_CODE_UNSPECIFIED` | No code given — treat as `ERROR_CODE_INTERNAL`. |

`error.retryable` is the agent's own answer to "should the caller retry?" — use it
directly.

**`service_error` — a failure on this backend's side or in reaching the agent:**

| `errorCode` | What happened | Should the frontend retry? |
|---|---|---|
| `VALIDATION_ERROR` | The message exceeded the runtime length limit | No — fix the request |
| `NOT_FOUND` | The agent rejected the call: requested resource not found in that tenant | No |
| `ML_AGENT_TIMEOUT` | The ML Agent took too long to respond | Maybe, after a pause |
| `ML_AGENT_UNAVAILABLE` | This backend couldn't reach the ML Agent at all | Maybe, after a pause |
| `ML_AGENT_ERROR` | The ML Agent call failed at the transport level | Maybe, after a pause |
| `CONCURRENCY_LIMIT_REACHED` | This backend is protecting itself/the ML Agent from overload right now | Yes, after a short pause |
| `INTERNAL_ERROR` | Something unexpected on this backend's side, or a credential/access problem between this backend and the ML Agent | No — this is not something the frontend can fix |

`errorMessage` is always a short, safe, human-readable sentence — never a raw
exception or stack trace. Transient failures that happen *before* the ML Agent sends
its first event are already retried by this backend (a few times, with backoff) before
a `service_error` is sent.

## 9. Testing this without a real ML Agent

By default this backend runs against a **mock** ML Agent (no real agent needed —
great for frontend development). The mock emits the real contract's own messages, so
what you receive is exactly the shape the real agent will produce. Trigger specific
behaviors by including these keywords anywhere in your `message` text:

| Keyword | Simulates |
|---|---|
| `trigger:slow` | A normal response, but much slower between chunks |
| `trigger:timeout` | The agent never responds → `service_error` `ML_AGENT_TIMEOUT` |
| `trigger:error` | The agent can't be reached/fails at the transport level → `service_error` `ML_AGENT_ERROR` (after this backend's own retries) |
| `trigger:agent-error` | The agent's own `error` event → `error` `{"code":"ERROR_CODE_DATA_UNAVAILABLE","retryable":true}` |
| `trigger:empty` | A valid but empty response: just `done` (no `chunk`/`payload`) |
| `trigger:rejected` | The agent rejects the request → `service_error` `NOT_FOUND` |
| `trigger:needs-input` | `payload` status `STATUS_NEEDS_INPUT` with `follow_up_questions`, no recommendation |
| `trigger:insufficient-data` | `payload` status `STATUS_INSUFFICIENT_DATA` with `limitations`, no recommendation |
| `trigger:simulation-pending` | `payload` status `STATUS_SIMULATION_PENDING`: the candidate plus a `STATUS_RUNNING` simulation (no metrics) |
| `trigger:evaluated` | `payload` status `STATUS_EVALUATED`: the candidate plus a `STATUS_COMPLETED` simulation with baseline/candidate metrics |
| `trigger:simulation-failed` | `payload` status `STATUS_SIMULATION_FAILED`: the candidate plus a `STATUS_FAILED` simulation (no metrics) |

Any other text gets the default answer: an `AgenticGetPolicyContext`
`tool_call`/`tool_result` pair, `chunk`s, a `payload` with status
`STATUS_CANDIDATE_READY` (a `CHANGE_TYPE_REFINE` recommendation with a
`policy_definition` holding a `candidate-rule-format` v1.0 rule, and a resolvable citation),
then `done`. The baseline/scenario/candidate ids echo your `policyContext` (the rule's
`context` echoes your tenant/org), as the real agent's would; everything else is a
labeled mock fixture (`mock-…`), with metrics taken from the contract's illustrative
example. That
makes the full contract (§5–§6) testable end-to-end without waiting on the real ML
Agent integration.

## 10. Quick-reference field map

```text
FRONTEND SENDS               THIS BACKEND FORWARDS              ML AGENT RETURNS  ═══▶  FRONTEND RECEIVES (unchanged)
─────────────────            ────────────────────────────       ─────────────────       ─────────────────────────────
X-Tenant-Id (header)  ───▶  requestContext.tenant               chunk              ═══▶  event:chunk        {"chunk":{...}}
X-Org-Id (header)     ───▶  requestContext.organization         tool_call          ═══▶  event:tool_call    {"tool_call":{...}}
history               ───▶  history (role/content ─▶ user/agent) tool_result       ═══▶  event:tool_result  {"tool_result":{...}}
message               ───▶  prompt                              payload            ═══▶  event:payload      {"payload":{...}}
policyContext         ───▶  policyContext                       done               ═══▶  event:done         {"done":{...}}
requestId             ───▶  requestContext.agentSessionId       error              ═══▶  event:error        {"error":{...}}
X-Correlation-Id      ───▶  requestContext.requestId            ping               ═══▶  event:ping         {"ping":{}}
operatorId            ───▶  operatorId
                                                                (no agent event)   ───▶  event:service_error (this backend's own)
```

The request side (left) is still mapped into the ML Agent's gRPC request shape. The
response side (right) is not mapped at all.

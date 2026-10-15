# Policy Manager chat contract (proposal)

Chat interface between the backend and the Policy Manager agent. It reuses the
shared streaming types in `orchestrator/v1/common.proto` and follows the same
shape as the Case Manager contract.

## Files

- `policy_manager/v1/policy_manager.proto`: request, structured answer, recommendation, simulation and citation types.
- `orchestrator/v1/common.proto`: shared types (`AgentRequestContext`, `ConversationTurn`). Unchanged; included so the contract compiles on its own.

Compile check: `protoc -I . policy_manager/v1/policy_manager.proto`

## Turn flow

1. Backend sends `AskPolicyManagerRequest` (prompt, history, `PolicyManagerContext`).
2. Agent streams `chunk` events with the text answer.
3. Agent sends one `payload` (`PolicyManagerAnswerPayload`), then `done`.
4. On failure the agent sends `error` and no `done`.
5. `done` means the chat turn ended. A simulation may still be pending; see `SimulationSummary.status`.

Simulation metrics come from the simulator and must match the referenced candidate version and snapshot.

## Not included yet

- The `AskPolicyManager` rpc and the `policy_manager_answer_payload` arm of `AnswerPayload` still need to be added to `chat_agent.proto`.
- Agent-to-data and agent-to-simulator RPCs are separate contracts.

## Open questions

1. `PolicyDefinition.format` is a free-form string. Which format does the simulator accept? It should become an enum.
2. Version fields (`policy_set_version`, `candidate_version`, baseline version) are strings. Confirm the type.
3. Confirm `simulation_id` and `snapshot_id` match the simulator's identifiers.
4. `PolicyImpact.net_impact`: unit and definition needed. Is fraud-capture uplift equal to the recall delta?
5. `missed_fraud_indicator_ids` and `historical_summary` (PRD 8.3 LLM inputs): does the backend supply these, or does the agent fetch them from `scenario_id`?
6. SME approve/reject/request-modification and the audit trail (PRD PM-12, PM-13) are not in this chat contract. Confirm they are separate APIs.
7. Who implements `AskPolicyManager`: the existing orchestrator agent or the Thoughtful Labs LLM API?

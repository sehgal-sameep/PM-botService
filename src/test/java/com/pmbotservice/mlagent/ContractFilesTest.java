package com.pmbotservice.mlagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.cmbotservice.mlagent.grpc.v1.AskPolicyManagerRequest;
import com.cmbotservice.mlagent.grpc.v1.ChatAgentGrpc;
import com.cmbotservice.mlagent.grpc.v1.PolicyManagerContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the Thoughtful Labs contract: the two shared .proto files are compiled exactly as
 * delivered in {@code docs/}, and the wire facts that follow from them do not drift.
 */
class ContractFilesTest {

  @ParameterizedTest
  @ValueSource(strings = {"orchestrator/v1/common.proto", "policy_manager/v1/policy_manager.proto"})
  void compiledProto_isByteIdenticalToTheDeliveredContract(String file) throws IOException {
    assertThat(Files.readAllBytes(Path.of("src/main/proto", file)))
        .isEqualTo(Files.readAllBytes(Path.of("docs", file)));
  }

  @Test
  void rpcPath_usesTheContractsPackage() {
    assertThat(ChatAgentGrpc.getAskPolicyManagerMethod().getFullMethodName())
        .isEqualTo("cmbotservice.mlagent.v1.ChatAgent/AskPolicyManager");
  }

  @Test
  void requestFieldNumbers_matchTheContract() {
    assertThat(AskPolicyManagerRequest.REQUEST_CONTEXT_FIELD_NUMBER).isEqualTo(1);
    assertThat(AskPolicyManagerRequest.OPERATOR_ID_FIELD_NUMBER).isEqualTo(2);
    assertThat(AskPolicyManagerRequest.PROMPT_FIELD_NUMBER).isEqualTo(3);
    assertThat(AskPolicyManagerRequest.POLICY_CONTEXT_FIELD_NUMBER).isEqualTo(4);
    assertThat(AskPolicyManagerRequest.HISTORY_FIELD_NUMBER).isEqualTo(5);
    assertThat(PolicyManagerContext.MISSED_FRAUD_INDICATOR_IDS_FIELD_NUMBER).isEqualTo(8);
    assertThat(PolicyManagerContext.HISTORICAL_SUMMARY_FIELD_NUMBER).isEqualTo(9);
  }
}

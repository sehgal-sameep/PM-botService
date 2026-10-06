package com.pmbotservice.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatRequestTest {

  @Test
  void blankOperatorId_isNormalizedToNull() {
    ChatRequest request = new ChatRequest(null, null, "  ", "hello");

    assertThat(request.operatorId()).isNull();
  }

  @Test
  void suppliedOperatorId_isKeptAsIs() {
    ChatRequest request = new ChatRequest(null, null, "op-7", "hello");

    assertThat(request.operatorId()).isEqualTo("op-7");
  }
}

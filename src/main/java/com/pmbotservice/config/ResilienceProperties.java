package com.pmbotservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Circuit breaker / bulkhead / retry thresholds guarding the ML Agent call. See {@code
 * ResilienceConfig} for how these become live resilience4j instances, and {@code
 * ChatOrchestrationService} for the retry-classification rules that resilience4j itself doesn't
 * model (stream-position awareness — never retry after partial content).
 */
@ConfigurationProperties(prefix = "resilience")
@Validated
public record ResilienceProperties(
    @NotNull @Valid CircuitBreaker circuitBreaker,
    @NotNull @Valid Bulkhead bulkhead,
    @NotNull @Valid Retry retry) {

  /**
   * {@code enabled = false} takes the circuit breaker out of the ML Agent call chain entirely: no
   * call is ever rejected as {@code CONCURRENCY_LIMIT_REACHED} because the breaker is open. The
   * remaining thresholds are still bound and validated, so turning it back on needs no other
   * change.
   */
  public record CircuitBreaker(
      boolean enabled,
      @Positive @DecimalMax("100") float failureRateThreshold,
      @Positive int slidingWindowSize,
      @NotNull Duration waitDurationInOpenState,
      @Positive int permittedCallsInHalfOpenState,
      @Positive int minimumNumberOfCalls) {}

  public record Bulkhead(@Positive int maxConcurrentCalls, @NotNull Duration maxWaitDuration) {}

  public record Retry(
      @Positive int maxAttempts,
      @NotNull Duration initialBackoff,
      @NotNull Duration maxBackoff,
      @DecimalMin("0.0") @DecimalMax("1.0") double jitterFactor) {}
}

package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetrievalContextTest {
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void shouldRejectNonPositiveCandidateLimit(int limit) {
    assertThatThrownBy(() -> new RetrievalContext(limit, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidateLimit");
    assertThatThrownBy(() -> RetrievalContext.of(limit, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldCarryCandidateLimit() {
    var context = RetrievalContext.of(10, Duration.ofMillis(200));
    assertThat(context.candidateLimit()).isEqualTo(10);
    assertThat(context.timeBudget()).isEqualTo(Duration.ofMillis(200));
  }

  @Test
  void shouldRejectInvalidTimeBudget() {
    assertThatThrownBy(() -> RetrievalContext.of(10, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrievalContext.of(10, Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrievalContext.of(10, null))
        .isInstanceOf(NullPointerException.class);
  }
}

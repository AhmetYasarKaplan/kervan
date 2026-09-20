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
    assertThatThrownBy(() -> new RetrievalContext(limit, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidateLimit");
    assertThatThrownBy(() -> RetrievalContext.of(limit, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1, 100})
  void shouldPreserveNonNegativeTimeBudget(long nanos) {
    var timeout = Duration.ofNanos(nanos);
    var context = RetrievalContext.of(1, timeout);
    assertThat(context.candidateLimit()).isEqualTo(1);
    assertThat(context.timeout()).isEqualTo(timeout);
  }

  @Test
  void shouldRejectNullAndNegativeTimeout() {
    assertThatThrownBy(() -> new RetrievalContext(1, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetrievalContext(1, Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout");
  }
}

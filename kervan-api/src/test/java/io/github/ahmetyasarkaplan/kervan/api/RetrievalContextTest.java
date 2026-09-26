package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetrievalContextTest {
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void shouldRejectNonPositiveCandidateLimit(int limit) {
    assertThatThrownBy(() -> new RetrievalContext(limit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidateLimit");
    assertThatThrownBy(() -> RetrievalContext.of(limit))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldCarryCandidateLimit() {
    assertThat(RetrievalContext.of(10).candidateLimit()).isEqualTo(10);
  }
}

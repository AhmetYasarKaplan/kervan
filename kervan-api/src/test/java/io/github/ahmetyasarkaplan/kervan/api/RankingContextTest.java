package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RankingContextTest {
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void shouldRejectNonPositiveLimit(int limit) {
    assertThatThrownBy(() -> new RankingContext(limit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidateLimit");
    assertThatThrownBy(() -> RankingContext.of(limit)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 100, Integer.MAX_VALUE})
  void shouldPreservePositiveLimit(int limit) {
    assertThat(RankingContext.of(limit).candidateLimit()).isEqualTo(limit);
  }
}

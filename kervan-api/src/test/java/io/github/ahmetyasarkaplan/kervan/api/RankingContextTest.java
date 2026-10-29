package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RankingContextTest {
  @Test
  void remainingBudgetDecreasesAndExpiresAtZero() {
    var context = RankingContext.of(5, Duration.ofMillis(20));
    Duration initial = context.remainingBudget();
    assertThat(initial).isPositive().isLessThanOrEqualTo(Duration.ofMillis(20));
    LockSupport.parkNanos(Duration.ofMillis(30).toNanos());
    assertThat(context.remainingBudget()).isZero();
    assertThat(context.remainingBudget()).isZero();
    assertThat(RankingContext.of(1, Duration.ZERO).remainingBudget()).isZero();
  }

  @Test
  void rejectsInvalidBudgets() {
    assertThatThrownBy(() -> RankingContext.of(1, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RankingContext.of(1, Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RankingContext.of(1, Duration.ofSeconds(Long.MAX_VALUE)))
        .isInstanceOf(IllegalArgumentException.class);
  }

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

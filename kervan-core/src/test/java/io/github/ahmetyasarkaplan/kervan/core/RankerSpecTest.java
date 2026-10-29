package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RankerSpecTest {
  @Test
  void rejectsInvalidConfiguration() {
    var identity = new IdentityRanker<String, String>();
    assertThatThrownBy(
            () -> new RankerSpec<>(null, 1, 1, Duration.ofSeconds(1), RankerFailurePolicy.FAIL))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new RankerSpec<>(identity, 0, 1, Duration.ofSeconds(1), RankerFailurePolicy.FAIL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RankerSpec<>(identity, 1, 0, Duration.ofSeconds(1), RankerFailurePolicy.FAIL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RankerSpec<>(identity, 1, 2, Duration.ofSeconds(1), RankerFailurePolicy.FAIL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new RankerSpec<>(identity, 1, 1, Duration.ZERO, RankerFailurePolicy.FAIL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RankerSpec<>(
                    identity, 1, 1, Duration.ofSeconds(Long.MAX_VALUE), RankerFailurePolicy.FAIL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RankerSpec<>(identity, 1, 1, Duration.ofSeconds(1), null))
        .isInstanceOf(NullPointerException.class);
  }
}

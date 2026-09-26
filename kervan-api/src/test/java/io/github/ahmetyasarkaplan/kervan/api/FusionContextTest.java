package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FusionContextTest {
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void shouldRejectNonPositiveLimit(int limit) {
    assertThatThrownBy(() -> new FusionContext(limit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidateLimit");
    assertThatThrownBy(() -> FusionContext.of(limit)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 100, Integer.MAX_VALUE})
  void shouldPreservePositiveLimit(int limit) {
    assertThat(FusionContext.of(limit).candidateLimit()).isEqualTo(limit);
  }

  @Test
  void shouldCopyWeightsAndDefaultToNoOverrides() {
    var weights = new HashMap<>(Map.of("search", 2.0));
    var context = FusionContext.of(10, weights);
    weights.clear();

    assertThat(context.sourceWeights()).containsEntry("search", 2.0);
    assertThat(FusionContext.of(10).sourceWeights()).isEmpty();
    assertThatThrownBy(() -> context.sourceWeights().put("other", 1.0))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldRejectInvalidWeights() {
    for (double weight : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThatThrownBy(() -> FusionContext.of(10, Map.of("search", weight)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("weight");
    }
    assertThatThrownBy(() -> FusionContext.of(10, Map.of(" ", 1.0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FusionContext.of(10, null)).isInstanceOf(NullPointerException.class);
  }
}

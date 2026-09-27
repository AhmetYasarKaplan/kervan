package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScoreDirectionTest {

  @Test
  void shouldKeepSimilarityAndInvertDistanceWithoutLosingFiniteExtremes() {
    assertThat(ScoreDirection.HIGHER_IS_BETTER.orient(0.8)).isEqualTo(0.8);
    assertThat(ScoreDirection.LOWER_IS_BETTER.orient(0.2)).isEqualTo(-0.2);
    assertThat(ScoreDirection.LOWER_IS_BETTER.orient(Double.MAX_VALUE))
        .isEqualTo(-Double.MAX_VALUE);
  }

  @Test
  void shouldRejectNonFiniteProviderValues() {
    assertThatThrownBy(() -> ScoreDirection.LOWER_IS_BETTER.orient(Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ScoreDirection.HIGHER_IS_BETTER.orient(Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

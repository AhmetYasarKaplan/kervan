package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SourceProvenanceTest {

  @Test
  void shouldRequirePositiveOneBasedRanks() {
    var candidate = SourceCandidate.of("id", 1.0);
    assertThatThrownBy(() -> new SourceHit<>(candidate, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceContribution("search", -1, 1.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectInvalidContributions() {
    assertThatThrownBy(() -> new SourceContribution(" ", 1, 1.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceContribution("search", 1, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceHit<String>(null, 1))
        .isInstanceOf(NullPointerException.class);
  }
}

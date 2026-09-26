package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceCandidateTest {

  @Test
  void shouldPreserveCanonicalIdAndFiniteScore() {
    var candidate = SourceCandidate.of("product-42", -2.5);
    assertThat(candidate.id()).isEqualTo("product-42");
    assertThat(candidate.score()).isEqualTo(-2.5);
    assertThat(candidate.attributes()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void shouldRejectNonFiniteScores(double score) {
    assertThatThrownBy(() -> SourceCandidate.of("id", score))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finite");
  }

  @Test
  void shouldRejectNullFields() {
    assertThatThrownBy(() -> SourceCandidate.of(null, 1.0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SourceCandidate<>("id", 1.0, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void shouldCopyAttributesWithoutDeepCopyingValues() {
    var attributes = new HashMap<String, Object>(Map.of("category", "books"));
    var candidate = new SourceCandidate<>("id", 1.0, attributes);
    attributes.put("category", "media");
    assertThat(candidate.attributes()).containsEntry("category", "books");
    assertThatThrownBy(() -> candidate.attributes().put("other", true))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

package io.github.ahmetyasarkaplan.kervan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceContributionTest {

  @Test
  void shouldRetainLegacyConstructorWithEmptyAttributes() {
    assertThat(new SourceContribution("search", 1, 2.0))
        .isEqualTo(new SourceContribution("search", 1, 2.0, Map.of()));
  }

  @Test
  void shouldTakeShallowImmutableSnapshotOfProviderAttributes() {
    var values = new ArrayList<>(List.of("books"));
    var attributes = new HashMap<String, Object>(Map.of("categories", values));
    var contribution = new SourceContribution("search", 1, 2.0, attributes);

    attributes.clear();

    assertThat(contribution.attributes()).containsEntry("categories", values);
    assertThat(contribution.attributes().get("categories")).isSameAs(values);
    assertThatThrownBy(() -> contribution.attributes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldRejectNullAttributesAndEntries() {
    assertThatThrownBy(() -> new SourceContribution("search", 1, 2.0, null))
        .isInstanceOf(NullPointerException.class);
    var attributes = new HashMap<String, Object>();
    attributes.put("missing", null);
    assertThatThrownBy(() -> new SourceContribution("search", 1, 2.0, attributes))
        .isInstanceOf(NullPointerException.class);
  }
}

package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroupQuotaPolicyTest {
  @Test
  void shouldGreedilyKeepStableOrderAndFillLimitFromLaterGroups() {
    var first = ranked("a", "first");
    var second = ranked("b", "first");
    var third = ranked("c", "second");
    var fourth = ranked("d", "third");
    var policy =
        new GroupQuotaPolicy<String, String, Object>(
            candidate -> candidate.attributes().get("group"), 1);

    var output = policy.apply(null, List.of(first, second, third, fourth), RankingContext.of(3));

    assertThat(output).containsExactly(first, third, fourth);
    assertThat(output.getFirst()).isSameAs(first);
    assertThatThrownBy(output::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldApplySameCapToSharedMissingGroupBucket() {
    var input =
        List.of(ranked("a", null), ranked("b", null), ranked("c", "known"), ranked("d", null));
    var policy =
        new GroupQuotaPolicy<String, String, Object>(
            candidate -> candidate.attributes().get("group"), 2);

    assertThat(policy.apply(null, input, RankingContext.of(10)))
        .extracting(RankedCandidate::id)
        .containsExactly("a", "b", "c");
  }

  @Test
  void shouldUseGenericGroupEqualityAndSnapshotInput() {
    var first = ranked("a", new OpaqueGroup(1));
    var second = ranked("b", new OpaqueGroup(1));
    var third = ranked("c", new OpaqueGroup(2));
    var input = new ArrayList<>(List.of(first, second, third));
    var policy =
        new GroupQuotaPolicy<String, String, Object>(
            candidate -> candidate.attributes().get("group"), 1);
    var output = policy.apply(null, input, RankingContext.of(2));
    input.clear();

    assertThat(output).containsExactly(first, third);
    assertThat(policy.apply(null, List.of(), RankingContext.of(1))).isEmpty();
  }

  @Test
  void shouldRejectInvalidConfigurationAndInput() {
    assertThatThrownBy(() -> new GroupQuotaPolicy<String, String, String>(candidate -> "group", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GroupQuotaPolicy<String, String, String>(candidate -> "group", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GroupQuotaPolicy<String, String, String>(null, 1))
        .isInstanceOf(NullPointerException.class);
    var policy = new GroupQuotaPolicy<String, String, String>(candidate -> "group", 1);
    assertThatThrownBy(() -> policy.apply(null, null, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> policy.apply(null, Arrays.asList(ranked("a", null), null), RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> policy.apply(null, List.of(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                policy.apply(
                    null, List.of(ranked("a", null), ranked("a", null)), RankingContext.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private record OpaqueGroup(int value) {
    @Override
    public String toString() {
      throw new AssertionError("groups must not be stringified or ordered");
    }
  }

  private static RankedCandidate<String> ranked(String id, Object group) {
    return new RankedCandidate<>(
        id,
        0.5,
        List.of(new SourceContribution("search", 1, 8.0)),
        group == null ? Map.of() : Map.of("group", group));
  }
}

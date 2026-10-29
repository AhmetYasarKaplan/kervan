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
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PinnedRankingPolicyTest {
  @Test
  void shouldMoveExistingPinsInRequestedOrderSkipAbsentPinsAndPreserveCandidateValues() {
    var first = ranked("a");
    var second = ranked("b");
    var third = ranked("c");
    var output =
        new PinnedRankingPolicy<String, String>(List.of("missing", "c", "a"))
            .apply("query", List.of(first, second, third), RankingContext.of(3));

    assertThat(output).containsExactly(third, first, second);
    assertThat(output.getFirst()).isSameAs(third);
    assertThatThrownBy(output::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldSelectQueryDependentPinsAndApplyOutputLimitAfterPinning() {
    var policy =
        new PinnedRankingPolicy<String, String>(
            query -> query.equals("special") ? List.of("c") : List.of());
    var input = List.of(ranked("a"), ranked("b"), ranked("c"));

    assertThat(policy.apply("special", input, RankingContext.of(2)))
        .extracting(RankedCandidate::id)
        .containsExactly("c", "a");
    assertThat(policy.apply("ordinary", input, RankingContext.of(2)))
        .extracting(RankedCandidate::id)
        .containsExactly("a", "b");
  }

  @Test
  void shouldSnapshotFixedPinsAndInputAndSupportOpaqueIds() {
    var first = new OpaqueId(1);
    var second = new OpaqueId(2);
    var pins = new ArrayList<>(List.of(second));
    var policy = new PinnedRankingPolicy<String, OpaqueId>(pins);
    pins.clear();
    var input = new ArrayList<>(List.of(ranked(first), ranked(second)));
    var output = policy.apply(null, input, RankingContext.of(3));
    input.clear();

    assertThat(output).extracting(RankedCandidate::id).containsExactly(second, first);
    assertThat(policy.apply(null, List.of(), RankingContext.of(1))).isEmpty();
  }

  @Test
  void shouldRejectInvalidFixedOrDynamicPins() {
    assertThatThrownBy(() -> new PinnedRankingPolicy<String, String>(List.of("a", "a")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PinnedRankingPolicy<String, String>(Arrays.asList("a", null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PinnedRankingPolicy<String, String>((List<String>) null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new PinnedRankingPolicy<String, String>((Function<String, List<String>>) null))
        .isInstanceOf(NullPointerException.class);
    for (List<String> pins : Arrays.asList(null, List.of("a", "a"), Arrays.asList("a", null))) {
      var policy = new PinnedRankingPolicy<String, String>(query -> pins);
      assertThatThrownBy(() -> policy.apply(null, List.of(ranked("a")), RankingContext.of(1)))
          .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }
  }

  @Test
  void shouldRejectInvalidCandidatesAndMissingContext() {
    var policy = new PinnedRankingPolicy<String, String>(List.of());
    assertThatThrownBy(
            () -> policy.apply(null, List.of(ranked("a"), ranked("a")), RankingContext.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.apply(null, null, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> policy.apply(null, Arrays.asList(ranked("a"), null), RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> policy.apply(null, List.of(), null))
        .isInstanceOf(NullPointerException.class);
  }

  private record OpaqueId(int value) {
    @Override
    public String toString() {
      throw new AssertionError("IDs must not be stringified or ordered");
    }
  }

  private static <ID> RankedCandidate<ID> ranked(ID id) {
    return new RankedCandidate<>(
        id, 0.5, List.of(new SourceContribution("search", 1, 8.0)), Map.of("title", "product"));
  }
}

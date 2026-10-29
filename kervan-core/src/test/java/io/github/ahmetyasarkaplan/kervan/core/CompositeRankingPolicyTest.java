package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class CompositeRankingPolicyTest {
  @Test
  void shouldPinBeforeQuotaAndFillFromBeyondTheFinalResultLimit() {
    var input = input();
    var policy =
        new CompositeRankingPolicy<String, String>(
            List.of(new PinnedRankingPolicy<>(List.of("b")), quota()));

    var output = policy.apply(null, input, RankingContext.of(3));

    assertThat(output).containsExactly(input.get(1), input.get(2), input.get(3));
    assertThat(input).extracting(RankedCandidate::id).containsExactly("a", "b", "c", "d");
    assertThat(output.getFirst()).isSameAs(input.get(1));
  }

  @Test
  void shouldMakeCompositionOrderExplicitAndNeverResurrectMissingPins() {
    var input = input();
    var quotaThenPins =
        new CompositeRankingPolicy<String, String>(
            List.of(quota(), new PinnedRankingPolicy<>(List.of("b"))));

    assertThat(quotaThenPins.apply(null, input, RankingContext.of(3)))
        .containsExactly(input.get(0), input.get(2), input.get(3));
  }

  @Test
  void shouldForwardQueryFullWindowLimitAndDiminishingBudget() {
    var firstBudget = new AtomicReference<Duration>();
    RankingPolicy<String, String> first =
        (query, candidates, context) -> {
          assertThat(query).isEqualTo("query");
          assertThat(context.candidateLimit()).isEqualTo(4);
          firstBudget.set(context.remainingBudget());
          LockSupport.parkNanos(5_000_000);
          return candidates.subList(0, 2);
        };
    RankingPolicy<String, String> second =
        (query, candidates, context) -> {
          assertThat(query).isEqualTo("query");
          assertThat(candidates).hasSize(2);
          assertThat(context.candidateLimit()).isEqualTo(4);
          assertThat(context.remainingBudget()).isLessThan(firstBudget.get());
          return candidates;
        };

    assertThat(
            new CompositeRankingPolicy<>(List.of(first, second))
                .apply("query", input(), RankingContext.of(1, Duration.ofSeconds(10))))
        .hasSize(1);
  }

  @Test
  void shouldKeepExpiredBudgetExpiredEvenForEmptyInput() {
    RankingPolicy<String, String> policy =
        (query, candidates, context) -> {
          assertThat(candidates).isEmpty();
          assertThat(context.candidateLimit()).isEqualTo(1);
          assertThat(context.remainingBudget()).isZero();
          return candidates;
        };

    assertThat(
            new CompositeRankingPolicy<>(List.of(policy))
                .apply(null, List.of(), RankingContext.of(1, Duration.ZERO)))
        .isEmpty();
  }

  @Test
  void shouldSnapshotPoliciesInputsAndEveryIntermediateOutput() {
    var returned = new AtomicReference<ArrayList<RankedCandidate<String>>>();
    RankingPolicy<String, String> first =
        (query, candidates, context) -> {
          assertThatThrownBy(candidates::clear).isInstanceOf(UnsupportedOperationException.class);
          returned.set(new ArrayList<>(candidates));
          return returned.get();
        };
    RankingPolicy<String, String> second =
        (query, candidates, context) -> {
          returned.get().clear();
          assertThat(candidates).hasSize(4);
          assertThatThrownBy(candidates::clear).isInstanceOf(UnsupportedOperationException.class);
          return candidates;
        };
    var policies = new ArrayList<>(List.of(first, second));
    var composite = new CompositeRankingPolicy<>(policies);
    policies.clear();
    var input = new ArrayList<>(input());
    var output = composite.apply(null, input, RankingContext.of(10));
    input.clear();

    assertThat(output).hasSize(4);
    assertThatThrownBy(output::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void shouldRejectIntermediateInjectionRewriteOrDuplicateEvenIfLaterPolicyWouldHideIt() {
    RankingPolicy<String, String> hideInvalid = (query, candidates, context) -> List.of();
    List<RankingPolicy<String, String>> invalid =
        List.of(
            (query, candidates, context) -> List.of(ranked("injected", "group")),
            (query, candidates, context) -> List.of(ranked("a", "rewritten-group")),
            (query, candidates, context) -> List.of(candidates.getFirst(), candidates.getFirst()),
            (query, candidates, context) -> null,
            (query, candidates, context) -> Arrays.asList(candidates.getFirst(), null));
    for (RankingPolicy<String, String> policy : invalid) {
      var composite = new CompositeRankingPolicy<>(List.of(policy, hideInvalid));
      assertThatThrownBy(() -> composite.apply(null, input(), RankingContext.of(3)))
          .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }
  }

  @Test
  void shouldRejectResurrectionOfACandidateRemovedByAnEarlierPolicy() {
    var input = input();
    RankingPolicy<String, String> removeFirst =
        (query, candidates, context) -> candidates.subList(1, candidates.size());
    RankingPolicy<String, String> resurrect =
        (query, candidates, context) -> List.of(input.getFirst());
    var composite = new CompositeRankingPolicy<>(List.of(removeFirst, resurrect));

    assertThatThrownBy(() -> composite.apply(null, input, RankingContext.of(3)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unchanged subset");
  }

  @Test
  void shouldTreatEmptyPolicyListAsLimitedIdentityAndRejectInvalidConfigurationAndInput() {
    var composite = new CompositeRankingPolicy<String, String>(List.of());
    assertThat(composite.apply(null, input(), RankingContext.of(2)))
        .containsExactlyElementsOf(input().subList(0, 2));
    assertThat(composite.apply(null, List.of(), RankingContext.of(1))).isEmpty();
    assertThatThrownBy(() -> new CompositeRankingPolicy<String, String>(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new CompositeRankingPolicy<String, String>(
                    Arrays.asList((RankingPolicy<String, String>) null)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> composite.apply(null, null, RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> composite.apply(null, input(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                composite.apply(
                    null, Arrays.asList((RankedCandidate<String>) null), RankingContext.of(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                composite.apply(
                    null, List.of(input().getFirst(), input().getFirst()), RankingContext.of(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static GroupQuotaPolicy<String, String, Object> quota() {
    return new GroupQuotaPolicy<>(candidate -> candidate.attributes().get("group"), 1);
  }

  private static List<RankedCandidate<String>> input() {
    return List.of(
        ranked("a", "one"), ranked("b", "one"), ranked("c", "two"), ranked("d", "three"));
  }

  private static RankedCandidate<String> ranked(String id, String group) {
    return new RankedCandidate<>(
        id, 0.5, List.of(new SourceContribution("search", 1, 8.0)), Map.of("group", group));
  }
}

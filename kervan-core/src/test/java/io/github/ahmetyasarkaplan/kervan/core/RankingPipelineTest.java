package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RankingPipelineTest {

  @Test
  void shouldCombineTwoIndependentProvidersSequentiallyWithProvenance() {
    List<String> calls = new ArrayList<>();
    CandidateRetriever<String, String> search =
        (query, context) -> {
          calls.add("search:" + query + ":" + context.candidateLimit());
          return List.of(SourceCandidate.of("a", 8.0), SourceCandidate.of("shared", 7.0));
        };
    CandidateRetriever<String, String> recommendations =
        (query, context) -> {
          calls.add("recommendations:" + query + ":" + context.candidateLimit());
          return List.of(SourceCandidate.of("shared", 0.8), SourceCandidate.of("c", 0.7));
        };
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.of("search", search, 10))
            .addRetriever(RetrieverSpec.of("recommendations", recommendations, 5))
            .fusion(new FirstSeenFusion<>(), 10)
            .ranker(new IdentityRanker<>(), 10)
            .resultLimit(10)
            .build();

    var result = pipeline.execute("camera");

    assertThat(calls).containsExactly("search:camera:10", "recommendations:camera:5");
    assertThat(result.candidates())
        .extracting(RankedCandidate::id)
        .containsExactly("a", "shared", "c");
    assertThat(result.candidates().get(1).contributions())
        .containsExactly(
            new SourceContribution("search", 2, 7.0),
            new SourceContribution("recommendations", 1, 0.8));
    assertThat(result.successfulStages()).containsExactlyInAnyOrder("search", "recommendations");
    assertThat(result.failedStages()).isEmpty();
    assertThat(result.isPartial()).isFalse();
    assertThat(result.duration().isNegative()).isFalse();
  }

  @Test
  void shouldDeduplicateWithinSourceBeforeApplyingUniqueCandidateLimit() {
    CandidateRetriever<String, String> search =
        (query, context) ->
            List.of(
                SourceCandidate.of("a", 8.0),
                SourceCandidate.of("a", 99.0),
                SourceCandidate.of("b", 6.0),
                SourceCandidate.of("c", 5.0));
    var result = pipeline(search, 2).execute("query");

    assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a", "b");
    assertThat(result.candidates().get(0).score()).isEqualTo(8.0);
    assertThat(result.candidates().get(1).contributions())
        .containsExactly(new SourceContribution("search", 3, 6.0));
  }

  @Test
  void shouldReturnCompleteEmptyResultWhenProviderFindsNothing() {
    CandidateRetriever<String, String> empty = (query, context) -> List.of();
    var result = pipeline(empty, 2).execute("query");
    assertThat(result.candidates()).isEmpty();
    assertThat(result.isPartial()).isFalse();
    assertThat(result.successfulStages()).containsExactly("search");
  }

  @Test
  void shouldCreateRetrievalStatusAndDurationInCore() {
    var captured = new AtomicReference<RetrievalStatus>();
    var capturedDuration = new AtomicReference<java.time.Duration>();
    CandidateRetriever<String, String> empty = (query, context) -> List.of();
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.of("search", empty, 1))
            .fusion(
                (results, context) -> {
                  captured.set(results.getFirst().status());
                  capturedDuration.set(results.getFirst().duration());
                  return List.of();
                },
                1)
            .ranker(new IdentityRanker<>(), 1)
            .resultLimit(1)
            .build();

    pipeline.execute("query");

    assertThat(captured.get()).isEqualTo(RetrievalStatus.EMPTY);
    assertThat(capturedDuration.get()).isNotNull();
    assertThat(capturedDuration.get().isNegative()).isFalse();
  }

  @Test
  void shouldApplyFusionRankingAndResultBudgets() {
    CandidateRetriever<String, String> search =
        (query, context) ->
            List.of(
                SourceCandidate.of("a", 1.0),
                SourceCandidate.of("b", 2.0),
                SourceCandidate.of("c", 3.0));
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.of("search", search, 10))
            .fusion(new FirstSeenFusion<>(), 3)
            .ranker(
                (query, candidates, context) ->
                    List.of(candidates.get(2), candidates.get(1), candidates.get(0)),
                2)
            .resultLimit(1)
            .build();

    assertThat(pipeline.execute("query").candidates())
        .extracting(RankedCandidate::id)
        .containsExactly("c");
  }

  @Test
  void shouldCapCustomFusionOutputBeforePassingItToTheRanker() {
    var seenByRanker = new AtomicReference<List<RankedCandidate<String>>>();
    CandidateRetriever<String, String> search =
        (query, context) ->
            List.of(
                SourceCandidate.of("a", 3.0),
                SourceCandidate.of("b", 2.0),
                SourceCandidate.of("c", 1.0));
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.of("search", search, 3))
            .fusion(
                (results, context) ->
                    new FirstSeenFusion<String>().fuse(results, FusionContext.of(3)),
                2)
            .ranker(
                (query, candidates, context) -> {
                  seenByRanker.set(candidates);
                  return candidates;
                },
                2)
            .resultLimit(2)
            .build();

    var result = pipeline.execute("query");

    assertThat(seenByRanker.get()).extracting(RankedCandidate::id).containsExactly("a", "b");
    assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a", "b");
  }

  @Test
  void shouldSnapshotConfigurationWhenBuilding() {
    CandidateRetriever<String, String> search =
        (query, context) -> List.of(SourceCandidate.of("a", 1.0));
    var builder =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.of("search", search, 1))
            .fusion(new FirstSeenFusion<>(), 1)
            .ranker(new IdentityRanker<>(), 1)
            .resultLimit(1);
    var pipeline = builder.build();
    builder.addRetriever(RetrieverSpec.of("later", search, 1));
    assertThat(pipeline.execute("query").successfulStages()).containsExactly("search");
  }

  @Test
  void shouldRejectInvalidBuilderConfiguration() {
    CandidateRetriever<String, String> empty = (query, context) -> List.of();
    assertThatThrownBy(() -> RankingPipeline.<String, String>builder(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrieverSpec.of("search", empty, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrieverSpec.of(" ", empty, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RankingPipeline.<String, String>builder("discovery")
                    .fusion(new FirstSeenFusion<>(), 1)
                    .ranker(new IdentityRanker<>(), 1)
                    .resultLimit(1)
                    .build())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                RankingPipeline.<String, String>builder("discovery")
                    .addRetriever(RetrieverSpec.of("search", empty, 1))
                    .addRetriever(RetrieverSpec.of("search", empty, 1))
                    .fusion(new FirstSeenFusion<>(), 1)
                    .ranker(new IdentityRanker<>(), 1)
                    .resultLimit(1)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(
            () ->
                RankingPipeline.<String, String>builder("discovery")
                    .addRetriever(RetrieverSpec.of("search", empty, 1))
                    .build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldRejectInvalidProviderOutputsAndAvoidFalseSuccess() {
    CandidateRetriever<String, String> nullList = (query, context) -> null;
    assertThatThrownBy(() -> pipeline(nullList, 2).execute("query"))
        .isInstanceOf(NullPointerException.class);
    CandidateRetriever<String, String> nullCandidate =
        (query, context) -> Arrays.asList((SourceCandidate<String>) null);
    assertThatThrownBy(() -> pipeline(nullCandidate, 2).execute("query"))
        .isInstanceOf(NullPointerException.class);
    CandidateRetriever<String, String> nullAfterLimit =
        (query, context) -> Arrays.asList(SourceCandidate.of("a", 1.0), null);
    assertThatThrownBy(() -> pipeline(nullAfterLimit, 1).execute("query"))
        .isInstanceOf(NullPointerException.class);
    CandidateRetriever<String, String> failing =
        (query, context) -> {
          throw new IllegalStateException("provider unavailable");
        };
    assertThatThrownBy(() -> pipeline(failing, 2).execute("query"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("provider unavailable");
  }

  @Test
  void shouldRejectDuplicateIdsFromCustomFusion() {
    CandidateRetriever<String, String> search =
        (query, context) -> List.of(SourceCandidate.of("a", 1.0));
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.of("search", search, 1))
            .fusion(
                (results, context) -> {
                  var candidate =
                      new RankedCandidate<>(
                          "a", 1.0, List.of(new SourceContribution("search", 1, 1.0)), Map.of());
                  return List.of(candidate, candidate);
                },
                2)
            .ranker(new IdentityRanker<>(), 2)
            .resultLimit(2)
            .build();
    assertThatThrownBy(() -> pipeline.execute("query"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void shouldRejectDuplicateIdsFromCustomRanker() {
    CandidateRetriever<String, String> search =
        (query, context) -> List.of(SourceCandidate.of("a", 1.0));
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.of("search", search, 1))
            .fusion(new FirstSeenFusion<>(), 1)
            .ranker(
                (query, candidates, context) ->
                    List.of(candidates.getFirst(), candidates.getFirst()),
                2)
            .resultLimit(2)
            .build();

    assertThatThrownBy(() -> pipeline.execute("query"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  private static RankingPipeline<String, String> pipeline(
      CandidateRetriever<String, String> retriever, int limit) {
    return RankingPipeline.<String, String>builder("discovery")
        .addRetriever(RetrieverSpec.of("search", retriever, limit))
        .fusion(new FirstSeenFusion<>(), 10)
        .ranker(new IdentityRanker<>(), 10)
        .resultLimit(10)
        .build();
  }
}

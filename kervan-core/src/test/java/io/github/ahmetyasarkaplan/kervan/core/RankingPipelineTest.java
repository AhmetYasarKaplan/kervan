package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RankingPipelineTest {

  @Test
  void shouldCombineTwoIndependentProvidersSequentiallyWithProvenance() {
    List<String> calls = Collections.synchronizedList(new ArrayList<>());
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
            .addRetriever(RetrieverSpec.required("search", search, 10, Duration.ofSeconds(5)))
            .addRetriever(
                RetrieverSpec.required(
                    "recommendations", recommendations, 5, Duration.ofSeconds(5)))
            .fusion(new FirstSeenFusion<>(), 10)
            .ranker(new IdentityRanker<>(), 10)
            .resultLimit(10)
            .timeout(Duration.ofSeconds(5))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build();

    var result = pipeline.execute("camera");

    assertThat(calls).containsExactlyInAnyOrder("search:camera:10", "recommendations:camera:5");
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
            .addRetriever(RetrieverSpec.required("search", empty, 1, Duration.ofSeconds(5)))
            .fusion(
                (results, context) -> {
                  captured.set(results.getFirst().status());
                  capturedDuration.set(results.getFirst().duration());
                  return List.of();
                },
                1)
            .ranker(new IdentityRanker<>(), 1)
            .resultLimit(1)
            .timeout(Duration.ofSeconds(5))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
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
            .addRetriever(RetrieverSpec.required("search", search, 10, Duration.ofSeconds(5)))
            .fusion(new FirstSeenFusion<>(), 3)
            .ranker(
                (query, candidates, context) ->
                    List.of(candidates.get(2), candidates.get(1), candidates.get(0)),
                2)
            .resultLimit(1)
            .timeout(Duration.ofSeconds(5))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
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
            .addRetriever(RetrieverSpec.required("search", search, 3, Duration.ofSeconds(5)))
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
            .timeout(Duration.ofSeconds(5))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
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
            .addRetriever(RetrieverSpec.required("search", search, 1, Duration.ofSeconds(5)))
            .fusion(new FirstSeenFusion<>(), 1)
            .ranker(new IdentityRanker<>(), 1)
            .resultLimit(1);
    var pipeline =
        builder.timeout(Duration.ofSeconds(5)).failurePolicy(FailurePolicy.PARTIAL_RESULTS).build();
    builder.addRetriever(RetrieverSpec.required("later", search, 1, Duration.ofSeconds(5)));
    assertThat(pipeline.execute("query").successfulStages()).containsExactly("search");
  }

  @Test
  void shouldRejectInvalidBuilderConfiguration() {
    CandidateRetriever<String, String> empty = (query, context) -> List.of();
    assertThatThrownBy(() -> RankingPipeline.<String, String>builder(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrieverSpec.required("search", empty, 0, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrieverSpec.required(" ", empty, 1, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrieverSpec.required("search", empty, 1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrieverSpec.optional("search", empty, 1, Duration.ofNanos(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrieverSpec.required("search", empty, 1, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> RankingPipeline.<String, String>builder("discovery").timeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> RankingPipeline.<String, String>builder("discovery").failurePolicy(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                RankingPipeline.<String, String>builder("discovery")
                    .fusion(new FirstSeenFusion<>(), 1)
                    .ranker(new IdentityRanker<>(), 1)
                    .resultLimit(1)
                    .timeout(Duration.ofSeconds(5))
                    .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
                    .build())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                RankingPipeline.<String, String>builder("discovery")
                    .addRetriever(RetrieverSpec.required("search", empty, 1, Duration.ofSeconds(5)))
                    .addRetriever(RetrieverSpec.required("search", empty, 1, Duration.ofSeconds(5)))
                    .fusion(new FirstSeenFusion<>(), 1)
                    .ranker(new IdentityRanker<>(), 1)
                    .resultLimit(1)
                    .timeout(Duration.ofSeconds(5))
                    .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(
            () ->
                RankingPipeline.<String, String>builder("discovery")
                    .addRetriever(RetrieverSpec.required("search", empty, 1, Duration.ofSeconds(5)))
                    .timeout(Duration.ofSeconds(5))
                    .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
                    .build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldRejectInvalidProviderOutputsAndAvoidFalseSuccess() {
    CandidateRetriever<String, String> nullList = (query, context) -> null;
    assertThatThrownBy(() -> pipeline(nullList, 2).execute("query"))
        .isInstanceOf(PipelineExecutionException.class)
        .extracting("reason")
        .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED);
    CandidateRetriever<String, String> nullCandidate =
        (query, context) -> Arrays.asList((SourceCandidate<String>) null);
    assertThatThrownBy(() -> pipeline(nullCandidate, 2).execute("query"))
        .isInstanceOf(PipelineExecutionException.class);
    CandidateRetriever<String, String> nullAfterLimit =
        (query, context) -> Arrays.asList(SourceCandidate.of("a", 1.0), null);
    assertThatThrownBy(() -> pipeline(nullAfterLimit, 1).execute("query"))
        .isInstanceOf(PipelineExecutionException.class);
    CandidateRetriever<String, String> failing =
        (query, context) -> {
          throw new IllegalStateException("provider unavailable");
        };
    assertThatThrownBy(() -> pipeline(failing, 2).execute("query"))
        .isInstanceOf(PipelineExecutionException.class)
        .hasMessageNotContaining("provider unavailable");
  }

  @Test
  void shouldRejectDuplicateIdsFromCustomFusion() {
    CandidateRetriever<String, String> search =
        (query, context) -> List.of(SourceCandidate.of("a", 1.0));
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.required("search", search, 1, Duration.ofSeconds(5)))
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
            .timeout(Duration.ofSeconds(5))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build();
    assertThatThrownBy(() -> pipeline.execute("query"))
        .isInstanceOf(PipelineExecutionException.class)
        .extracting("reason")
        .isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED);
  }

  @Test
  void shouldRejectDuplicateIdsFromCustomRanker() {
    CandidateRetriever<String, String> search =
        (query, context) -> List.of(SourceCandidate.of("a", 1.0));
    var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.required("search", search, 1, Duration.ofSeconds(5)))
            .fusion(new FirstSeenFusion<>(), 1)
            .ranker(
                (query, candidates, context) ->
                    List.of(candidates.getFirst(), candidates.getFirst()),
                2)
            .resultLimit(2)
            .timeout(Duration.ofSeconds(5))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build();

    assertThatThrownBy(() -> pipeline.execute("query"))
        .isInstanceOf(PipelineExecutionException.class)
        .extracting("reason")
        .isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED);
  }

  private static RankingPipeline<String, String> pipeline(
      CandidateRetriever<String, String> retriever, int limit) {
    return RankingPipeline.<String, String>builder("discovery")
        .addRetriever(RetrieverSpec.required("search", retriever, limit, Duration.ofSeconds(5)))
        .fusion(new FirstSeenFusion<>(), 10)
        .ranker(new IdentityRanker<>(), 10)
        .resultLimit(10)
        .timeout(Duration.ofSeconds(5))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
        .build();
  }
}

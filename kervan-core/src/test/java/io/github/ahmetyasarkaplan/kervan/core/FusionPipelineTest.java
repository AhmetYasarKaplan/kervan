package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FusionPipelineTest {

  @Test
  void shouldUseRrfByDefaultWithoutDroppingCandidatesBeforeRanking() {
    CandidateRetriever<String, String> search =
        (query, context) -> List.of(SourceCandidate.of("a", 50), SourceCandidate.of("b", 40));
    CandidateRetriever<String, String> recommendations =
        (query, context) -> List.of(SourceCandidate.of("c", 0.9), SourceCandidate.of("d", 0.8));

    try (var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.required("search", search, 2, Duration.ofSeconds(1)))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations", recommendations, 2, Duration.ofSeconds(1)))
            .ranker(
                (query, candidates, context) ->
                    List.of(
                        candidates.get(3), candidates.get(2), candidates.get(1), candidates.get(0)),
                4)
            .resultLimit(4)
            .timeout(Duration.ofSeconds(2))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      var result = pipeline.execute("camera");

      assertThat(result.candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("d", "b", "c", "a");
      assertThat(result.candidates())
          .extracting(RankedCandidate::score)
          .containsExactly(1.0 / 62, 1.0 / 62, 1.0 / 61, 1.0 / 61);
    }
  }

  @Test
  void shouldPassConfiguredWeightsAndEnforceSourceAndFusionLimits() {
    CandidateRetriever<String, String> search =
        (query, context) ->
            List.of(
                SourceCandidate.of("a", 10),
                SourceCandidate.of("shared", 5),
                SourceCandidate.of("excluded-search", 0));
    CandidateRetriever<String, String> recommendations =
        (query, context) ->
            List.of(
                SourceCandidate.of("shared", 100),
                SourceCandidate.of("b", 50),
                SourceCandidate.of("excluded-recommendations", 0));

    try (var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.required("search", search, 2, Duration.ofSeconds(1), 1.0))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations", recommendations, 2, Duration.ofSeconds(1), 2.0))
            .fusion(new WeightedMinMaxFusion<>(), 2)
            .ranker(new IdentityRanker<>(), 2)
            .resultLimit(2)
            .timeout(Duration.ofSeconds(2))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      var result = pipeline.execute("camera");

      assertThat(result.candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("shared", "a");
      assertThat(result.candidates()).extracting(RankedCandidate::score).containsExactly(2.0, 1.0);
      assertThat(result.candidates().getFirst().contributions())
          .containsExactly(
              new SourceContribution("search", 2, 5.0),
              new SourceContribution("recommendations", 1, 100.0));
      assertThat(result.isPartial()).isFalse();
    }
  }

  @Test
  void shouldProduceTheSameRrfRankingAcrossRepeatedConcurrentExecutions() {
    CandidateRetriever<String, String> search =
        (query, context) -> List.of(SourceCandidate.of("a", 8), SourceCandidate.of("shared", 7));
    CandidateRetriever<String, String> recommendations =
        (query, context) ->
            List.of(SourceCandidate.of("shared", 0.8), SourceCandidate.of("b", 0.7));

    try (var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(RetrieverSpec.required("search", search, 2, Duration.ofSeconds(1)))
            .addRetriever(
                RetrieverSpec.optional(
                    "recommendations", recommendations, 2, Duration.ofSeconds(1)))
            .fusion(new ReciprocalRankFusion<>(60), 3)
            .ranker(new IdentityRanker<>(), 3)
            .resultLimit(3)
            .timeout(Duration.ofSeconds(2))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      for (int run = 0; run < 20; run++) {
        assertThat(pipeline.execute("camera").candidates())
            .extracting(RankedCandidate::id)
            .containsExactly("shared", "a", "b");
      }
    }
  }
}

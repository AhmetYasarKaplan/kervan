package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RankingPolicyPipelineTest {

  @Test
  void policyFiltersAndReordersAfterRankingBeforeFinalLimit() {
    var rankedInputs = new AtomicReference<List<RankedCandidate<String>>>();
    var policyInputs = new AtomicReference<List<RankedCandidate<String>>>();
    RankingPolicy<String, String> policy =
        (query, candidates, context) -> {
          assertThat(query).isEqualTo("camera");
          assertThat(context.candidateLimit()).isEqualTo(2);
          policyInputs.set(candidates);
          return List.of(candidates.get(2), candidates.get(0));
        };
    try (var pipeline =
        builder()
            .ranker(
                (query, candidates, context) -> {
                  rankedInputs.set(candidates);
                  return candidates;
                },
                3)
            .policy(policy)
            .resultLimit(2)
            .build()) {
      var result = pipeline.execute("camera");
      assertThat(policyInputs.get()).isEqualTo(rankedInputs.get());
      assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("c", "a");
      assertThat(result.candidates().getFirst()).isEqualTo(rankedInputs.get().get(2));
      assertThat(result.candidates().getLast()).isEqualTo(rankedInputs.get().getFirst());
      assertThat(result.isPartial()).isFalse();
    }
  }

  @Test
  void policyCanReturnEmptyAndDefaultKeepsOriginalOrder() {
    try (var defaultPipeline = builder().ranker(new IdentityRanker<>(), 3).resultLimit(2).build();
        var filteredPipeline =
            builder()
                .ranker(new IdentityRanker<>(), 3)
                .policy((query, candidates, context) -> List.of())
                .resultLimit(2)
                .build()) {
      assertThat(defaultPipeline.execute("camera").candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("a", "b");
      assertThat(filteredPipeline.execute("camera").candidates()).isEmpty();
    }
  }

  @Test
  void policyCannotDuplicateInventOrAlterRankedCandidates() {
    List<RankingPolicy<String, String>> invalidPolicies =
        List.of(
            (query, candidates, context) -> List.of(candidates.getFirst(), candidates.getFirst()),
            (query, candidates, context) ->
                List.of(
                    new RankedCandidate<>(
                        "unknown",
                        candidates.getFirst().score(),
                        candidates.getFirst().contributions(),
                        candidates.getFirst().attributes())),
            (query, candidates, context) ->
                List.of(
                    new RankedCandidate<>(
                        candidates.getFirst().id(),
                        candidates.getFirst().score() + 1,
                        candidates.getFirst().contributions(),
                        candidates.getFirst().attributes())),
            (query, candidates, context) -> null,
            (query, candidates, context) -> Arrays.asList(candidates.getFirst(), null));
    for (RankingPolicy<String, String> policy : invalidPolicies) {
      try (var pipeline =
          builder().ranker(new IdentityRanker<>(), 3).policy(policy).resultLimit(2).build()) {
        assertThatThrownBy(() -> pipeline.execute("camera"))
            .isInstanceOfSatisfying(
                PipelineExecutionException.class,
                failure ->
                    assertThat(failure.reason())
                        .isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED));
      }
    }
  }

  @Test
  void failedPolicyIsObservedAsFailedStageAndPipeline() {
    var stageStatus = new AtomicReference<PipelineObserver.StageStatus>();
    var pipelineReason = new AtomicReference<PipelineExecutionException.Reason>();
    PipelineObserver observer =
        name ->
            new PipelineObserver.Execution() {
              @Override
              public PipelineObserver.Stage startStage(
                  PipelineObserver.StageKind kind, String stableName, int inputCandidates) {
                if (kind == PipelineObserver.StageKind.POLICY) {
                  return new PipelineObserver.Stage() {
                    @Override
                    public void finished(
                        PipelineObserver.StageStatus status,
                        Duration duration,
                        int outputCandidates) {
                      stageStatus.set(status);
                    }
                  };
                }
                return PipelineObserver.Stage.NOOP;
              }

              @Override
              public void failed(PipelineExecutionException.Reason reason, Duration duration) {
                pipelineReason.set(reason);
              }
            };
    try (var pipeline =
        builder()
            .ranker(new IdentityRanker<>(), 3)
            .policy(
                (query, candidates, context) -> {
                  throw new IllegalStateException("private policy detail");
                })
            .observer(observer)
            .resultLimit(2)
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              failure ->
                  assertThat(failure.reason())
                      .isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED))
          .hasMessageNotContaining("private policy detail");
      assertThat(stageStatus.get()).isEqualTo(PipelineObserver.StageStatus.FAILED);
      assertThat(pipelineReason.get()).isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED);
    }
  }

  private static RankingPipeline.Builder<String, String> builder() {
    return RankingPipeline.<String, String>builder("policy-test")
        .addRetriever(
            RetrieverSpec.required(
                "search",
                (query, context) ->
                    List.of(
                        SourceCandidate.of("a", 3),
                        SourceCandidate.of("b", 2),
                        SourceCandidate.of("c", 1)),
                3,
                Duration.ofSeconds(2)))
        .fusion(new FirstSeenFusion<>(), 3)
        .timeout(Duration.ofSeconds(2))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS);
  }
}

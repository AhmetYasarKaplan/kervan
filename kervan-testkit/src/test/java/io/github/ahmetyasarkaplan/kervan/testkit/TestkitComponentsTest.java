package io.github.ahmetyasarkaplan.kervan.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TestkitComponentsTest {

  private static final RetrievalContext CONTEXT = RetrievalContext.of(3, Duration.ofSeconds(1));

  @Test
  void stubSnapshotsOutputAndHonorsLimit() {
    List<SourceCandidate<String>> fixture = new ArrayList<>();
    fixture.add(SourceCandidate.of("canonical-a", 3));
    fixture.add(SourceCandidate.of("canonical-b", 2));
    fixture.add(SourceCandidate.of("canonical-c", 1));
    StubRetriever<String, String> retriever = new StubRetriever<>(fixture);
    fixture.clear();

    assertThat(retriever.retrieve("query", RetrievalContext.of(2, Duration.ofSeconds(1))))
        .extracting(SourceCandidate::id)
        .containsExactly("canonical-a", "canonical-b");
    assertThat(retriever.retrieve("query", CONTEXT)).hasSize(3);
    assertThatThrownBy(() -> retriever.retrieve("query", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void slowRetrieverDelegatesAfterDelayAndPreservesInterruption() throws InterruptedException {
    StubRetriever<String, String> stub =
        new StubRetriever<>(List.of(SourceCandidate.of("canonical-a", 3)));
    SlowRetriever<String, String> quick = new SlowRetriever<>(Duration.ofMillis(1), stub);
    assertThat(quick.retrieve("query", CONTEXT))
        .extracting(SourceCandidate::id)
        .containsExactly("canonical-a");

    SlowRetriever<String, String> blocked = new SlowRetriever<>(Duration.ofDays(1), stub);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean interrupted = new AtomicBoolean();
    Thread worker =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    blocked.retrieve("query", CONTEXT);
                  } catch (Throwable e) {
                    failure.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    worker.interrupt();
    worker.join(Duration.ofSeconds(2));

    assertThat(worker.isAlive()).isFalse();
    assertThat(failure.get()).isInstanceOf(SlowRetriever.InterruptedRetrievalException.class);
    assertThat(interrupted.get()).isTrue();
    assertThatThrownBy(() -> new SlowRetriever<String, String>(Duration.ZERO, stub))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failingRetrieverThrowsProvidedFailure() {
    IllegalStateException failure = new IllegalStateException("fixture failure");
    FailingRetriever<String, String> retriever = new FailingRetriever<>(failure);

    assertThatThrownBy(() -> retriever.retrieve("query", CONTEXT)).isSameAs(failure);
  }

  @Test
  void retrieverContractAllowsDifferentTopKOrderingAtSmallerLimit() {
    CandidateRetrieverContract<String, String> contract =
        new CandidateRetrieverContract<>() {
          @Override
          public CandidateRetriever<String, String> retriever() {
            return (query, context) ->
                context.candidateLimit() == 1
                    ? List.of(SourceCandidate.of("canonical-b", 2))
                    : List.of(
                        SourceCandidate.of("canonical-a", 3),
                        SourceCandidate.of("canonical-b", 2),
                        SourceCandidate.of("canonical-c", 1));
          }

          @Override
          public String query() {
            return "fixture";
          }

          @Override
          public List<String> expectedCanonicalIds() {
            return List.of("canonical-a", "canonical-b", "canonical-c");
          }
        };

    contract.returnsOrderedCanonicalCandidatesWithinLimit();
    contract.honorsCandidateLimit();
    contract.returnsStableFixtureOnRepeat();
  }

  @Test
  void fixedStagesRespectOutputBudgets() {
    List<RankedCandidate<String>> output = List.of(ranked("a"), ranked("b"));
    FixedOutputFusion<String> fusion = new FixedOutputFusion<>(output);
    FixedOutputRanker<String, String> ranker = new FixedOutputRanker<>(output);

    assertThat(fusion.fuse(List.of(), FusionContext.of(1)))
        .extracting(RankedCandidate::id)
        .containsExactly("a");
    assertThat(ranker.rank("query", List.of(), RankingContext.of(1)))
        .extracting(RankedCandidate::id)
        .containsExactly("a");
  }

  @Test
  void pipelineAssertionsCheckOrderStatusesAndProvenance() {
    PipelineResult<String> result =
        new PipelineResult<>(
            "fixture",
            List.of(ranked("a")),
            Duration.ZERO,
            List.of(
                RetrievalResult.success(
                    "search",
                    List.of(new SourceHit<>(SourceCandidate.of("a", 1), 1)),
                    Duration.ZERO),
                RetrievalResult.empty("recommendations", Duration.ZERO)));

    PipelineAssertions.assertCandidateIds(result, List.of("a"));
    PipelineAssertions.assertRetrievalStatuses(
        result,
        Map.of("search", RetrievalStatus.SUCCESS, "recommendations", RetrievalStatus.EMPTY));
    PipelineAssertions.assertContributingSources(result, "a", List.of("search"));

    assertThatThrownBy(() -> PipelineAssertions.assertCandidateIds(result, List.of("b")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("candidate IDs");
    assertThatThrownBy(
            () ->
                PipelineAssertions.assertRetrievalStatuses(
                    result, Map.of("search", RetrievalStatus.FAILED)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("retrieval statuses");
    assertThatThrownBy(
            () -> PipelineAssertions.assertContributingSources(result, "a", List.of("other")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("contributing sources");
  }

  private static RankedCandidate<String> ranked(String id) {
    return new RankedCandidate<>(id, 1, List.of(new SourceContribution("search", 1, 1)), Map.of());
  }
}

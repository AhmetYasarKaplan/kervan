package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RankingStatus;
import io.github.ahmetyasarkaplan.kervan.api.RerankingScore;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedRankingPipelineTest {
  private static final Duration SECOND = Duration.ofSeconds(1);

  @Test
  void boundsInputsAndOutputsAndAppliesPolicyAfterSuccessfulReranking() {
    var received = new AtomicReference<List<RankedCandidate<String>>>();
    var policyReceived = new AtomicReference<List<RankedCandidate<String>>>();
    Ranker<String, String> ranker =
        (query, candidates, context) -> {
          assertThat(query).isEqualTo("query");
          assertThat(context.candidateLimit()).isEqualTo(1);
          assertThat(context.remainingBudget()).isPositive().isLessThanOrEqualTo(SECOND);
          assertThat(Thread.currentThread().isVirtual()).isTrue();
          received.set(candidates);
          RankedCandidate<String> second = candidates.get(1);
          return List.of(
              new RankedCandidate<>(
                  second.id(),
                  9,
                  second.contributions(),
                  second.attributes(),
                  Optional.of(new RerankingScore(second.score(), 9))),
              candidates.getFirst());
        };
    try (var pipeline =
        base(ranker, RankerFailurePolicy.FAIL, SECOND, 2, 1)
            .policy(
                (query, candidates, context) -> {
                  policyReceived.set(candidates);
                  return candidates;
                })
            .build()) {
      var result = pipeline.execute("query");
      assertThat(received.get()).extracting(RankedCandidate::id).containsExactly("a", "b");
      assertThat(policyReceived.get()).extracting(RankedCandidate::id).containsExactly("b");
      assertThat(result.candidates()).extracting(RankedCandidate::score).containsExactly(9.0);
      assertThat(result.candidates().getFirst().contributions())
          .isEqualTo(received.get().get(1).contributions());
      assertThat(result.rankingOutcome().status()).isEqualTo(RankingStatus.SUCCESS);
      assertThat(result.rankingOutcome().inputCandidates()).isEqualTo(2);
      assertThat(result.rankingOutcome().outputCandidates()).isEqualTo(1);
      assertThat(result.rankingOutcome().fallbackUsed()).isFalse();
      assertThat(result.rankingOutcome().duration().isNegative()).isFalse();
      assertThat(result.isPartial()).isFalse();
      assertThat(result.isDegraded()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "duplicate", "foreign", "attributes", "fusion-score", "throw"})
  void malformedOrFailedRankerUsesOriginalFusedCandidates(String mode) {
    Ranker<String, String> ranker =
        (query, candidates, context) -> {
          RankedCandidate<String> first = candidates.getFirst();
          return switch (mode) {
            case "null" -> null;
            case "duplicate" -> List.of(first, first);
            case "foreign" ->
                List.of(
                    new RankedCandidate<>("foreign", 1, first.contributions(), first.attributes()));
            case "attributes" ->
                List.of(
                    new RankedCandidate<>(first.id(), 2, first.contributions(), Map.of("x", 1)));
            case "fusion-score" ->
                List.of(
                    new RankedCandidate<>(
                        first.id(),
                        2,
                        first.contributions(),
                        first.attributes(),
                        Optional.of(new RerankingScore(1234, 2))));
            default -> throw new IllegalStateException("secret model error");
          };
        };
    var policyRan = new AtomicBoolean();
    try (var pipeline =
        base(ranker, RankerFailurePolicy.KEEP_FUSED, SECOND, 2, 1)
            .policy(
                (query, candidates, context) -> {
                  policyRan.set(true);
                  return candidates;
                })
            .build()) {
      var result = pipeline.execute("query");
      assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a");
      assertThat(result.candidates().getFirst().score()).isEqualTo(3);
      assertThat(result.rankingOutcome().status()).isEqualTo(RankingStatus.FAILED);
      assertThat(result.rankingOutcome().outputCandidates()).isZero();
      assertThat(result.rankingOutcome().fallbackUsed()).isTrue();
      assertThat(result.isPartial()).isFalse();
      assertThat(result.isDegraded()).isTrue();
      assertThat(policyRan).isTrue();
    }
  }

  @Test
  void rejectsCandidateBeyondSelectedFusedPrefix() {
    var excluded = new AtomicReference<RankedCandidate<String>>();
    try (var pipeline =
        base(
                (query, candidates, context) -> List.of(excluded.get()),
                RankerFailurePolicy.KEEP_FUSED,
                SECOND,
                1,
                1)
            .fusion(
                (results, context) -> {
                  var fused = new FirstSeenFusion<String>().fuse(results, context);
                  excluded.set(fused.get(1));
                  return fused;
                },
                3)
            .build()) {
      assertThat(pipeline.execute("query").rankingOutcome().status())
          .isEqualTo(RankingStatus.FAILED);
    }
  }

  @ParameterizedTest
  @EnumSource(RankerFailurePolicy.class)
  void timeoutCancelsRankerAndSettlesObserverExactlyOnce(RankerFailurePolicy failurePolicy)
      throws Exception {
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var observer = new Events();
    Ranker<String, String> ranker =
        (query, candidates, context) -> {
          try {
            awaitUncooperatively(release, interrupted);
            return candidates;
          } finally {
            done.countDown();
          }
        };
    try (var pipeline =
        base(ranker, failurePolicy, Duration.ofMillis(100), 2, 1).observer(observer).build()) {
      if (failurePolicy == RankerFailurePolicy.KEEP_FUSED) {
        var result = pipeline.execute("query");
        assertThat(result.rankingOutcome().status()).isEqualTo(RankingStatus.TIMED_OUT);
        assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a");
        assertThat(result.isPartial()).isFalse();
        assertThat(result.isDegraded()).isTrue();
      } else {
        assertThatThrownBy(() -> pipeline.execute("query"))
            .isInstanceOfSatisfying(
                PipelineExecutionException.class,
                error ->
                    assertThat(error.reason())
                        .isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED));
      }
      assertThat(interrupted.await(SECOND.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
      release.countDown();
      assertThat(done.await(SECOND.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
      assertThat(observer.rankerFinishes).containsExactly(PipelineObserver.StageStatus.TIMED_OUT);
      assertThat(observer.rankerOutputs).containsExactly(0);
    } finally {
      release.countDown();
    }
  }

  @Test
  void explicitFailPolicyRejectsRankerFailureAndDoesNotRunPolicy() {
    var policyRan = new AtomicBoolean();
    try (var pipeline =
        base(
                (query, candidates, context) -> {
                  throw new IllegalStateException("secret");
                },
                RankerFailurePolicy.FAIL,
                SECOND,
                2,
                1)
            .policy(
                (query, candidates, context) -> {
                  policyRan.set(true);
                  return candidates;
                })
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("query"))
          .isInstanceOf(PipelineExecutionException.class)
          .hasMessageNotContaining("secret");
      assertThat(policyRan).isFalse();
    }
  }

  @Test
  void errorsNeverBecomeFusedFallback() {
    try (var pipeline =
        base(
                (query, candidates, context) -> {
                  throw new AssertionError("fatal");
                },
                RankerFailurePolicy.KEEP_FUSED,
                SECOND,
                2,
                1)
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("query"))
          .isInstanceOf(PipelineExecutionException.class);
    }
  }

  @ParameterizedTest
  @EnumSource(RankerFailurePolicy.class)
  void noRemainingBudgetSkipsModelWithoutUsingFinalizationGrace(RankerFailurePolicy failurePolicy) {
    var invoked = new AtomicBoolean();
    var started = new AtomicReference<Long>();
    Duration pipelineTimeout = Duration.ofMillis(300);
    PipelineObserver observer =
        name -> {
          started.set(System.nanoTime());
          return PipelineObserver.Execution.NOOP;
        };
    try (var pipeline =
        base(
                (query, candidates, context) -> {
                  invoked.set(true);
                  return candidates;
                },
                failurePolicy,
                SECOND,
                2,
                1)
            .observer(observer)
            .timeout(pipelineTimeout)
            .fusion(
                (results, context) -> {
                  waitUntil(
                      started.get() + pipelineTimeout.toNanos() + TimeUnit.MILLISECONDS.toNanos(1));
                  return new FirstSeenFusion<String>().fuse(results, context);
                },
                3)
            .build()) {
      if (failurePolicy == RankerFailurePolicy.KEEP_FUSED) {
        var result = pipeline.execute("query");
        assertThat(result.rankingOutcome().status()).isEqualTo(RankingStatus.SKIPPED_NO_BUDGET);
        assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a");
      } else {
        assertThatThrownBy(() -> pipeline.execute("query"))
            .isInstanceOf(PipelineExecutionException.class);
      }
      assertThat(invoked).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @SuppressWarnings("try")
  void callerInterruptionAndCloseNeverFallBackOrStartPolicy(boolean close) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var workerDone = new CountDownLatch(1);
    var failure = new AtomicReference<PipelineExecutionException>();
    var interruptPreserved = new AtomicBoolean();
    var policyRan = new AtomicBoolean();
    var observer = new Events();
    Ranker<String, String> ranker =
        (query, candidates, context) -> {
          entered.countDown();
          try {
            awaitUncooperatively(release, new CountDownLatch(0));
            return candidates;
          } finally {
            workerDone.countDown();
          }
        };
    try (var pipeline =
        base(ranker, RankerFailurePolicy.KEEP_FUSED, Duration.ofSeconds(5), 2, 1)
            .timeout(Duration.ofSeconds(5))
            .observer(observer)
            .policy(
                (query, candidates, context) -> {
                  policyRan.set(true);
                  return candidates;
                })
            .build()) {
      Thread caller =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      pipeline.execute("query");
                    } catch (PipelineExecutionException error) {
                      failure.set(error);
                      interruptPreserved.set(Thread.currentThread().isInterrupted());
                    } finally {
                      done.countDown();
                    }
                  });
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      if (close) {
        pipeline.close();
      } else {
        caller.interrupt();
      }
      assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).isNotNull();
      assertThat(failure.get().reason())
          .isEqualTo(
              close
                  ? PipelineExecutionException.Reason.CLOSED
                  : PipelineExecutionException.Reason.INTERRUPTED);
      assertThat(interruptPreserved.get()).isEqualTo(!close);
      release.countDown();
      assertThat(workerDone.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(policyRan).isFalse();
      assertThat(observer.rankerFinishes).containsExactly(PipelineObserver.StageStatus.CANCELLED);
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancelledFusionCannotStartRankerOrPolicyAfterReturningLate() throws Exception {
    var release = new CountDownLatch(1);
    var fusionDone = new CountDownLatch(1);
    var rankerRan = new AtomicBoolean();
    var policyRan = new AtomicBoolean();
    var observer = new Events();
    try (var pipeline =
        base(
                (query, candidates, context) -> {
                  rankerRan.set(true);
                  return candidates;
                },
                RankerFailurePolicy.KEEP_FUSED,
                SECOND,
                2,
                1)
            .timeout(Duration.ofMillis(150))
            .observer(observer)
            .fusion(
                (results, context) -> {
                  try {
                    awaitUncooperatively(release, new CountDownLatch(0));
                    return new FirstSeenFusion<String>().fuse(results, context);
                  } finally {
                    fusionDone.countDown();
                  }
                },
                3)
            .policy(
                (query, candidates, context) -> {
                  policyRan.set(true);
                  return candidates;
                })
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("query"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(PipelineExecutionException.Reason.GLOBAL_DEADLINE_EXCEEDED));
      release.countDown();
      assertThat(fusionDone.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(rankerRan).isFalse();
      assertThat(policyRan).isFalse();
      assertThat(observer.fusionFinishes)
          .containsExactly(PipelineObserver.StageStatus.DEADLINE_EXCEEDED);
    } finally {
      release.countDown();
    }
  }

  @Test
  void successfulEmptyRankingHasExplicitOutcome() {
    var events = new Events();
    try (var pipeline =
        base((query, candidates, context) -> List.of(), RankerFailurePolicy.FAIL, SECOND, 2, 1)
            .observer(events)
            .build()) {
      var result = pipeline.execute("query");
      assertThat(result.candidates()).isEmpty();
      assertThat(result.rankingOutcome().status()).isEqualTo(RankingStatus.EMPTY);
      assertThat(result.isDegraded()).isFalse();
      assertThat(events.rankerFinishes).containsExactly(PipelineObserver.StageStatus.EMPTY);
    }
  }

  @Test
  void rankerReceivesAndConsumesOnlyRealPipelineBudget() {
    var started = new AtomicReference<Long>();
    var suppliedBudget = new AtomicReference<Duration>();
    var release = new CountDownLatch(1);
    var observer = new Events();
    Duration pipelineTimeout = Duration.ofMillis(400);
    Ranker<String, String> ranker =
        (query, candidates, context) -> {
          suppliedBudget.set(context.remainingBudget());
          awaitUncooperatively(release, new CountDownLatch(0));
          return candidates;
        };
    try (var pipeline =
        base(ranker, RankerFailurePolicy.KEEP_FUSED, Duration.ofSeconds(5), 2, 1)
            .timeout(pipelineTimeout)
            .observer(
                name -> {
                  started.set(System.nanoTime());
                  return observer.startPipeline(name);
                })
            .fusion(
                (results, context) -> {
                  waitUntil(started.get() + Duration.ofMillis(250).toNanos());
                  return new FirstSeenFusion<String>().fuse(results, context);
                },
                3)
            .build()) {
      var result = pipeline.execute("query");
      assertThat(suppliedBudget.get()).isPositive().isLessThan(Duration.ofMillis(170));
      assertThat(result.rankingOutcome().status()).isEqualTo(RankingStatus.TIMED_OUT);
      assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a");
      assertThat(observer.rankerFinishes).containsExactly(PipelineObserver.StageStatus.TIMED_OUT);
    } finally {
      release.countDown();
    }
  }

  private static RankingPipeline.Builder<String, String> base(
      Ranker<String, String> ranker,
      RankerFailurePolicy failurePolicy,
      Duration timeout,
      int inputLimit,
      int outputLimit) {
    return RankingPipeline.<String, String>builder("bounded")
        .addRetriever(
            RetrieverSpec.required(
                "search",
                (query, context) ->
                    List.of(
                        SourceCandidate.of("a", 3),
                        SourceCandidate.of("b", 2),
                        SourceCandidate.of("c", 1)),
                3,
                SECOND))
        .fusion(new FirstSeenFusion<>(), 3)
        .ranker(new RankerSpec<>(ranker, inputLimit, outputLimit, timeout, failurePolicy))
        .resultLimit(outputLimit)
        .timeout(Duration.ofSeconds(2))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS);
  }

  private static void awaitUncooperatively(CountDownLatch release, CountDownLatch interrupted) {
    while (release.getCount() > 0) {
      try {
        release.await();
      } catch (InterruptedException ignored) {
        interrupted.countDown();
      }
    }
  }

  private static void waitUntil(long target) {
    long remaining;
    while ((remaining = target - System.nanoTime()) > 0) {
      LockSupport.parkNanos(remaining);
    }
  }

  private static final class Events implements PipelineObserver {
    private final List<StageStatus> rankerFinishes = new CopyOnWriteArrayList<>();
    private final List<Integer> rankerOutputs = new CopyOnWriteArrayList<>();
    private final List<StageStatus> fusionFinishes = new CopyOnWriteArrayList<>();

    @Override
    public Execution startPipeline(String name) {
      return new Execution() {
        @Override
        public Stage startStage(StageKind kind, String stableName, int inputCandidates) {
          return new Stage() {
            @Override
            public void finished(StageStatus status, Duration duration, int outputs) {
              if (kind == StageKind.RANKER) {
                rankerFinishes.add(status);
                rankerOutputs.add(outputs);
              } else if (kind == StageKind.FUSION) {
                fusionFinishes.add(status);
              }
            }
          };
        }
      };
    }
  }
}

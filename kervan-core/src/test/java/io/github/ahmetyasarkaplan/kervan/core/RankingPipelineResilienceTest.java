package io.github.ahmetyasarkaplan.kervan.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RankingPipelineResilienceTest {

  @Test
  void shouldInvokeProvidersConcurrentlyOnVirtualThreadsAndPreserveRegistrationOrder()
      throws Exception {
    var bothStarted = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var virtual = new AtomicBoolean(true);
    CandidateRetriever<String, String> first =
        (query, context) -> {
          virtual.compareAndSet(true, Thread.currentThread().isVirtual());
          bothStarted.countDown();
          await(release);
          return List.of(SourceCandidate.of("first", 1.0));
        };
    CandidateRetriever<String, String> second =
        (query, context) -> {
          virtual.compareAndSet(true, Thread.currentThread().isVirtual());
          bothStarted.countDown();
          await(release);
          return List.of(SourceCandidate.of("second", 2.0));
        };

    try (var pipeline =
            pipeline(
                Duration.ofSeconds(5),
                FailurePolicy.PARTIAL_RESULTS,
                List.of(required("first", first), required("second", second)));
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var execution = caller.submit(() -> pipeline.execute("camera"));
      try {
        assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      var result = execution.get(2, TimeUnit.SECONDS);
      assertThat(virtual.get()).isTrue();
      assertThat(result.candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("first", "second");
      assertThat(result.retrievalResults())
          .extracting(resultItem -> resultItem.retrieverName())
          .containsExactly("first", "second");
    }
  }

  @Test
  void shouldPreserveConfiguredOrderWhenSecondProviderCompletesFirst() throws Exception {
    var secondCompleted = new CountDownLatch(1);
    CandidateRetriever<String, String> first =
        (query, context) -> {
          await(secondCompleted);
          return List.of(SourceCandidate.of("first", 1.0));
        };
    CandidateRetriever<String, String> second =
        (query, context) -> {
          secondCompleted.countDown();
          return List.of(SourceCandidate.of("second", 2.0));
        };
    try (var pipeline =
        pipeline(
            Duration.ofSeconds(5),
            FailurePolicy.PARTIAL_RESULTS,
            List.of(required("first", first), required("second", second)))) {
      var result = pipeline.execute("camera");
      assertThat(result.candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("first", "second");
      assertThat(result.retrievalResults())
          .extracting(item -> item.retrieverName())
          .containsExactly("first", "second");
    }
  }

  @Test
  void shouldKeepSuccessfulResultWhenOptionalProviderTimesOut() throws Exception {
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CandidateRetriever<String, String> slow = blocking(release, interrupted);
    try (var pipeline =
        pipeline(
            Duration.ofSeconds(2),
            FailurePolicy.PARTIAL_RESULTS,
            List.of(
                required("search", (query, context) -> List.of(SourceCandidate.of("a", 1.0))),
                RetrieverSpec.optional("recommendations", slow, 10, Duration.ofMillis(100))))) {
      var result = pipeline.execute("camera");
      release.countDown();

      assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a");
      assertThat(result.isPartial()).isTrue();
      assertThat(result.failedStages()).containsExactly("recommendations");
      assertThat(result.timedOutStages()).containsExactly("recommendations");
      assertThat(result.retrievalResults().get(1).status()).isEqualTo(RetrievalStatus.TIMED_OUT);
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldFailWhenRequiredProviderFailsAndCancelOtherWork() throws Exception {
    var slowStarted = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CandidateRetriever<String, String> slow =
        (query, context) -> {
          slowStarted.countDown();
          return blocking(release, interrupted).retrieve(query, context);
        };
    CandidateRetriever<String, String> failing =
        (query, context) -> {
          await(slowStarted);
          throw new IllegalStateException("secret provider response");
        };

    try (var pipeline =
        pipeline(
            Duration.ofSeconds(3),
            FailurePolicy.PARTIAL_RESULTS,
            List.of(required("search", failing), optional("recommendations", slow)))) {
      assertThatThrownBy(() -> pipeline.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error -> {
                assertThat(error.reason())
                    .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED);
                assertThat(error.getMessage()).doesNotContain("secret provider response");
                assertThat(error.retrievalResults())
                    .extracting(result -> result.status())
                    .containsExactly(RetrievalStatus.FAILED, RetrievalStatus.CANCELLED);
              });
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldFailFastForOptionalProviderFailure() throws Exception {
    var slowStarted = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CandidateRetriever<String, String> slow =
        (query, context) -> {
          slowStarted.countDown();
          return blocking(release, interrupted).retrieve(query, context);
        };
    CandidateRetriever<String, String> failing =
        (query, context) -> {
          await(slowStarted);
          throw new IllegalStateException("unavailable");
        };

    try (var pipeline =
        pipeline(
            Duration.ofSeconds(3),
            FailurePolicy.FAIL_FAST,
            List.of(required("search", slow), optional("recommendations", failing)))) {
      assertThatThrownBy(() -> pipeline.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error -> {
                assertThat(error.reason()).isEqualTo(PipelineExecutionException.Reason.FAIL_FAST);
                assertThat(error.retrievalResults())
                    .extracting(result -> result.status())
                    .containsExactly(RetrievalStatus.CANCELLED, RetrievalStatus.FAILED);
              });
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldClassifyGlobalDeadlineSeparatelyAndStopWaitingForUncooperativeProvider()
      throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    CandidateRetriever<String, String> uncooperative =
        (query, context) -> {
          entered.countDown();
          while (release.getCount() > 0) {
            try {
              release.await();
            } catch (InterruptedException ignored) {
              // Deliberately model a client that does not honour interruption.
            }
          }
          return List.of(SourceCandidate.of("late", 1.0));
        };
    try (var pipeline =
            pipeline(
                Duration.ofMillis(150),
                FailurePolicy.PARTIAL_RESULTS,
                List.of(
                    required("search", (query, context) -> List.of(SourceCandidate.of("a", 1.0))),
                    optional("slow", uncooperative)));
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var execution = caller.submit(() -> pipeline.execute("camera"));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      try {
        var result = execution.get(2, TimeUnit.SECONDS);
        assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a");
        assertThat(result.retrievalResults().get(1).status())
            .isEqualTo(RetrievalStatus.DEADLINE_EXCEEDED);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void shouldRejectTotalFailureButAcceptEmptySuccessfulProvider() {
    CandidateRetriever<String, String> failing =
        (query, context) -> {
          throw new IllegalStateException("failure");
        };
    try (var allFailed =
            pipeline(
                Duration.ofSeconds(2),
                FailurePolicy.PARTIAL_RESULTS,
                List.of(optional("one", failing), optional("two", failing)));
        var emptySurvives =
            pipeline(
                Duration.ofSeconds(2),
                FailurePolicy.PARTIAL_RESULTS,
                List.of(
                    optional("one", failing), required("empty", (query, context) -> List.of())))) {
      assertThatThrownBy(() -> allFailed.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(PipelineExecutionException.Reason.ALL_RETRIEVERS_FAILED));
      var result = emptySurvives.execute("camera");
      assertThat(result.candidates()).isEmpty();
      assertThat(result.isPartial()).isTrue();
      assertThat(result.successfulStages()).containsExactly("empty");
    }
  }

  @Test
  void shouldTreatRequiredTimeoutAsFatalUnderPartialResults() {
    var release = new CountDownLatch(1);
    try (var pipeline =
        pipeline(
            Duration.ofSeconds(2),
            FailurePolicy.PARTIAL_RESULTS,
            List.of(
                RetrieverSpec.required(
                    "search", blocking(release, new CountDownLatch(1)), 10, Duration.ofMillis(100)),
                optional(
                    "recommendations",
                    (query, context) -> List.of(SourceCandidate.of("a", 1.0)))))) {
      assertThatThrownBy(() -> pipeline.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error -> {
                assertThat(error.reason())
                    .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED);
                assertThat(error.retrievalResults().getFirst().status())
                    .isEqualTo(RetrievalStatus.TIMED_OUT);
              });
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldTreatAnyTimeoutAsFatalUnderFailFast() {
    var release = new CountDownLatch(1);
    try (var pipeline =
        pipeline(
            Duration.ofSeconds(2),
            FailurePolicy.FAIL_FAST,
            List.of(
                required("search", (query, context) -> List.of(SourceCandidate.of("a", 1.0))),
                RetrieverSpec.optional(
                    "slow",
                    blocking(release, new CountDownLatch(1)),
                    10,
                    Duration.ofMillis(100))))) {
      assertThatThrownBy(() -> pipeline.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error -> {
                assertThat(error.reason()).isEqualTo(PipelineExecutionException.Reason.FAIL_FAST);
                assertThat(error.retrievalResults().get(1).status())
                    .isEqualTo(RetrievalStatus.TIMED_OUT);
              });
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldFailWhenRequiredProviderExceedsGlobalDeadline() {
    var release = new CountDownLatch(1);
    try (var pipeline =
        pipeline(
            Duration.ofMillis(150),
            FailurePolicy.PARTIAL_RESULTS,
            List.of(
                required("slow", blocking(release, new CountDownLatch(1))),
                optional("fast", (query, context) -> List.of(SourceCandidate.of("a", 1.0)))))) {
      assertThatThrownBy(() -> pipeline.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error -> {
                assertThat(error.reason())
                    .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED);
                assertThat(error.retrievalResults().getFirst().status())
                    .isEqualTo(RetrievalStatus.DEADLINE_EXCEEDED);
              });
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldGiveRetrieverTheSmallerRemainingBudget() {
    var budget = new AtomicReference<Duration>();
    CandidateRetriever<String, String> retriever =
        (query, context) -> {
          budget.set(context.timeBudget());
          return List.of();
        };
    try (var pipeline =
        pipeline(
            Duration.ofMillis(500),
            FailurePolicy.PARTIAL_RESULTS,
            List.of(RetrieverSpec.required("search", retriever, 10, Duration.ofSeconds(5))))) {
      pipeline.execute("camera");
      assertThat(budget.get()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(500));
    }
  }

  @Test
  void shouldEnforceDeadlineDuringRankingStage() throws Exception {
    var rankingStarted = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    try (var pipeline =
        RankingPipeline.<String, String>builder("discovery")
            .addRetriever(
                required("search", (query, context) -> List.of(SourceCandidate.of("a", 1.0))))
            .fusion(new FirstSeenFusion<>(), 10)
            .ranker(
                (query, candidates, context) -> {
                  rankingStarted.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                  }
                  return candidates;
                },
                10)
            .resultLimit(10)
            .timeout(Duration.ofMillis(150))
            .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("camera"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(PipelineExecutionException.Reason.GLOBAL_DEADLINE_EXCEEDED));
      assertThat(rankingStarted.getCount()).isZero();
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void shouldCloseReusableExecutorAndRejectFurtherExecutions() throws Exception {
    var started = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CandidateRetriever<String, String> slow =
        (query, context) -> {
          started.countDown();
          return blocking(release, interrupted).retrieve(query, context);
        };
    var pipeline =
        pipeline(
            Duration.ofSeconds(3), FailurePolicy.PARTIAL_RESULTS, List.of(required("slow", slow)));
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var execution = caller.submit(() -> pipeline.execute("camera"));
      assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
      pipeline.close();
      assertThatThrownBy(() -> execution.get(1, TimeUnit.SECONDS))
          .isInstanceOfSatisfying(
              ExecutionException.class,
              error ->
                  assertThat(error.getCause())
                      .isInstanceOfSatisfying(
                          PipelineExecutionException.class,
                          failure ->
                              assertThat(failure.reason())
                                  .isEqualTo(PipelineExecutionException.Reason.CLOSED)));
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> pipeline.execute("again"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
      pipeline.close();
    } finally {
      release.countDown();
      pipeline.close();
    }
  }

  @Test
  void shouldWakeWaitingCallerOnCloseEvenWhenProviderIgnoresInterruption() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CandidateRetriever<String, String> uncooperative =
        (query, context) -> {
          started.countDown();
          while (release.getCount() > 0) {
            try {
              release.await();
            } catch (InterruptedException ignored) {
              // Simulate an adapter that cannot promptly stop its provider call.
            }
          }
          return List.of();
        };
    var pipeline =
        pipeline(
            Duration.ofSeconds(5),
            FailurePolicy.PARTIAL_RESULTS,
            List.of(required("slow", uncooperative)));
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var execution = caller.submit(() -> pipeline.execute("camera"));
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      pipeline.close();
      try {
        assertThatThrownBy(() -> execution.get(1, TimeUnit.SECONDS))
            .isInstanceOfSatisfying(
                ExecutionException.class,
                error ->
                    assertThat(error.getCause())
                        .isInstanceOfSatisfying(
                            PipelineExecutionException.class,
                            failure ->
                                assertThat(failure.reason())
                                    .isEqualTo(PipelineExecutionException.Reason.CLOSED)));
      } finally {
        release.countDown();
      }
    } finally {
      release.countDown();
      pipeline.close();
    }
  }

  @Test
  void shouldReusePipelineForConcurrentRequests() throws Exception {
    var bothStarted = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    CandidateRetriever<String, String> retriever =
        (query, context) -> {
          bothStarted.countDown();
          await(release);
          return List.of(SourceCandidate.of(query, 1.0));
        };
    try (var pipeline =
            pipeline(
                Duration.ofSeconds(5),
                FailurePolicy.PARTIAL_RESULTS,
                List.of(required("search", retriever)));
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = caller.submit(() -> pipeline.execute("first"));
      var second = caller.submit(() -> pipeline.execute("second"));
      try {
        assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      assertThat(first.get(2, TimeUnit.SECONDS).candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("first");
      assertThat(second.get(2, TimeUnit.SECONDS).candidates())
          .extracting(RankedCandidate::id)
          .containsExactly("second");
    }
  }

  @Test
  void shouldRestoreCallerInterruptAndCancelProviderWork() throws Exception {
    var started = new CountDownLatch(1);
    var interruptedProvider = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var callerThread = new AtomicReference<Thread>();
    CandidateRetriever<String, String> slow =
        (query, context) -> {
          started.countDown();
          return blocking(release, interruptedProvider).retrieve(query, context);
        };
    try (var pipeline =
            pipeline(
                Duration.ofSeconds(5),
                FailurePolicy.PARTIAL_RESULTS,
                List.of(required("slow", slow)));
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var execution =
          caller.submit(
              () -> {
                callerThread.set(Thread.currentThread());
                try {
                  pipeline.execute("camera");
                  throw new AssertionError("execution should have been interrupted");
                } catch (PipelineExecutionException e) {
                  return List.of(e.reason(), Thread.currentThread().isInterrupted());
                }
              });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      callerThread.get().interrupt();
      try {
        assertThat(execution.get(2, TimeUnit.SECONDS))
            .containsExactly(PipelineExecutionException.Reason.INTERRUPTED, true);
        assertThat(interruptedProvider.await(1, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
  }

  private static CandidateRetriever<String, String> blocking(
      CountDownLatch release, CountDownLatch interrupted) {
    return (query, context) -> {
      try {
        release.await();
      } catch (InterruptedException e) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
      }
      return List.of();
    };
  }

  private static RetrieverSpec<String, String> required(
      String name, CandidateRetriever<String, String> retriever) {
    return RetrieverSpec.required(name, retriever, 10, Duration.ofSeconds(3));
  }

  private static RetrieverSpec<String, String> optional(
      String name, CandidateRetriever<String, String> retriever) {
    return RetrieverSpec.optional(name, retriever, 10, Duration.ofSeconds(3));
  }

  private static RankingPipeline<String, String> pipeline(
      Duration timeout, FailurePolicy policy, List<RetrieverSpec<String, String>> specs) {
    var builder =
        RankingPipeline.<String, String>builder("discovery")
            .fusion(new FirstSeenFusion<>(), 10)
            .ranker(new IdentityRanker<>(), 10)
            .resultLimit(10)
            .timeout(timeout)
            .failurePolicy(policy);
    specs.forEach(builder::addRetriever);
    return builder.build();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test fixture did not start");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test fixture interrupted", e);
    }
  }
}

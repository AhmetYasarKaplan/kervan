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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RankingPipelineObserverTest {

  @Test
  void reportsOrderedSuccessEventsAndActivatesScopesOnWorkerThreads() {
    var observer = new RecordingObserver();
    Thread caller = Thread.currentThread();
    CandidateRetriever<String, String> retriever =
        (query, context) -> {
          observer.action("retriever", "RETRIEVER:search");
          return List.of(SourceCandidate.of("a", 2), SourceCandidate.of("b", 1));
        };
    try (var pipeline =
        base(observer)
            .addRetriever(RetrieverSpec.required("search", retriever, 10, Duration.ofSeconds(2)))
            .fusion(
                (results, context) -> {
                  observer.action("fusion", "FUSION:custom");
                  return new FirstSeenFusion<String>().fuse(results, context);
                },
                10)
            .ranker(
                (query, candidates, context) -> {
                  observer.action("ranker", "RANKER:custom");
                  return new IdentityRanker<String, String>().rank(query, candidates, context);
                },
                10)
            .policy(
                (query, candidates, context) -> {
                  observer.action("policy", "POLICY:custom");
                  return candidates;
                })
            .resultLimit(1)
            .build()) {
      var result = pipeline.execute("sensitive query");

      assertThat(result.candidates()).extracting(RankedCandidate::id).containsExactly("a");
      assertThat(result.isPartial()).isFalse();
      assertThat(observer.sequence)
          .containsExactly(
              "pipeline:start",
              "stage:start:RETRIEVER:search:0",
              "stage:activate:RETRIEVER:search",
              "action:retriever",
              "stage:close:RETRIEVER:search",
              "stage:finished:RETRIEVER:search:SUCCESS:2",
              "stage:start:FUSION:custom:2",
              "stage:activate:FUSION:custom",
              "action:fusion",
              "stage:finished:FUSION:custom:SUCCESS:2",
              "stage:close:FUSION:custom",
              "stage:start:RANKER:custom:2",
              "stage:activate:RANKER:custom",
              "action:ranker",
              "stage:finished:RANKER:custom:SUCCESS:2",
              "stage:close:RANKER:custom",
              "stage:start:POLICY:custom:2",
              "stage:activate:POLICY:custom",
              "action:policy",
              "stage:finished:POLICY:custom:SUCCESS:2",
              "stage:close:POLICY:custom",
              "pipeline:succeeded:false:1");
      assertThat(observer.actionScopes)
          .containsEntry("retriever", true)
          .containsEntry("fusion", true)
          .containsEntry("ranker", true)
          .containsEntry("policy", true);
      assertThat(observer.scopes)
          .hasSize(8)
          .allSatisfy(
              scope -> {
                assertThat(scope.thread()).isNotSameAs(caller);
                assertThat(scope.thread().isVirtual()).isTrue();
              });
      assertThat(observer.scopes)
          .extracting(ScopeEvent::phase)
          .containsExactly(
              "activate", "close", "activate", "close", "activate", "close", "activate", "close");
      assertThat(observer.finishes)
          .allSatisfy(finish -> assertThat(finish.duration().isNegative()).isFalse());
      assertThat(observer.success).isNotNull();
      assertThat(observer.success.duration().isNegative()).isFalse();
      assertThat(observer.success.outputCandidates()).isEqualTo(1);
      assertThat(observer.failure).isNull();
      assertThat(observer.pipelineThread).isSameAs(caller);
      assertThat(observer.terminalThread).isSameAs(caller);
    }
  }

  @Test
  void reportsPartialResultAndAuthoritativeRetrieverTimeoutOnlyOnce() throws Exception {
    var observer = new RecordingObserver();
    var slowStarted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var slowDone = new CountDownLatch(1);
    CandidateRetriever<String, String> slow = uncooperative(slowStarted, release, slowDone);
    CandidateRetriever<String, String> fast =
        (query, context) -> {
          await(slowStarted);
          return List.of(SourceCandidate.of("a", 1));
        };

    try (var pipeline =
        base(observer)
            .addRetriever(RetrieverSpec.required("search", fast, 10, Duration.ofSeconds(1)))
            .addRetriever(RetrieverSpec.optional("slow", slow, 10, Duration.ofMillis(100)))
            .build()) {
      var result = pipeline.execute("query");
      assertThat(result.isPartial()).isTrue();
      assertThat(result.retrievalResults())
          .extracting(item -> item.status())
          .containsExactly(RetrievalStatus.SUCCESS, RetrievalStatus.TIMED_OUT);
      assertThat(observer.success).isNotNull();
      assertThat(observer.success.partial()).isTrue();
      assertThat(observer.success.duration().isNegative()).isFalse();
      assertThat(observer.success.outputCandidates()).isEqualTo(1);
      assertThat(observer.failure).isNull();
      assertThat(observer.finishes)
          .extracting(finish -> finish.kind() + ":" + finish.name() + ":" + finish.status())
          .containsExactlyInAnyOrder(
              "RETRIEVER:search:SUCCESS",
              "RETRIEVER:slow:TIMED_OUT",
              "FUSION:rrf:SUCCESS",
              "RANKER:identity:SUCCESS",
              "POLICY:identity:SUCCESS");
      assertThat(observer.finishes)
          .filteredOn(finish -> finish.kind() == PipelineObserver.StageKind.RETRIEVER)
          .extracting(StageFinish::outputCandidates)
          .containsExactlyInAnyOrder(1, 0);
      assertThat(observer.starts)
          .extracting(start -> start.kind() + ":" + start.inputCandidates())
          .contains("FUSION:1", "RANKER:1", "POLICY:1");
      release.countDown();
      assertThat(slowDone.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(observer.finishes)
          .filteredOn(finish -> finish.name().equals("slow"))
          .singleElement()
          .satisfies(
              finish ->
                  assertThat(finish.status()).isEqualTo(PipelineObserver.StageStatus.TIMED_OUT));
    } finally {
      release.countDown();
    }
  }

  @Test
  void reportsRequiredFailureAndCancellationWithoutLateDuplicateEvents() throws Exception {
    var observer = new RecordingObserver();
    var slowStarted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var slowDone = new CountDownLatch(1);
    CandidateRetriever<String, String> slow = uncooperative(slowStarted, release, slowDone);
    CandidateRetriever<String, String> failing =
        (query, context) -> {
          await(slowStarted);
          throw new IllegalStateException("provider secret");
        };

    try (var pipeline =
        base(observer)
            .addRetriever(RetrieverSpec.required("search", failing, 10, Duration.ofSeconds(2)))
            .addRetriever(RetrieverSpec.optional("slow", slow, 10, Duration.ofSeconds(2)))
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("query"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error -> {
                assertThat(error.reason())
                    .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED);
                assertThat(error.retrievalResults())
                    .extracting(item -> item.status())
                    .containsExactly(RetrievalStatus.FAILED, RetrievalStatus.CANCELLED);
              });
      assertThat(observer.failure.reason())
          .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED);
      assertThat(observer.failure.duration().isNegative()).isFalse();
      assertThat(observer.success).isNull();
      assertThat(observer.finishes)
          .extracting(finish -> finish.kind() + ":" + finish.name() + ":" + finish.status())
          .containsExactlyInAnyOrder("RETRIEVER:search:FAILED", "RETRIEVER:slow:CANCELLED");
      assertThat(observer.starts)
          .extracting(StageStart::kind)
          .containsOnly(PipelineObserver.StageKind.RETRIEVER);
      release.countDown();
      assertThat(slowDone.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(observer.finishes)
          .filteredOn(finish -> finish.name().equals("slow"))
          .singleElement()
          .satisfies(
              finish ->
                  assertThat(finish.status()).isEqualTo(PipelineObserver.StageStatus.CANCELLED));
    } finally {
      release.countDown();
    }
  }

  @Test
  void reportsFailedFusionBeforeTerminalFailureAndDoesNotStartRanker() {
    var observer = new RecordingObserver();
    try (var pipeline =
        base(observer)
            .addRetriever(
                RetrieverSpec.required(
                    "search",
                    (query, context) -> List.of(SourceCandidate.of("a", 1)),
                    10,
                    Duration.ofSeconds(2)))
            .fusion(
                (results, context) -> {
                  throw new IllegalStateException("fusion failed");
                },
                10)
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("query"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED));
      assertThat(observer.finishes)
          .extracting(
              finish -> finish.kind() + ":" + finish.status() + ":" + finish.outputCandidates())
          .containsExactly("RETRIEVER:SUCCESS:1", "FUSION:FAILED:0");
      assertThat(observer.starts)
          .extracting(StageStart::kind)
          .containsExactly(PipelineObserver.StageKind.RETRIEVER, PipelineObserver.StageKind.FUSION);
      assertThat(observer.failure.reason())
          .isEqualTo(PipelineExecutionException.Reason.STAGE_FAILED);
      assertThat(observer.success).isNull();
    }
  }

  @Test
  void observerRuntimeExceptionsNeverChangeSuccessfulOrFailedPipelineOutcomes() {
    PipelineObserver failingAtStart =
        name -> {
          throw new IllegalStateException("startPipeline");
        };
    PipelineObserver failingAtStartStage =
        name ->
            new PipelineObserver.Execution() {
              @Override
              public PipelineObserver.Stage startStage(
                  PipelineObserver.StageKind kind, String stableName, int inputCandidates) {
                throw new IllegalStateException("startStage");
              }

              @Override
              public void succeeded(boolean partial, Duration duration, int outputCandidates) {
                throw new IllegalStateException("succeeded");
              }
            };
    PipelineObserver failingAtActivate =
        name ->
            new PipelineObserver.Execution() {
              @Override
              public PipelineObserver.Stage startStage(
                  PipelineObserver.StageKind kind, String stableName, int inputCandidates) {
                return new PipelineObserver.Stage() {
                  @Override
                  public PipelineObserver.Scope activate() {
                    throw new IllegalStateException("activate");
                  }

                  @Override
                  public void finished(
                      PipelineObserver.StageStatus status,
                      Duration duration,
                      int outputCandidates) {
                    throw new IllegalStateException("finished");
                  }
                };
              }
            };
    PipelineObserver failingAtCloseAndFinish =
        name ->
            new PipelineObserver.Execution() {
              @Override
              public PipelineObserver.Stage startStage(
                  PipelineObserver.StageKind kind, String stableName, int inputCandidates) {
                return new PipelineObserver.Stage() {
                  @Override
                  public PipelineObserver.Scope activate() {
                    return () -> {
                      throw new IllegalStateException("close");
                    };
                  }

                  @Override
                  public void finished(
                      PipelineObserver.StageStatus status,
                      Duration duration,
                      int outputCandidates) {
                    throw new IllegalStateException("finished");
                  }
                };
              }

              @Override
              public void succeeded(boolean partial, Duration duration, int outputCandidates) {
                throw new IllegalStateException("succeeded");
              }
            };

    for (PipelineObserver observer :
        List.of(failingAtStart, failingAtStartStage, failingAtActivate, failingAtCloseAndFinish)) {
      try (var pipeline =
          base(observer)
              .addRetriever(
                  RetrieverSpec.required(
                      "search",
                      (query, context) -> List.of(SourceCandidate.of("a", 1)),
                      10,
                      Duration.ofSeconds(2)))
              .build()) {
        assertThat(pipeline.execute("query").candidates())
            .extracting(RankedCandidate::id)
            .containsExactly("a");
      }
    }

    PipelineObserver failingAtFailure =
        name ->
            new PipelineObserver.Execution() {
              @Override
              public void failed(PipelineExecutionException.Reason reason, Duration duration) {
                throw new IllegalStateException("failed");
              }
            };
    try (var pipeline =
        base(failingAtFailure)
            .addRetriever(
                RetrieverSpec.required(
                    "search",
                    (query, context) -> {
                      throw new IllegalArgumentException("provider failure");
                    },
                    10,
                    Duration.ofSeconds(2)))
            .build()) {
      assertThatThrownBy(() -> pipeline.execute("query"))
          .isInstanceOfSatisfying(
              PipelineExecutionException.class,
              error ->
                  assertThat(error.reason())
                      .isEqualTo(PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED));
    }
  }

  private static RankingPipeline.Builder<String, String> base(PipelineObserver observer) {
    return RankingPipeline.<String, String>builder("observed")
        .observer(observer)
        .ranker(new IdentityRanker<>(), 10)
        .resultLimit(10)
        .timeout(Duration.ofSeconds(2))
        .failurePolicy(FailurePolicy.PARTIAL_RESULTS);
  }

  private static CandidateRetriever<String, String> uncooperative(
      CountDownLatch started, CountDownLatch release, CountDownLatch finished) {
    return (query, context) -> {
      started.countDown();
      try {
        while (release.getCount() > 0) {
          try {
            release.await();
          } catch (InterruptedException ignored) {
            // Model a provider that keeps working after best-effort cancellation.
          }
        }
        return List.of(SourceCandidate.of("late", 1));
      } finally {
        finished.countDown();
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("fixture did not start");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fixture interrupted", e);
    }
  }

  private record StageStart(
      PipelineObserver.StageKind kind, String name, int inputCandidates, Thread thread) {}

  private record StageFinish(
      PipelineObserver.StageKind kind,
      String name,
      PipelineObserver.StageStatus status,
      Duration duration,
      int outputCandidates,
      Thread thread) {}

  private record ScopeEvent(String phase, String stage, Thread thread) {}

  private record Success(boolean partial, Duration duration, int outputCandidates) {}

  private record Failure(PipelineExecutionException.Reason reason, Duration duration) {}

  private static final class RecordingObserver implements PipelineObserver {
    private final List<String> sequence = new CopyOnWriteArrayList<>();
    private final List<StageStart> starts = new CopyOnWriteArrayList<>();
    private final List<StageFinish> finishes = new CopyOnWriteArrayList<>();
    private final List<ScopeEvent> scopes = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> actionScopes = new ConcurrentHashMap<>();
    private final ThreadLocal<String> activeScope = new ThreadLocal<>();
    private volatile Thread pipelineThread;
    private volatile Thread terminalThread;
    private volatile Success success;
    private volatile Failure failure;

    private void action(String name, String expectedScope) {
      sequence.add("action:" + name);
      actionScopes.put(name, expectedScope.equals(activeScope.get()));
    }

    @Override
    public Execution startPipeline(String pipelineName) {
      pipelineThread = Thread.currentThread();
      sequence.add("pipeline:start");
      return new Execution() {
        @Override
        public Stage startStage(StageKind kind, String stableName, int inputCandidates) {
          String key = kind + ":" + stableName;
          starts.add(new StageStart(kind, stableName, inputCandidates, Thread.currentThread()));
          sequence.add("stage:start:" + key + ":" + inputCandidates);
          return new Stage() {
            @Override
            public Scope activate() {
              activeScope.set(key);
              scopes.add(new ScopeEvent("activate", key, Thread.currentThread()));
              sequence.add("stage:activate:" + key);
              return () -> {
                scopes.add(new ScopeEvent("close", key, Thread.currentThread()));
                sequence.add("stage:close:" + key);
                activeScope.remove();
              };
            }

            @Override
            public void finished(StageStatus status, Duration duration, int outputCandidates) {
              finishes.add(
                  new StageFinish(
                      kind,
                      stableName,
                      status,
                      duration,
                      outputCandidates,
                      Thread.currentThread()));
              sequence.add("stage:finished:" + key + ":" + status + ":" + outputCandidates);
            }
          };
        }

        @Override
        public void succeeded(boolean partial, Duration duration, int outputCandidates) {
          success = new Success(partial, duration, outputCandidates);
          terminalThread = Thread.currentThread();
          sequence.add("pipeline:succeeded:" + partial + ":" + outputCandidates);
        }

        @Override
        public void failed(PipelineExecutionException.Reason reason, Duration duration) {
          failure = new Failure(reason, duration);
          terminalThread = Thread.currentThread();
          sequence.add("pipeline:failed:" + reason);
        }
      };
    }
  }
}

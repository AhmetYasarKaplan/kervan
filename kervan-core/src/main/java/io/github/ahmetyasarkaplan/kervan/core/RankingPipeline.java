package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.FusionContext;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalStatus;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Coordinates independent providers concurrently, then fuses and ranks their canonical candidates.
 * Each pipeline owns one reusable virtual-thread executor. Close it when no longer needed.
 */
public final class RankingPipeline<Q, ID> implements AutoCloseable {

  // A small bounded interval lets an already successful partial retrieval be assembled at the
  // deadline. Custom fusion/ranking work is still cancelled if it exceeds this interval.
  private static final long MAX_FINISH_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(25);

  private final String name;
  private final List<RetrieverSpec<Q, ID>> retrievers;
  private final FusionStrategy<ID> fusion;
  private final int fusionLimit;
  private final Ranker<Q, ID> ranker;
  private final int rankingLimit;
  private final int resultLimit;
  private final long timeoutNanos;
  private final FailurePolicy failurePolicy;
  private final PipelineObserver observer;
  private final Map<String, Double> sourceWeights;
  private final ExecutorService executor;
  private final Object lifecycleLock = new Object();
  private final Set<Future<?>> activeFutures = new HashSet<>();
  private final Set<Runnable> closeSignals = new HashSet<>();
  private volatile boolean closed;

  private RankingPipeline(Builder<Q, ID> builder) {
    name = builder.name;
    retrievers = List.copyOf(builder.retrievers);
    if (builder.fusion == null) {
      fusion = new ReciprocalRankFusion<>(60);
      long sourceCapacity = 0;
      for (RetrieverSpec<Q, ID> spec : retrievers) {
        sourceCapacity += spec.candidateLimit();
      }
      fusionLimit = (int) Math.min(sourceCapacity, Integer.MAX_VALUE);
    } else {
      fusion = builder.fusion;
      fusionLimit = builder.fusionLimit;
    }
    ranker = builder.ranker;
    rankingLimit = builder.rankingLimit;
    resultLimit = builder.resultLimit;
    timeoutNanos = builder.timeout.toNanos();
    failurePolicy = builder.failurePolicy;
    observer = builder.observer;
    Map<String, Double> weights = new LinkedHashMap<>();
    for (RetrieverSpec<Q, ID> spec : retrievers) {
      weights.put(spec.name(), spec.weight());
    }
    sourceWeights = Map.copyOf(weights);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  public static <Q, ID> Builder<Q, ID> builder(String name) {
    return new Builder<>(name);
  }

  /**
   * Runs providers concurrently and returns results in registration order. Cancellation interrupts
   * virtual threads on a best-effort basis; a provider may continue remote work after this method
   * stops waiting. Concurrent calls to this method are supported.
   */
  public PipelineResult<ID> execute(Q query) {
    long pipelineStart = System.nanoTime();
    PipelineObserver.Execution observation = startObservation(observer, name);
    try {
      PipelineResult<ID> result = executeObserved(query, pipelineStart, observation);
      safely(
          () ->
              observation.succeeded(
                  result.isPartial(), result.duration(), result.candidates().size()));
      return result;
    } catch (RuntimeException failure) {
      PipelineExecutionException.Reason reason =
          failure instanceof PipelineExecutionException pipelineFailure
              ? pipelineFailure.reason()
              : closed
                  ? PipelineExecutionException.Reason.CLOSED
                  : PipelineExecutionException.Reason.STAGE_FAILED;
      safely(() -> observation.failed(reason, elapsed(pipelineStart, System.nanoTime())));
      throw failure;
    }
  }

  private PipelineResult<ID> executeObserved(
      Q query, long pipelineStart, PipelineObserver.Execution observation) {
    var completionQueue = new LinkedBlockingQueue<Future<Invocation<ID>>>();
    var completions = new ExecutorCompletionService<Invocation<ID>>(executor, completionQueue);
    FutureTask<Invocation<ID>> closeSignal = new FutureTask<>(() -> null);
    closeSignal.run();
    Runnable wakeOnClose = () -> completionQueue.offer(closeSignal);
    List<Job<Q, ID>> jobs = new ArrayList<>(retrievers.size());

    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("pipeline is closed");
      }
      closeSignals.add(wakeOnClose);
      try {
        for (RetrieverSpec<Q, ID> spec : retrievers) {
          long submittedAt = System.nanoTime();
          PipelineObserver.Stage stage =
              startStage(observation, PipelineObserver.StageKind.RETRIEVER, spec.name(), 0);
          Future<Invocation<ID>> future =
              completions.submit(() -> invoke(spec, query, submittedAt, pipelineStart, stage));
          jobs.add(new Job<>(spec, submittedAt, future, stage));
          activeFutures.add(future);
        }
      } catch (RejectedExecutionException e) {
        cancelUnfinished(jobs);
        jobs.forEach(job -> activeFutures.remove(job.future));
        closeSignals.remove(wakeOnClose);
        throw new IllegalStateException("pipeline could not submit provider work", e);
      }
    }

    try {
      while (jobs.stream().anyMatch(job -> job.result == null)) {
        if (closed) {
          throw abort(PipelineExecutionException.Reason.CLOSED, jobs, pipelineStart);
        }

        // Inspect in registration order so task completion order never changes result ordering.
        PipelineExecutionException.Reason fatal = null;
        for (Job<Q, ID> job : jobs) {
          if (job.result == null && job.future.isDone()) {
            complete(job, pipelineStart);
            PipelineExecutionException.Reason reason = fatalReason(job);
            if (fatal == null) {
              fatal = reason;
            }
          }
        }
        if (fatal != null) {
          throw abort(fatal, jobs, pipelineStart);
        }

        long now = System.nanoTime();
        for (Job<Q, ID> job : jobs) {
          if (job.result == null) {
            RetrievalStatus expiration = expiration(job, pipelineStart, now);
            if (expiration != null) {
              if (job.future.isDone() || !job.future.cancel(true)) {
                complete(job, pipelineStart);
              } else {
                job.result = timedResult(job, expiration, now);
                report(job);
              }
              PipelineExecutionException.Reason reason = fatalReason(job);
              if (fatal == null) {
                fatal = reason;
              }
            }
          }
        }
        if (fatal != null) {
          throw abort(fatal, jobs, pipelineStart);
        }

        if (jobs.stream().anyMatch(job -> job.result == null)) {
          completions.poll(nextWaitNanos(jobs, pipelineStart, now), TimeUnit.NANOSECONDS);
        }
      }

      List<RetrievalResult<ID>> results = results(jobs);
      if (results.stream().noneMatch(result -> usable(result.status()))) {
        throw new PipelineExecutionException(
            name, PipelineExecutionException.Reason.ALL_RETRIEVERS_FAILED, results);
      }

      List<RankedCandidate<ID>> candidates =
          finishWithinDeadline(query, results, pipelineStart, observation);
      return new PipelineResult<>(
          name, candidates, elapsed(pipelineStart, System.nanoTime()), results);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw abort(PipelineExecutionException.Reason.INTERRUPTED, jobs, pipelineStart);
    } finally {
      cancelUnfinished(jobs);
      synchronized (lifecycleLock) {
        jobs.forEach(job -> activeFutures.remove(job.future));
        closeSignals.remove(wakeOnClose);
      }
    }
  }

  private Invocation<ID> invoke(
      RetrieverSpec<Q, ID> spec,
      Q query,
      long submittedAt,
      long pipelineStart,
      PipelineObserver.Stage stage) {
    PipelineObserver.Scope scope = activate(stage);
    try {
      long startedAt = System.nanoTime();
      long remaining =
          Math.min(
              spec.timeout().toNanos() - (startedAt - submittedAt),
              timeoutNanos - (startedAt - pipelineStart));
      if (remaining <= 0) {
        RetrievalStatus status = expiration(spec, submittedAt, pipelineStart, startedAt);
        return new Invocation<>(
            timedResult(spec.name(), status, submittedAt, startedAt), startedAt);
      }

      try {
        List<SourceCandidate<ID>> raw =
            spec.retriever()
                .retrieve(
                    query, RetrievalContext.of(spec.candidateLimit(), Duration.ofNanos(remaining)));
        List<SourceHit<ID>> hits = sanitize(raw, spec.candidateLimit());
        long completedAt = System.nanoTime();
        return new Invocation<>(
            RetrievalResult.success(spec.name(), hits, elapsed(submittedAt, completedAt)),
            completedAt);
      } catch (Exception e) {
        long completedAt = System.nanoTime();
        return new Invocation<>(
            RetrievalResult.failure(spec.name(), elapsed(submittedAt, completedAt)), completedAt);
      }
    } finally {
      scope.close();
    }
  }

  private void complete(Job<Q, ID> job, long pipelineStart) throws InterruptedException {
    try {
      Invocation<ID> invocation = job.future.get();
      RetrievalStatus expiration = expiration(job, pipelineStart, invocation.completedAt());
      job.result =
          expiration == null
              ? invocation.result()
              : timedResult(job, expiration, invocation.completedAt());
    } catch (CancellationException e) {
      job.result =
          RetrievalResult.cancelled(job.spec.name(), elapsed(job.submittedAt, System.nanoTime()));
    } catch (ExecutionException e) {
      job.result =
          RetrievalResult.failure(job.spec.name(), elapsed(job.submittedAt, System.nanoTime()));
    }
    report(job);
  }

  private RetrievalStatus expiration(Job<Q, ID> job, long pipelineStart, long at) {
    return expiration(job.spec, job.submittedAt, pipelineStart, at);
  }

  private RetrievalStatus expiration(
      RetrieverSpec<Q, ID> spec, long submittedAt, long pipelineStart, long at) {
    long localLimit = spec.timeout().toNanos();
    long globalRemainingAtSubmission = timeoutNanos - (submittedAt - pipelineStart);
    boolean localExpired = at - submittedAt >= localLimit;
    boolean globalExpired = at - pipelineStart >= timeoutNanos;
    if (globalExpired && globalRemainingAtSubmission <= localLimit) {
      return RetrievalStatus.DEADLINE_EXCEEDED;
    }
    if (localExpired) {
      return RetrievalStatus.TIMED_OUT;
    }
    return globalExpired ? RetrievalStatus.DEADLINE_EXCEEDED : null;
  }

  private static <Q, ID> RetrievalResult<ID> timedResult(
      Job<Q, ID> job, RetrievalStatus status, long at) {
    return timedResult(job.spec.name(), status, job.submittedAt, at);
  }

  private static <ID> RetrievalResult<ID> timedResult(
      String name, RetrievalStatus status, long submittedAt, long at) {
    Duration duration = elapsed(submittedAt, at);
    return status == RetrievalStatus.DEADLINE_EXCEEDED
        ? RetrievalResult.deadlineExceeded(name, duration)
        : RetrievalResult.timedOut(name, duration);
  }

  private PipelineExecutionException.Reason fatalReason(Job<Q, ID> job) {
    if (closed || job.result.status() == RetrievalStatus.CANCELLED) {
      return PipelineExecutionException.Reason.CLOSED;
    }
    if (usable(job.result.status())) {
      return null;
    }
    if (failurePolicy == FailurePolicy.FAIL_FAST) {
      return PipelineExecutionException.Reason.FAIL_FAST;
    }
    return job.spec.required() ? PipelineExecutionException.Reason.REQUIRED_RETRIEVER_FAILED : null;
  }

  private PipelineExecutionException abort(
      PipelineExecutionException.Reason reason, List<Job<Q, ID>> jobs, long pipelineStart) {
    for (Job<Q, ID> job : jobs) {
      if (job.result == null) {
        if (job.future.isDone() || !job.future.cancel(true)) {
          try {
            complete(job, pipelineStart);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        if (job.result == null) {
          job.result =
              RetrievalResult.cancelled(
                  job.spec.name(), elapsed(job.submittedAt, System.nanoTime()));
          report(job);
        }
      }
    }
    return new PipelineExecutionException(name, reason, results(jobs));
  }

  private List<RankedCandidate<ID>> finishWithinDeadline(
      Q query,
      List<RetrievalResult<ID>> results,
      long pipelineStart,
      PipelineObserver.Execution observation)
      throws InterruptedException {
    long remaining = finishRemaining(pipelineStart);
    if (remaining <= 0) {
      throw new PipelineExecutionException(
          name, PipelineExecutionException.Reason.GLOBAL_DEADLINE_EXCEEDED, results);
    }

    Future<FinishOutcome<ID>> finish;
    synchronized (lifecycleLock) {
      if (closed) {
        throw new PipelineExecutionException(
            name, PipelineExecutionException.Reason.CLOSED, results);
      }
      finish =
          executor.submit(
              () -> {
                List<RankedCandidate<ID>> candidates = finish(query, results, observation);
                return new FinishOutcome<>(candidates, System.nanoTime());
              });
      activeFutures.add(finish);
    }
    try {
      remaining = finishRemaining(pipelineStart);
      FinishOutcome<ID> outcome = finish.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
      if (finishRemaining(pipelineStart, outcome.completedAt()) < 0) {
        throw new PipelineExecutionException(
            name, PipelineExecutionException.Reason.GLOBAL_DEADLINE_EXCEEDED, results);
      }
      return outcome.candidates();
    } catch (TimeoutException e) {
      finish.cancel(true);
      throw new PipelineExecutionException(
          name, PipelineExecutionException.Reason.GLOBAL_DEADLINE_EXCEEDED, results);
    } catch (ExecutionException e) {
      throw new PipelineExecutionException(
          name, PipelineExecutionException.Reason.STAGE_FAILED, results);
    } catch (CancellationException e) {
      throw new PipelineExecutionException(name, PipelineExecutionException.Reason.CLOSED, results);
    } catch (InterruptedException e) {
      finish.cancel(true);
      throw e;
    } finally {
      synchronized (lifecycleLock) {
        activeFutures.remove(finish);
      }
    }
  }

  private List<RankedCandidate<ID>> finish(
      Q query, List<RetrievalResult<ID>> results, PipelineObserver.Execution observation) {
    int inputCount =
        (int)
            Math.min(
                results.stream().mapToLong(result -> result.candidates().size()).sum(),
                Integer.MAX_VALUE);
    PipelineObserver.Stage fusionStage =
        startStage(observation, PipelineObserver.StageKind.FUSION, fusionType(), inputCount);
    List<RankedCandidate<ID>> fused =
        observeStage(
            fusionStage,
            () ->
                limitUnique(
                    fusion.fuse(results, FusionContext.of(fusionLimit, sourceWeights)),
                    fusionLimit));
    PipelineObserver.Stage rankerStage =
        startStage(observation, PipelineObserver.StageKind.RANKER, rankerType(), fused.size());
    List<RankedCandidate<ID>> ranked =
        observeStage(
            rankerStage,
            () ->
                limitUnique(
                    ranker.rank(query, fused, RankingContext.of(rankingLimit)), rankingLimit));
    return List.copyOf(ranked.subList(0, Math.min(ranked.size(), resultLimit)));
  }

  private String fusionType() {
    if (fusion instanceof ReciprocalRankFusion<?>) {
      return "rrf";
    }
    if (fusion instanceof WeightedMinMaxFusion<?>) {
      return "weighted-min-max";
    }
    return fusion instanceof FirstSeenFusion<?> ? "first-seen" : "custom";
  }

  private String rankerType() {
    return ranker instanceof IdentityRanker<?, ?> ? "identity" : "custom";
  }

  private List<RankedCandidate<ID>> observeStage(
      PipelineObserver.Stage stage, Supplier<List<RankedCandidate<ID>>> action) {
    long startedAt = System.nanoTime();
    PipelineObserver.Scope scope = activate(stage);
    try {
      List<RankedCandidate<ID>> result = action.get();
      stageFinished(
          stage,
          PipelineObserver.StageStatus.SUCCESS,
          elapsed(startedAt, System.nanoTime()),
          result.size());
      return result;
    } catch (RuntimeException | Error failure) {
      stageFinished(
          stage, PipelineObserver.StageStatus.FAILED, elapsed(startedAt, System.nanoTime()), 0);
      throw failure;
    } finally {
      scope.close();
    }
  }

  private static PipelineObserver.Execution startObservation(
      PipelineObserver observer, String pipelineName) {
    try {
      PipelineObserver.Execution execution = observer.startPipeline(pipelineName);
      return execution == null ? PipelineObserver.Execution.NOOP : execution;
    } catch (RuntimeException ignored) {
      return PipelineObserver.Execution.NOOP;
    }
  }

  private static PipelineObserver.Stage startStage(
      PipelineObserver.Execution observation,
      PipelineObserver.StageKind kind,
      String stableName,
      int inputCandidates) {
    try {
      PipelineObserver.Stage stage = observation.startStage(kind, stableName, inputCandidates);
      return stage == null ? PipelineObserver.Stage.NOOP : stage;
    } catch (RuntimeException ignored) {
      return PipelineObserver.Stage.NOOP;
    }
  }

  private static PipelineObserver.Scope activate(PipelineObserver.Stage stage) {
    try {
      PipelineObserver.Scope scope = stage.activate();
      return () ->
          safely(
              () -> {
                if (scope != null) {
                  scope.close();
                }
              });
    } catch (RuntimeException ignored) {
      return () -> {};
    }
  }

  private static void stageFinished(
      PipelineObserver.Stage stage,
      PipelineObserver.StageStatus status,
      Duration duration,
      int outputCandidates) {
    safely(() -> stage.finished(status, duration, outputCandidates));
  }

  private static void safely(Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException ignored) {
      // Observation must not alter pipeline behavior.
    }
  }

  private static <Q, ID> void report(Job<Q, ID> job) {
    if (job.reported || job.result == null) {
      return;
    }
    job.reported = true;
    RetrievalResult<ID> result = job.result;
    stageFinished(
        job.stage,
        PipelineObserver.StageStatus.valueOf(result.status().name()),
        result.duration(),
        result.candidates().size());
  }

  private long nextWaitNanos(List<Job<Q, ID>> jobs, long pipelineStart, long now) {
    long wait = Math.max(1, timeoutNanos - (now - pipelineStart));
    for (Job<Q, ID> job : jobs) {
      if (job.result == null) {
        wait = Math.min(wait, Math.max(1, job.spec.timeout().toNanos() - (now - job.submittedAt)));
      }
    }
    return wait;
  }

  private long finishRemaining(long pipelineStart) {
    return finishRemaining(pipelineStart, System.nanoTime());
  }

  private long finishRemaining(long pipelineStart, long at) {
    long remaining = timeoutNanos - (at - pipelineStart);
    long grace = Math.min(MAX_FINISH_GRACE_NANOS, timeoutNanos / 4);
    return remaining > Long.MAX_VALUE - grace ? Long.MAX_VALUE : remaining + grace;
  }

  private static boolean usable(RetrievalStatus status) {
    return status == RetrievalStatus.SUCCESS || status == RetrievalStatus.EMPTY;
  }

  private static <Q, ID> List<RetrievalResult<ID>> results(List<Job<Q, ID>> jobs) {
    return jobs.stream().map(job -> job.result).toList();
  }

  private static <Q, ID> void cancelUnfinished(List<Job<Q, ID>> jobs) {
    for (Job<Q, ID> job : jobs) {
      if (job.result == null) {
        job.future.cancel(true);
      }
    }
  }

  private static Duration elapsed(long start, long end) {
    return Duration.ofNanos(Math.max(0, end - start));
  }

  private static <ID> List<SourceHit<ID>> sanitize(
      List<SourceCandidate<ID>> raw, int candidateLimit) {
    Objects.requireNonNull(raw, "retriever must not return null");
    var unique = new LinkedHashMap<ID, SourceHit<ID>>();
    for (int index = 0; index < raw.size(); index++) {
      SourceCandidate<ID> candidate =
          Objects.requireNonNull(raw.get(index), "retriever must not return null candidates");
      if (unique.size() < candidateLimit) {
        unique.putIfAbsent(candidate.id(), new SourceHit<>(candidate, index + 1));
      }
    }
    return List.copyOf(unique.values());
  }

  private static <ID> List<RankedCandidate<ID>> limitUnique(
      List<RankedCandidate<ID>> candidates, int limit) {
    List<RankedCandidate<ID>> snapshot =
        List.copyOf(Objects.requireNonNull(candidates, "stage must not return null"));
    Set<ID> ids = new HashSet<>();
    for (RankedCandidate<ID> candidate : snapshot) {
      if (!ids.add(candidate.id())) {
        throw new IllegalArgumentException("stage must not return duplicate candidate IDs");
      }
    }
    return List.copyOf(snapshot.subList(0, Math.min(snapshot.size(), limit)));
  }

  /**
   * Stops accepting requests and interrupts in-flight work without waiting for uncooperative code.
   */
  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (!closed) {
        closed = true;
        activeFutures.forEach(future -> future.cancel(true));
        closeSignals.forEach(Runnable::run);
        executor.shutdownNow();
      }
    }
  }

  private record Invocation<ID>(RetrievalResult<ID> result, long completedAt) {}

  private record FinishOutcome<ID>(List<RankedCandidate<ID>> candidates, long completedAt) {}

  private static final class Job<Q, ID> {
    private final RetrieverSpec<Q, ID> spec;
    private final long submittedAt;
    private final Future<Invocation<ID>> future;
    private final PipelineObserver.Stage stage;
    private RetrievalResult<ID> result;
    private boolean reported;

    private Job(
        RetrieverSpec<Q, ID> spec,
        long submittedAt,
        Future<Invocation<ID>> future,
        PipelineObserver.Stage stage) {
      this.spec = spec;
      this.submittedAt = submittedAt;
      this.future = future;
      this.stage = stage;
    }
  }

  public static final class Builder<Q, ID> {
    private final String name;
    private final List<RetrieverSpec<Q, ID>> retrievers = new ArrayList<>();
    private FusionStrategy<ID> fusion;
    private int fusionLimit;
    private Ranker<Q, ID> ranker;
    private int rankingLimit;
    private int resultLimit;
    private Duration timeout;
    private FailurePolicy failurePolicy;
    private PipelineObserver observer = PipelineObserver.noOp();

    private Builder(String name) {
      this.name = Objects.requireNonNull(name, "name must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
    }

    public Builder<Q, ID> addRetriever(RetrieverSpec<Q, ID> spec) {
      retrievers.add(Objects.requireNonNull(spec, "spec must not be null"));
      return this;
    }

    public Builder<Q, ID> fusion(FusionStrategy<ID> strategy, int candidateLimit) {
      fusion = Objects.requireNonNull(strategy, "strategy must not be null");
      fusionLimit = positive(candidateLimit, "fusion candidateLimit");
      return this;
    }

    public Builder<Q, ID> ranker(Ranker<Q, ID> implementation, int candidateLimit) {
      ranker = Objects.requireNonNull(implementation, "ranker must not be null");
      rankingLimit = positive(candidateLimit, "ranking candidateLimit");
      return this;
    }

    public Builder<Q, ID> resultLimit(int candidateLimit) {
      resultLimit = positive(candidateLimit, "resultLimit");
      return this;
    }

    public Builder<Q, ID> timeout(Duration timeout) {
      Objects.requireNonNull(timeout, "timeout must not be null");
      if (timeout.isZero() || timeout.isNegative()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
      try {
        timeout.toNanos();
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException("timeout is too large", e);
      }
      this.timeout = timeout;
      return this;
    }

    public Builder<Q, ID> failurePolicy(FailurePolicy policy) {
      failurePolicy = Objects.requireNonNull(policy, "failurePolicy must not be null");
      return this;
    }

    /** Installs optional execution instrumentation without changing pipeline outcomes. */
    public Builder<Q, ID> observer(PipelineObserver observer) {
      this.observer = Objects.requireNonNull(observer, "observer must not be null");
      return this;
    }

    public RankingPipeline<Q, ID> build() {
      if (retrievers.isEmpty()) {
        throw new IllegalStateException("at least one retriever is required");
      }
      Set<String> names = new HashSet<>();
      for (RetrieverSpec<Q, ID> spec : retrievers) {
        if (!names.add(spec.name())) {
          throw new IllegalStateException("retriever names must be unique");
        }
      }
      if (ranker == null || resultLimit == 0 || timeout == null || failurePolicy == null) {
        throw new IllegalStateException(
            "ranker, resultLimit, timeout and failurePolicy are required");
      }
      return new RankingPipeline<>(this);
    }

    private static int positive(int value, String name) {
      if (value <= 0) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return value;
    }
  }
}

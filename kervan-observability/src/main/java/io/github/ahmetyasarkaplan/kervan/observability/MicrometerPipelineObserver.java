package io.github.ahmetyasarkaplan.kervan.observability;

import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records pipeline and stage metrics using only configured names and fixed outcome tags. Distinct
 * pipeline names and pipeline/retriever pairs are bounded independently; excess names are grouped
 * under {@code _other} so dynamic configuration cannot create unbounded meter series.
 */
public final class MicrometerPipelineObserver implements PipelineObserver {

  private static final String OTHER = "_other";
  private static final int DEFAULT_NAME_LIMIT = 128;

  private final MeterRegistry registry;
  private final BoundedNames pipelineNames;
  private final BoundedNames retrieverPairs;

  /**
   * Creates an observer with the default configured-name limit.
   *
   * @param registry metric registry
   */
  public MicrometerPipelineObserver(MeterRegistry registry) {
    this(registry, DEFAULT_NAME_LIMIT);
  }

  /**
   * Creates an observer with a positive limit for pipeline names and provider pairs.
   *
   * @param registry metric registry
   * @param maxDistinctNames maximum distinct names before grouping them under {@code _other}
   */
  public MicrometerPipelineObserver(MeterRegistry registry, int maxDistinctNames) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    if (maxDistinctNames <= 0) {
      throw new IllegalArgumentException("maxDistinctNames must be positive");
    }
    pipelineNames = new BoundedNames(maxDistinctNames);
    retrieverPairs = new BoundedNames(maxDistinctNames);
  }

  @Override
  public Execution startPipeline(String pipelineName) {
    String label = pipelineNames.label(Objects.requireNonNull(pipelineName, "pipelineName"));
    return new MetricExecution(label);
  }

  private final class MetricExecution implements Execution {
    private final String pipeline;
    private final AtomicBoolean ended = new AtomicBoolean();

    private MetricExecution(String pipeline) {
      this.pipeline = pipeline;
    }

    @Override
    public Stage startStage(StageKind kind, String stableName, int inputCandidates) {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(stableName, "stableName");
      String retriever =
          kind == StageKind.RETRIEVER
              ? pipeline.equals(OTHER)
                  ? OTHER
                  : retrieverPairs.label(pipeline + '\u0000' + stableName, stableName)
              : null;
      return new MetricStage(pipeline, kind, retriever, inputCandidates);
    }

    @Override
    public void succeeded(boolean partial, Duration duration, int outputCandidates) {
      if (ended.compareAndSet(false, true)) {
        String outcome = partial ? "partial" : "success";
        pipelineMetrics(pipeline, outcome, duration);
        if (partial) {
          Counter.builder("kervan.pipeline.partial_results")
              .tag("pipeline", pipeline)
              .register(registry)
              .increment();
        }
      }
    }

    @Override
    public void failed(PipelineExecutionException.Reason reason, Duration duration) {
      if (ended.compareAndSet(false, true)) {
        pipelineMetrics(pipeline, "failed", duration);
      }
    }
  }

  private void pipelineMetrics(String pipeline, String outcome, Duration duration) {
    Timer.builder("kervan.pipeline.duration")
        .tags("pipeline", pipeline, "status", outcome)
        .register(registry)
        .record(duration);
    Counter.builder("kervan.pipeline.requests")
        .tags("pipeline", pipeline, "status", outcome)
        .register(registry)
        .increment();
  }

  private final class MetricStage implements Stage {
    private final String pipeline;
    private final StageKind kind;
    private final String retriever;
    private final int inputCandidates;
    private final AtomicBoolean ended = new AtomicBoolean();

    private MetricStage(String pipeline, StageKind kind, String retriever, int inputCandidates) {
      this.pipeline = pipeline;
      this.kind = kind;
      this.retriever = retriever;
      this.inputCandidates = inputCandidates;
    }

    @Override
    public void finished(StageStatus status, Duration duration, int outputCandidates) {
      if (!ended.compareAndSet(false, true)) {
        return;
      }
      String outcome = status.name().toLowerCase(java.util.Locale.ROOT);
      switch (kind) {
        case RETRIEVER -> {
          String[] tags = {"pipeline", pipeline, "retriever", retriever, "status", outcome};
          Timer.builder("kervan.retriever.duration").tags(tags).register(registry).record(duration);
          if (status == StageStatus.SUCCESS || status == StageStatus.EMPTY) {
            DistributionSummary.builder("kervan.retriever.candidates")
                .baseUnit("candidates")
                .tags("pipeline", pipeline, "retriever", retriever)
                .register(registry)
                .record(outputCandidates);
          } else if (status == StageStatus.FAILED) {
            Counter.builder("kervan.retriever.failures")
                .tags("pipeline", pipeline, "retriever", retriever)
                .register(registry)
                .increment();
          } else if (status == StageStatus.TIMED_OUT || status == StageStatus.DEADLINE_EXCEEDED) {
            Counter.builder("kervan.retriever.timeouts")
                .tags("pipeline", pipeline, "retriever", retriever, "status", outcome)
                .register(registry)
                .increment();
          }
        }
        case FUSION -> {
          Timer.builder("kervan.fusion.duration")
              .tags("pipeline", pipeline, "status", outcome)
              .register(registry)
              .record(duration);
          recordCount("kervan.fusion.input_candidates", pipeline, inputCandidates);
          if (status == StageStatus.SUCCESS || status == StageStatus.EMPTY) {
            recordCount("kervan.fusion.output_candidates", pipeline, outputCandidates);
          }
        }
        case RANKER ->
            Timer.builder("kervan.ranker.duration")
                .tags("pipeline", pipeline, "status", outcome)
                .register(registry)
                .record(duration);
      }
    }
  }

  private void recordCount(String name, String pipeline, int candidates) {
    DistributionSummary.builder(name)
        .baseUnit("candidates")
        .tag("pipeline", pipeline)
        .register(registry)
        .record(candidates);
  }

  private static final class BoundedNames {
    private final int limit;
    private final Set<String> known = new HashSet<>();

    private BoundedNames(int limit) {
      this.limit = limit;
    }

    private synchronized String label(String value) {
      return label(value, value);
    }

    private synchronized String label(String key, String value) {
      if (known.contains(key)) {
        return value;
      }
      if (known.size() >= limit) {
        return OTHER;
      }
      known.add(key);
      return value;
    }
  }
}

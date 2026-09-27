package io.github.ahmetyasarkaplan.kervan.observability;

import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creates one pipeline span and child spans for retrievers, fusion, and ranking. A stage's scope is
 * activated on its worker thread so downstream client instrumentation can share its trace. Span
 * attributes contain only stable configuration names, coarse outcomes, and counts.
 */
public final class OpenTelemetryPipelineObserver implements PipelineObserver {

  private final Tracer tracer;

  /**
   * Creates an observer using the supplied OpenTelemetry instance.
   *
   * @param telemetry tracing entry point
   */
  public OpenTelemetryPipelineObserver(OpenTelemetry telemetry) {
    Objects.requireNonNull(telemetry, "telemetry must not be null");
    tracer = telemetry.getTracer("io.github.ahmetyasarkaplan.kervan.observability");
  }

  @Override
  public Execution startPipeline(String pipelineName) {
    Span span =
        tracer
            .spanBuilder("kervan.pipeline")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("kervan.pipeline.name", Objects.requireNonNull(pipelineName))
            .startSpan();
    io.opentelemetry.context.Scope callerScope = span.makeCurrent();
    return new TraceExecution(span, Context.current(), callerScope);
  }

  private final class TraceExecution implements Execution {
    private final Span span;
    private final Context pipelineContext;
    private final io.opentelemetry.context.Scope callerScope;
    private final Set<TraceStage> openStages = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean ended = new AtomicBoolean();

    private TraceExecution(
        Span span, Context pipelineContext, io.opentelemetry.context.Scope callerScope) {
      this.span = span;
      this.pipelineContext = pipelineContext;
      this.callerScope = callerScope;
    }

    @Override
    public Stage startStage(StageKind kind, String stableName, int inputCandidates) {
      Objects.requireNonNull(kind, "kind");
      String spanName =
          switch (kind) {
            case RETRIEVER -> "kervan.retrieve";
            case FUSION -> "kervan.fusion";
            case RANKER -> "kervan.ranker";
          };
      Span child =
          tracer
              .spanBuilder(spanName)
              .setParent(pipelineContext)
              .setSpanKind(SpanKind.INTERNAL)
              .setAttribute("kervan.stage.name", Objects.requireNonNull(stableName))
              .setAttribute("kervan.stage.kind", kind.name().toLowerCase(Locale.ROOT))
              .setAttribute("kervan.stage.input_candidates", inputCandidates)
              .startSpan();
      TraceStage stage = new TraceStage(child, this);
      openStages.add(stage);
      if (ended.get()) {
        stage.finished(StageStatus.CANCELLED, Duration.ZERO, 0);
      }
      return stage;
    }

    @Override
    public void succeeded(boolean partial, Duration duration, int outputCandidates) {
      if (ended.compareAndSet(false, true)) {
        span.setAttribute("kervan.pipeline.partial", partial);
        span.setAttribute("kervan.pipeline.output_candidates", outputCandidates);
        span.setAttribute("kervan.pipeline.duration_ms", duration.toMillis());
        finish();
      }
    }

    @Override
    public void failed(PipelineExecutionException.Reason reason, Duration duration) {
      if (ended.compareAndSet(false, true)) {
        span.setStatus(StatusCode.ERROR);
        span.setAttribute("kervan.pipeline.failure_reason", Objects.requireNonNull(reason).name());
        span.setAttribute("kervan.pipeline.duration_ms", duration.toMillis());
        finish();
      }
    }

    private void finish() {
      try {
        for (TraceStage stage : openStages) {
          stage.cancel();
        }
      } finally {
        try {
          callerScope.close();
        } finally {
          span.end();
        }
      }
    }
  }

  private static final class TraceStage implements Stage {
    private final Span span;
    private final TraceExecution execution;
    private final long startedAt = System.nanoTime();
    private final AtomicBoolean ended = new AtomicBoolean();

    private TraceStage(Span span, TraceExecution execution) {
      this.span = span;
      this.execution = execution;
    }

    @Override
    public Scope activate() {
      if (ended.get()) {
        return () -> {};
      }
      io.opentelemetry.context.Scope stageScope = span.makeCurrent();
      return stageScope::close;
    }

    @Override
    public void finished(StageStatus status, Duration duration, int outputCandidates) {
      if (!ended.compareAndSet(false, true)) {
        return;
      }
      span.setAttribute("kervan.stage.status", Objects.requireNonNull(status).name());
      span.setAttribute("kervan.stage.output_candidates", outputCandidates);
      span.setAttribute("kervan.stage.duration_ms", duration.toMillis());
      if (status == StageStatus.FAILED
          || status == StageStatus.TIMED_OUT
          || status == StageStatus.DEADLINE_EXCEEDED) {
        span.setStatus(StatusCode.ERROR);
      }
      try {
        span.end();
      } finally {
        execution.openStages.remove(this);
      }
    }

    private void cancel() {
      finished(
          StageStatus.CANCELLED, Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)), 0);
    }
  }
}

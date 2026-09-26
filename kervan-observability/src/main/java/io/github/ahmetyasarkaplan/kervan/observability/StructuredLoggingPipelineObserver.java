package io.github.ahmetyasarkaplan.kervan.observability;

import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits one structured, content-free log event per completed pipeline execution. */
public final class StructuredLoggingPipelineObserver implements PipelineObserver {

  private final Logger logger;

  public StructuredLoggingPipelineObserver() {
    this(LoggerFactory.getLogger(StructuredLoggingPipelineObserver.class));
  }

  public StructuredLoggingPipelineObserver(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger must not be null");
  }

  @Override
  public Execution startPipeline(String pipelineName) {
    return new LogExecution(Objects.requireNonNull(pipelineName, "pipelineName"));
  }

  private final class LogExecution implements Execution {
    private final String pipelineName;
    private final AtomicInteger successfulRetrievers = new AtomicInteger();
    private final AtomicInteger failedRetrievers = new AtomicInteger();
    private final AtomicInteger timedOutRetrievers = new AtomicInteger();
    private final AtomicBoolean ended = new AtomicBoolean();

    private LogExecution(String pipelineName) {
      this.pipelineName = pipelineName;
    }

    @Override
    public Stage startStage(StageKind kind, String stableName, int inputCandidates) {
      if (kind != StageKind.RETRIEVER) {
        return Stage.NOOP;
      }
      return new Stage() {
        private final AtomicBoolean recorded = new AtomicBoolean();

        @Override
        public void finished(StageStatus status, Duration duration, int outputCandidates) {
          if (!recorded.compareAndSet(false, true)) {
            return;
          }
          switch (status) {
            case SUCCESS, EMPTY -> successfulRetrievers.incrementAndGet();
            case FAILED -> failedRetrievers.incrementAndGet();
            case TIMED_OUT, DEADLINE_EXCEEDED -> timedOutRetrievers.incrementAndGet();
            case CANCELLED -> {}
          }
        }
      };
    }

    @Override
    public void succeeded(boolean partial, Duration duration, int outputCandidates) {
      if (ended.compareAndSet(false, true)) {
        logger
            .atInfo()
            .addKeyValue("pipeline", pipelineName)
            .addKeyValue("status", partial ? "partial" : "success")
            .addKeyValue("duration_ms", duration.toMillis())
            .addKeyValue("candidate_count", outputCandidates)
            .addKeyValue("retriever_success_count", successfulRetrievers.get())
            .addKeyValue("retriever_failure_count", failedRetrievers.get())
            .addKeyValue("retriever_timeout_count", timedOutRetrievers.get())
            .log("Kervan pipeline completed");
      }
    }

    @Override
    public void failed(PipelineExecutionException.Reason reason, Duration duration) {
      if (ended.compareAndSet(false, true)) {
        logger
            .atWarn()
            .addKeyValue("pipeline", pipelineName)
            .addKeyValue("status", "failed")
            .addKeyValue("reason", Objects.requireNonNull(reason).name())
            .addKeyValue("duration_ms", duration.toMillis())
            .addKeyValue("retriever_success_count", successfulRetrievers.get())
            .addKeyValue("retriever_failure_count", failedRetrievers.get())
            .addKeyValue("retriever_timeout_count", timedOutRetrievers.get())
            .log("Kervan pipeline failed");
      }
    }
  }
}

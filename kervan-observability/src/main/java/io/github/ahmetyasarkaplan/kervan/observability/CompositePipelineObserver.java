package io.github.ahmetyasarkaplan.kervan.observability;

import io.github.ahmetyasarkaplan.kervan.api.PipelineExecutionException;
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Combines independent observers while isolating failures in any one of them. */
public final class CompositePipelineObserver implements PipelineObserver {

  private final List<PipelineObserver> observers;

  public CompositePipelineObserver(List<? extends PipelineObserver> observers) {
    this.observers = List.copyOf(Objects.requireNonNull(observers, "observers must not be null"));
  }

  @Override
  public Execution startPipeline(String pipelineName) {
    List<Execution> started = new ArrayList<>(observers.size());
    for (PipelineObserver observer : observers) {
      try {
        started.add(Objects.requireNonNull(observer.startPipeline(pipelineName)));
      } catch (RuntimeException ignored) {
        started.add(Execution.NOOP);
      }
    }
    return new CombinedExecution(List.copyOf(started));
  }

  private record CombinedExecution(List<Execution> delegates) implements Execution {

    @Override
    public Stage startStage(StageKind kind, String stableName, int inputCandidates) {
      List<Stage> stages = new ArrayList<>(delegates.size());
      for (Execution delegate : delegates) {
        try {
          stages.add(
              Objects.requireNonNull(delegate.startStage(kind, stableName, inputCandidates)));
        } catch (RuntimeException ignored) {
          stages.add(Stage.NOOP);
        }
      }
      return new CombinedStage(List.copyOf(stages));
    }

    @Override
    public void succeeded(boolean partial, Duration duration, int outputCandidates) {
      for (int index = delegates.size() - 1; index >= 0; index--) {
        try {
          delegates.get(index).succeeded(partial, duration, outputCandidates);
        } catch (RuntimeException ignored) {
          // Other telemetry signals should still be delivered.
        }
      }
    }

    @Override
    public void failed(PipelineExecutionException.Reason reason, Duration duration) {
      for (int index = delegates.size() - 1; index >= 0; index--) {
        try {
          delegates.get(index).failed(reason, duration);
        } catch (RuntimeException ignored) {
          // Other telemetry signals should still be delivered.
        }
      }
    }
  }

  private static final class CombinedStage implements Stage {
    private final List<Stage> delegates;
    private final AtomicBoolean ended = new AtomicBoolean();

    private CombinedStage(List<Stage> delegates) {
      this.delegates = delegates;
    }

    @Override
    public Scope activate() {
      List<Scope> scopes = new ArrayList<>(delegates.size());
      for (Stage delegate : delegates) {
        try {
          scopes.add(Objects.requireNonNull(delegate.activate()));
        } catch (RuntimeException ignored) {
          // Continue activating the remaining observers.
        }
      }
      return () -> {
        for (int index = scopes.size() - 1; index >= 0; index--) {
          try {
            scopes.get(index).close();
          } catch (RuntimeException ignored) {
            // Every scope still needs a chance to close.
          }
        }
      };
    }

    @Override
    public void finished(StageStatus status, Duration duration, int outputCandidates) {
      if (ended.compareAndSet(false, true)) {
        for (int index = delegates.size() - 1; index >= 0; index--) {
          try {
            delegates.get(index).finished(status, duration, outputCandidates);
          } catch (RuntimeException ignored) {
            // Continue delivering the stage result to the remaining observers.
          }
        }
      }
    }
  }
}

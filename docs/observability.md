# Observability

`RankingPipeline.Builder.observer(PipelineObserver)` enables optional execution
instrumentation. The observer receives pipeline and retriever outcomes, fusion
and ranking stage outcomes, durations, and candidate counts. Its event contract
lives in `kervan-core`; Micrometer, OpenTelemetry, and logging implementations
live in `kervan-observability`. A pipeline without an observer uses a no-op
implementation.

## Attach an observer

Supply your application's `MeterRegistry` and `OpenTelemetry` instances to the
observers. Combine signals into one observer and pass it to the Java builder:

```java
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import io.github.ahmetyasarkaplan.kervan.observability.CompositePipelineObserver;
import io.github.ahmetyasarkaplan.kervan.observability.MicrometerPipelineObserver;
import io.github.ahmetyasarkaplan.kervan.observability.OpenTelemetryPipelineObserver;
import io.github.ahmetyasarkaplan.kervan.observability.StructuredLoggingPipelineObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;

static PipelineObserver pipelineObserver(
    MeterRegistry meterRegistry, OpenTelemetry openTelemetry) {
  return new CompositePipelineObserver(
      List.of(
          new MicrometerPipelineObserver(meterRegistry),
          new OpenTelemetryPipelineObserver(openTelemetry),
          new StructuredLoggingPipelineObserver()));
}
```

Call `.observer(pipelineObserver(meterRegistry, openTelemetry))` on the
`RankingPipeline` builder in the [embedded example](../README.md#embedded-usage)
before `.build()`. You can also use any one implementation directly or provide
your own `PipelineObserver`. Observer callbacks must be thread-safe because
retriever work runs on provider threads. The pipeline ignores runtime exceptions
from observer callbacks so instrumentation does not change its result.

For pipelines assembled from Spring Boot properties, expose a single
`PipelineObserver` bean. The starter attaches that bean to its configured
pipelines when present. To use several signals, expose one
`CompositePipelineObserver` bean containing them; registering multiple separate
observer beans does not select a composite automatically. The starter does not
create a telemetry backend or exporter.

## Metrics

`MicrometerPipelineObserver` records these meters:

| Meter | Measurement |
| --- | --- |
| `kervan.pipeline.requests` | Completed executions, tagged `success`, `partial`, or `failed` |
| `kervan.pipeline.duration` | Completed execution duration |
| `kervan.pipeline.partial_results` | Partial executions |
| `kervan.retriever.duration` | Duration by configured retriever and coarse outcome |
| `kervan.retriever.candidates` | Candidate count for successful or empty retrievers |
| `kervan.retriever.failures` | Failed retriever count |
| `kervan.retriever.timeouts` | Timed-out or deadline-exceeded retriever count |
| `kervan.fusion.duration` | Fusion duration |
| `kervan.fusion.input_candidates` | Number of source candidates entering fusion |
| `kervan.fusion.output_candidates` | Number of candidates after successful fusion |
| `kervan.ranker.duration` | Ranker duration |

Tags use configured pipeline/retriever names and fixed outcome values. The
default constructor admits at most 128 distinct pipeline names and 128 distinct
pipeline/retriever pairs per observer instance; additional names are grouped
under `_other`. The second constructor accepts a positive custom limit. Keep
pipeline and retriever names stable in your configuration. Query text, candidate
IDs, user IDs, request IDs, and exception messages are never metric tags.

## Traces and logs

`OpenTelemetryPipelineObserver` creates a `kervan.pipeline` span with child
`kervan.retrieve`, `kervan.fusion`, and `kervan.ranker` spans. Each retriever
span is active while its provider runs, so instrumented provider clients can
create child spans. Span attributes contain configured names, stage kind,
coarse status, duration, and candidate counts. A failed pipeline or failed or
timed-out stage receives an error status. The application controls the tracer
provider and export configuration.

`StructuredLoggingPipelineObserver` writes one SLF4J key-value event per
completed execution: INFO for success or partial results, WARN for failure.
Fields include pipeline name, status, duration, and retriever outcome counts;
successful executions also include final candidate count. Whether these fields
render as JSON depends on the application's logging backend configuration.

Neither implementation adds query text, vectors, candidate IDs, provider
exception messages, or result contents to its telemetry. Use stable,
non-sensitive names when configuring a pipeline or retriever.

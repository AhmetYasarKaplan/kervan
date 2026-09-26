package io.github.ahmetyasarkaplan.kervan.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ObserverBehaviorTest {

  @Test
  void boundsMetricSeriesWhenConfiguredNamesVary() {
    var meters = new SimpleMeterRegistry();
    try {
      var observer = new MicrometerPipelineObserver(meters, 2);
      emit(observer, "one", "first");
      emit(observer, "two", "second");
      emit(observer, "one", "third");
      emit(observer, "three", "fourth");

      Set<String> pipelineLabels =
          meters.getMeters().stream()
              .map(meter -> meter.getId().getTag("pipeline"))
              .filter(java.util.Objects::nonNull)
              .collect(Collectors.toSet());
      assertThat(pipelineLabels).containsExactlyInAnyOrder("one", "two", "_other");
      Set<String> retrieverLabels =
          meters.getMeters().stream()
              .filter(meter -> meter.getId().getName().equals("kervan.retriever.duration"))
              .map(meter -> meter.getId().getTag("retriever"))
              .collect(Collectors.toSet());
      assertThat(retrieverLabels).containsExactlyInAnyOrder("first", "second", "_other");
      assertThat(meters.getMeters().stream().map(meter -> meter.getId().toString()))
          .noneMatch(id -> id.contains("three") || id.contains("fourth"));
    } finally {
      meters.close();
    }
  }

  @Test
  void timeoutHasAuthoritativeMetricAndSpanEvenWhenWorkerIsCancelled() {
    var meters = new SimpleMeterRegistry();
    try {
      var exporter = InMemorySpanExporter.create();
      try (var provider =
          SdkTracerProvider.builder()
              .addSpanProcessor(SimpleSpanProcessor.create(exporter))
              .build()) {
        var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        var observer =
            new CompositePipelineObserver(
                List.of(
                    new MicrometerPipelineObserver(meters),
                    new OpenTelemetryPipelineObserver(telemetry)));
        var slowStarted = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);
        try (RankingPipeline<String, String> pipeline =
            RankingPipeline.<String, String>builder("timed")
                .addRetriever(
                    RetrieverSpec.required(
                        "fast",
                        (query, context) -> {
                          try {
                            assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                          }
                          return List.of(SourceCandidate.of("item", 1.0));
                        },
                        10,
                        Duration.ofSeconds(1)))
                .addRetriever(
                    RetrieverSpec.optional(
                        "slow",
                        (query, context) -> {
                          slowStarted.countDown();
                          while (true) {
                            try {
                              releaseSlow.await();
                              break;
                            } catch (InterruptedException ignored) {
                              // Keep the provider pending until the coordinator settles timeout.
                            }
                          }
                          return List.of();
                        },
                        10,
                        Duration.ofMillis(40)))
                .ranker(new IdentityRanker<>(), 10)
                .resultLimit(10)
                .timeout(Duration.ofSeconds(3))
                .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
                .observer(observer)
                .build()) {
          assertThat(pipeline.execute("query").isPartial()).isTrue();
        } finally {
          releaseSlow.countDown();
        }

        assertThat(
                meters
                    .find("kervan.retriever.timeouts")
                    .tags("pipeline", "timed", "retriever", "slow", "status", "timed_out")
                    .counter()
                    .count())
            .isEqualTo(1.0);
        assertThat(
                exporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getName().equals("kervan.retrieve"))
                    .filter(
                        span ->
                            "slow"
                                .equals(
                                    span.getAttributes()
                                        .get(AttributeKey.stringKey("kervan.stage.name")))))
            .singleElement()
            .satisfies(
                span ->
                    assertThat(
                            span.getAttributes().get(AttributeKey.stringKey("kervan.stage.status")))
                        .isEqualTo("TIMED_OUT"));
      }
    } finally {
      meters.close();
    }
  }

  private static void emit(MicrometerPipelineObserver observer, String pipeline, String retriever) {
    PipelineObserver.Execution execution = observer.startPipeline(pipeline);
    execution
        .startStage(PipelineObserver.StageKind.RETRIEVER, retriever, 0)
        .finished(PipelineObserver.StageStatus.SUCCESS, Duration.ofMillis(1), 1);
    execution.succeeded(false, Duration.ofMillis(2), 1);
  }
}

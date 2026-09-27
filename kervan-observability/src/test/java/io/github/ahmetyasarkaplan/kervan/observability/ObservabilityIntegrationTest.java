package io.github.ahmetyasarkaplan.kervan.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.PipelineResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ObservabilityIntegrationTest {

  @Test
  void recordsAuthoritativeStageMetricsTraceHierarchyAndSafeStructuredLog() {
    var meters = new SimpleMeterRegistry();
    try {
      var exporter = InMemorySpanExporter.create();
      try (var provider =
          SdkTracerProvider.builder()
              .addSpanProcessor(SimpleSpanProcessor.create(exporter))
              .build()) {
        var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        var logger = (Logger) org.slf4j.LoggerFactory.getLogger("kervan.observability.test");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
          var observer =
              new CompositePipelineObserver(
                  List.of(
                      new MicrometerPipelineObserver(meters),
                      new OpenTelemetryPipelineObserver(telemetry),
                      new StructuredLoggingPipelineObserver(logger)));
          AtomicReference<String> currentRetrieverSpan = new AtomicReference<>();
          try (RankingPipeline<String, String> pipeline =
              RankingPipeline.<String, String>builder("catalog")
                  .addRetriever(
                      RetrieverSpec.required(
                          "search",
                          (query, context) -> {
                            currentRetrieverSpan.set(Span.current().getSpanContext().getSpanId());
                            Span clientSpan =
                                telemetry
                                    .getTracer("test")
                                    .spanBuilder("provider.client")
                                    .startSpan();
                            clientSpan.end();
                            return List.of(SourceCandidate.of("private-candidate-id", 1.0));
                          },
                          10,
                          Duration.ofSeconds(1)))
                  .addRetriever(
                      RetrieverSpec.optional(
                          "recommendations",
                          (query, context) -> {
                            throw new IllegalStateException("private provider exception");
                          },
                          10,
                          Duration.ofSeconds(1)))
                  .ranker(new IdentityRanker<>(), 10)
                  .resultLimit(10)
                  .timeout(Duration.ofSeconds(2))
                  .failurePolicy(FailurePolicy.PARTIAL_RESULTS)
                  .observer(observer)
                  .build()) {
            PipelineResult<String> result = pipeline.execute("private query text");
            assertThat(result.isPartial()).isTrue();
          }

          assertThat(
                  meters
                      .find("kervan.pipeline.requests")
                      .tags("pipeline", "catalog", "status", "partial")
                      .counter()
                      .count())
              .isEqualTo(1.0);
          assertThat(
                  meters
                      .find("kervan.pipeline.duration")
                      .tags("pipeline", "catalog", "status", "partial")
                      .timer()
                      .count())
              .isEqualTo(1);
          assertThat(
                  meters
                      .find("kervan.retriever.duration")
                      .tags("pipeline", "catalog", "retriever", "search", "status", "success")
                      .timer()
                      .count())
              .isEqualTo(1);
          assertThat(
                  meters
                      .find("kervan.retriever.failures")
                      .tags("pipeline", "catalog", "retriever", "recommendations")
                      .counter()
                      .count())
              .isEqualTo(1.0);
          assertThat(
                  meters
                      .find("kervan.retriever.candidates")
                      .tags("pipeline", "catalog", "retriever", "search")
                      .summary()
                      .totalAmount())
              .isEqualTo(1.0);
          assertThat(
                  meters
                      .find("kervan.fusion.input_candidates")
                      .tag("pipeline", "catalog")
                      .summary()
                      .totalAmount())
              .isEqualTo(1.0);
          assertThat(
                  meters
                      .find("kervan.fusion.output_candidates")
                      .tag("pipeline", "catalog")
                      .summary()
                      .totalAmount())
              .isEqualTo(1.0);
          assertThat(
                  meters.find("kervan.ranker.duration").tag("pipeline", "catalog").timer().count())
              .isEqualTo(1);
          assertThat(
                  meters.find("kervan.policy.duration").tag("pipeline", "catalog").timer().count())
              .isEqualTo(1);

          List<SpanData> spans = exporter.getFinishedSpanItems();
          assertThat(spans).hasSize(7);
          assertThat(spans).extracting(SpanData::getName).contains("kervan.policy");
          SpanData parent =
              spans.stream()
                  .filter(s -> s.getName().equals("kervan.pipeline"))
                  .findFirst()
                  .orElseThrow();
          assertThat(
                  spans.stream()
                      .filter(
                          s ->
                              s.getName().startsWith("kervan.")
                                  && !s.getName().equals("kervan.pipeline")))
              .allSatisfy(
                  child -> assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId()));
          assertThat(currentRetrieverSpan.get()).isNotNull().isNotEqualTo(parent.getSpanId());
          SpanData clientSpan =
              spans.stream()
                  .filter(s -> s.getName().equals("provider.client"))
                  .findFirst()
                  .orElseThrow();
          assertThat(clientSpan.getParentSpanId()).isEqualTo(currentRetrieverSpan.get());
          assertThat(spans.stream().filter(s -> s.getName().equals("kervan.retrieve")))
              .anySatisfy(
                  span -> {
                    assertThat(
                            span.getAttributes().get(AttributeKey.stringKey("kervan.stage.name")))
                        .isEqualTo("recommendations");
                    assertThat(
                            span.getAttributes().get(AttributeKey.stringKey("kervan.stage.status")))
                        .isEqualTo("FAILED");
                  });
          assertThat(spans.toString())
              .doesNotContain(
                  "private query text", "private-candidate-id", "private provider exception");

          assertThat(appender.list).hasSize(1);
          ILoggingEvent event = appender.list.getFirst();
          assertThat(event.getLevel()).isEqualTo(Level.INFO);
          Map<String, Object> fields =
              event.getKeyValuePairs().stream()
                  .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
          assertThat(fields)
              .containsEntry("pipeline", "catalog")
              .containsEntry("status", "partial")
              .containsEntry("candidate_count", 1);
          assertThat(event.getKeyValuePairs().toString())
              .doesNotContain(
                  "private query text", "private-candidate-id", "private provider exception");
        } finally {
          logger.detachAppender(appender);
          appender.stop();
        }
      }
    } finally {
      meters.close();
    }
  }
}

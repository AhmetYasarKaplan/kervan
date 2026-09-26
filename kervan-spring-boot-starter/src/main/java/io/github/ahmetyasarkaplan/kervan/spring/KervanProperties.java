package io.github.ahmetyasarkaplan.kervan.spring;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Type-safe external settings used to assemble named pipelines from Spring beans. */
@ConfigurationProperties(prefix = "kervan", ignoreUnknownFields = false)
public record KervanProperties(Map<String, Pipeline> pipelines) {

  public KervanProperties {
    pipelines =
        pipelines == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pipelines));
  }

  public record Pipeline(
      Duration timeout,
      Integer resultSize,
      FailurePolicy failurePolicy,
      List<Retriever> retrievers,
      Fusion fusion,
      RankerSettings ranker) {}

  public record Retriever(
      String name,
      String bean,
      Integer candidateLimit,
      Duration timeout,
      Boolean required,
      Double weight) {}

  public record Fusion(String type, String bean, Integer candidateLimit, Integer rankConstant) {}

  public record RankerSettings(String type, String bean, Integer candidateLimit) {}
}

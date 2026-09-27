package io.github.ahmetyasarkaplan.kervan.spring;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe external settings used to assemble named pipelines from Spring beans.
 *
 * @param pipelines pipeline settings keyed by stable name
 */
@ConfigurationProperties(prefix = "kervan", ignoreUnknownFields = false)
public record KervanProperties(Map<String, Pipeline> pipelines) {

  /** Copies configured pipelines into an immutable map. */
  public KervanProperties {
    pipelines =
        pipelines == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pipelines));
  }

  /**
   * Settings for one pipeline.
   *
   * @param timeout global request timeout
   * @param resultSize maximum final candidate count
   * @param failurePolicy partial-result or fail-fast behavior
   * @param retrievers configured provider specifications
   * @param fusion optional fusion settings
   * @param ranker ranking settings
   */
  public record Pipeline(
      Duration timeout,
      Integer resultSize,
      FailurePolicy failurePolicy,
      List<Retriever> retrievers,
      Fusion fusion,
      RankerSettings ranker) {}

  /**
   * Settings for one named provider bean.
   *
   * @param name stable provider name
   * @param bean application bean name
   * @param candidateLimit maximum unique source candidates
   * @param timeout provider timeout
   * @param required whether provider failure fails the pipeline
   * @param weight score-fusion weight
   */
  public record Retriever(
      String name,
      String bean,
      Integer candidateLimit,
      Duration timeout,
      Boolean required,
      Double weight) {}

  /**
   * Settings for built-in or application-supplied fusion.
   *
   * @param type built-in fusion kind
   * @param bean custom fusion bean name
   * @param candidateLimit maximum fused candidates
   * @param rankConstant RRF denominator offset
   */
  public record Fusion(String type, String bean, Integer candidateLimit, Integer rankConstant) {}

  /**
   * Settings for built-in or application-supplied ranking.
   *
   * @param type built-in ranker kind
   * @param bean custom ranker bean name
   * @param candidateLimit maximum ranked candidates
   */
  public record RankerSettings(String type, String bean, Integer candidateLimit) {}
}

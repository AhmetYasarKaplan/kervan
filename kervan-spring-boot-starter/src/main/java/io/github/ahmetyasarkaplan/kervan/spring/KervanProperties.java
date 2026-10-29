package io.github.ahmetyasarkaplan.kervan.spring;

import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.RankerFailurePolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
   * @param policy optional final filtering and ordering policy
   */
  public record Pipeline(
      Duration timeout,
      Integer resultSize,
      FailurePolicy failurePolicy,
      List<Retriever> retrievers,
      Fusion fusion,
      RankerSettings ranker,
      PolicySettings policy) {

    /** Selects the canonical constructor for Spring Boot configuration binding. */
    @ConstructorBinding
    public Pipeline {}

    /**
     * Creates pipeline settings with the default identity policy.
     *
     * @param timeout global request timeout
     * @param resultSize maximum final candidate count
     * @param failurePolicy partial-result or fail-fast behavior
     * @param retrievers configured provider specifications
     * @param fusion optional fusion settings
     * @param ranker ranking settings
     */
    public Pipeline(
        Duration timeout,
        Integer resultSize,
        FailurePolicy failurePolicy,
        List<Retriever> retrievers,
        Fusion fusion,
        RankerSettings ranker) {
      this(timeout, resultSize, failurePolicy, retrievers, fusion, ranker, null);
    }
  }

  /**
   * Settings for one named provider bean.
   *
   * @param name stable provider name
   * @param bean application bean name
   * @param candidateLimit maximum unique source candidates
   * @param timeout provider timeout
   * @param required whether provider failure fails the pipeline
   * @param weight positive weight for weight-aware fusion
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
   * @param inputLimit maximum fused candidates passed to a bounded ranker
   * @param timeout optional ranking time budget; requires inputLimit and failurePolicy
   * @param failurePolicy behavior when bounded ranking fails or times out
   */
  public record RankerSettings(
      String type,
      String bean,
      Integer candidateLimit,
      Integer inputLimit,
      Duration timeout,
      RankerFailurePolicy failurePolicy) {

    /** Selects the complete settings constructor for external configuration binding. */
    @ConstructorBinding
    public RankerSettings {}

    /**
     * Creates ranking settings using the pipeline's existing finalization budget.
     *
     * @param type identity or custom
     * @param bean custom ranker bean name
     * @param candidateLimit maximum ranked candidates
     */
    public RankerSettings(String type, String bean, Integer candidateLimit) {
      this(type, bean, candidateLimit, null, null, null);
    }
  }

  /**
   * Settings for the final policy.
   *
   * @param type identity or custom
   * @param bean custom policy bean name
   */
  public record PolicySettings(String type, String bean) {}
}

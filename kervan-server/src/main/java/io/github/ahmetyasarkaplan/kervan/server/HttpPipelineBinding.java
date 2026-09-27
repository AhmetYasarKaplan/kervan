package io.github.ahmetyasarkaplan.kervan.server;

import java.util.Objects;

/**
 * Explicitly allows one configured pipeline to be invoked through the HTTP endpoint. Applications
 * provide bindings as Spring beans; request bodies cannot choose Java types or expose other
 * pipelines.
 *
 * @param <Q> query type accepted by the pipeline
 * @param <ID> canonical candidate identifier type
 * @param pipelineName configured pipeline name
 * @param queryType concrete query type used for JSON decoding
 * @param idType concrete canonical identifier type
 */
public record HttpPipelineBinding<Q, ID>(
    String pipelineName, Class<Q> queryType, Class<ID> idType) {

  /** Validates the explicit HTTP binding. */
  public HttpPipelineBinding {
    Objects.requireNonNull(pipelineName, "pipelineName must not be null");
    if (pipelineName.isBlank()) {
      throw new IllegalArgumentException("pipelineName must not be blank");
    }
    Objects.requireNonNull(queryType, "queryType must not be null");
    Objects.requireNonNull(idType, "idType must not be null");
  }
}

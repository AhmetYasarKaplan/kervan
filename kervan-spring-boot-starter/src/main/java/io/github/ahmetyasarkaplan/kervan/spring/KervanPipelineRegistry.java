package io.github.ahmetyasarkaplan.kervan.spring;

import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** Owns pipelines assembled from configuration and closes them with the application context. */
public final class KervanPipelineRegistry implements AutoCloseable {

  private final Map<String, KervanPipelineFactory.PipelineEntry> pipelines;
  private volatile boolean closed;

  KervanPipelineRegistry(
      KervanProperties properties,
      ConfigurableListableBeanFactory beanFactory,
      PipelineObserver observer) {
    pipelines = new KervanPipelineFactory(beanFactory, observer).create(properties);
  }

  /**
   * Returns a named pipeline when the component bean declarations identify the supplied query and
   * candidate-ID types. Declare retriever beans with concrete generic return types so this check
   * can run before an application request.
   */
  @SuppressWarnings("unchecked")
  public <Q, ID> RankingPipeline<Q, ID> pipeline(
      String name, Class<Q> queryType, Class<ID> idType) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(queryType, "queryType must not be null");
    Objects.requireNonNull(idType, "idType must not be null");
    if (closed) {
      throw new IllegalStateException("Kervan pipeline registry is closed");
    }
    var entry = pipelines.get(name);
    if (entry == null) {
      throw new IllegalArgumentException("No Kervan pipeline named '" + name + "'");
    }
    if (entry.queryType() == null || entry.idType() == null) {
      throw new IllegalStateException(
          "Cannot verify generic types for Kervan pipeline '"
              + name
              + "'; declare component beans with concrete generic types");
    }
    if (!entry.queryType().equals(queryType) || !entry.idType().equals(idType)) {
      throw new IllegalArgumentException(
          "Kervan pipeline '"
              + name
              + "' uses <"
              + entry.queryType().getName()
              + ", "
              + entry.idType().getName()
              + ">, not <"
              + queryType.getName()
              + ", "
              + idType.getName()
              + ">");
    }
    return (RankingPipeline<Q, ID>) entry.pipeline();
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(pipelines.keySet()));
  }

  public int size() {
    return pipelines.size();
  }

  boolean isClosed() {
    return closed;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    pipelines.values().forEach(entry -> entry.pipeline().close());
  }
}

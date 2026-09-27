package io.github.ahmetyasarkaplan.kervan.server;

import io.github.ahmetyasarkaplan.kervan.spring.KervanAutoConfiguration;
import io.github.ahmetyasarkaplan.kervan.spring.KervanPipelineRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import tools.jackson.databind.json.JsonMapper;

/** Adds an HTTP endpoint only when the application explicitly binds at least one pipeline. */
@AutoConfiguration(after = KervanAutoConfiguration.class)
@ConditionalOnClass({DispatcherServlet.class, JsonMapper.class})
public class KervanServerAutoConfiguration {

  /** Creates the server auto-configuration entry point. */
  public KervanServerAutoConfiguration() {}

  /**
   * Creates the thin HTTP adapter after the configured pipeline registry is available.
   *
   * @param registry configured pipeline registry
   * @param bindings explicit HTTP allowlist
   * @param jsonMapper Spring Boot's configured JSON mapper
   * @return HTTP controller
   */
  @Bean
  @ConditionalOnBean(HttpPipelineBinding.class)
  @ConditionalOnMissingBean
  KervanHttpController kervanHttpController(
      KervanPipelineRegistry registry,
      List<HttpPipelineBinding<?, ?>> bindings,
      JsonMapper jsonMapper) {
    return new KervanHttpController(registry, bindings, jsonMapper);
  }
}

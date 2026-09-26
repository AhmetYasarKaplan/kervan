package io.github.ahmetyasarkaplan.kervan.spring;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assembles configured pipelines from named application beans. */
@AutoConfiguration
@EnableConfigurationProperties(KervanProperties.class)
public class KervanAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public KervanPipelineRegistry kervanPipelineRegistry(
      KervanProperties properties, ConfigurableListableBeanFactory beanFactory) {
    return new KervanPipelineRegistry(properties, beanFactory);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(HealthIndicator.class)
  static class HealthConfiguration {

    @Bean(name = "kervanHealthIndicator")
    @ConditionalOnMissingBean(name = "kervanHealthIndicator")
    HealthIndicator kervanHealthIndicator(KervanPipelineRegistry registry) {
      return () ->
          registry.isClosed()
              ? Health.down().build()
              : Health.up().withDetail("pipelineCount", registry.size()).build();
    }
  }
}

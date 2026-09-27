package io.github.ahmetyasarkaplan.kervan.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetyasarkaplan.kervan.spring.KervanPipelineRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ProductDiscoveryConfigurationTest {

  @Test
  void assemblesTheThreeProviderPipelineFromApplicationConfiguration() {
    try (var application =
        new SpringApplicationBuilder(ProductDiscoveryApplication.class)
            .web(WebApplicationType.NONE)
            .properties("spring.main.banner-mode=off")
            .run()) {
      var registry = application.getBean(KervanPipelineRegistry.class);
      assertThat(registry.names()).containsExactly("discovery");
      assertThat(registry.pipeline("discovery", ProductQuery.class, String.class)).isNotNull();
    }
  }
}

package io.github.ahmetyasarkaplan.kervan.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = SampleApplication.class,
    properties = "spring.config.import=classpath:sample-kervan.yml")
class KervanSpringBootContextTest {

  @Autowired private KervanPipelineRegistry registry;

  @Test
  void bindsYamlAndExecutesPipelineInAFullSpringBootContext() {
    assertThat(
            registry.pipeline("sample", String.class, String.class).execute("camera").candidates())
        .extracting(RankedCandidate::id)
        .containsExactly("shared", "recommendation-only", "search-only");
  }
}

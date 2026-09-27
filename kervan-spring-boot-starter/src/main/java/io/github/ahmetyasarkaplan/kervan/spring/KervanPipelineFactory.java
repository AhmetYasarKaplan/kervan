package io.github.ahmetyasarkaplan.kervan.spring;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.FailurePolicy;
import io.github.ahmetyasarkaplan.kervan.api.FusionStrategy;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingPolicy;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRanker;
import io.github.ahmetyasarkaplan.kervan.core.IdentityRankingPolicy;
import io.github.ahmetyasarkaplan.kervan.core.PipelineObserver;
import io.github.ahmetyasarkaplan.kervan.core.RankingPipeline;
import io.github.ahmetyasarkaplan.kervan.core.ReciprocalRankFusion;
import io.github.ahmetyasarkaplan.kervan.core.RetrieverSpec;
import io.github.ahmetyasarkaplan.kervan.core.WeightedMinMaxFusion;
import io.github.ahmetyasarkaplan.kervan.core.WeightedReciprocalRankFusion;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;

/** Validates external settings and resolves named application components once at startup. */
final class KervanPipelineFactory {

  private final ConfigurableListableBeanFactory beans;
  private final PipelineObserver observer;

  KervanPipelineFactory(ConfigurableListableBeanFactory beans, PipelineObserver observer) {
    this.beans = beans;
    this.observer = observer;
  }

  Map<String, PipelineEntry> create(KervanProperties properties) {
    Map<String, PipelineEntry> created = new LinkedHashMap<>();
    try {
      for (var configured : properties.pipelines().entrySet()) {
        String name = nonBlank(configured.getKey(), "kervan.pipelines");
        String path = "kervan.pipelines." + name;
        if (configured.getValue() == null) {
          throw invalid(path, "pipeline settings are required");
        }
        created.put(name, createOne(name, configured.getValue(), path));
      }
      return Collections.unmodifiableMap(new LinkedHashMap<>(created));
    } catch (RuntimeException failure) {
      created.values().forEach(entry -> entry.pipeline().close());
      throw failure;
    }
  }

  private PipelineEntry createOne(String name, KervanProperties.Pipeline settings, String path) {
    Duration timeout = positive(settings.timeout(), path + ".timeout");
    int resultSize = positive(settings.resultSize(), path + ".result-size");
    FailurePolicy failurePolicy = settings.failurePolicy();
    if (failurePolicy == null) {
      throw invalid(path + ".failure-policy", "a failure policy is required");
    }
    List<KervanProperties.Retriever> retrievers = settings.retrievers();
    if (retrievers == null || retrievers.isEmpty()) {
      throw invalid(path + ".retrievers", "at least one retriever is required");
    }

    RankingPipeline.Builder<Object, Object> builder = RankingPipeline.builder(name);
    if (observer != null) {
      builder.observer(observer);
    }
    Set<String> retrieverNames = new HashSet<>();
    TypePair types = new TypePair(null, null);
    long sourceCapacity = 0;
    for (int index = 0; index < retrievers.size(); index++) {
      String retrieverPath = path + ".retrievers[" + index + "]";
      KervanProperties.Retriever configured = retrievers.get(index);
      if (configured == null) {
        throw invalid(retrieverPath, "retriever settings are required");
      }
      String retrieverName = nonBlank(configured.name(), retrieverPath + ".name");
      if (!retrieverNames.add(retrieverName)) {
        throw invalid(retrieverPath + ".name", "duplicate retriever name: " + retrieverName);
      }
      String beanName = nonBlank(configured.bean(), retrieverPath + ".bean");
      int candidateLimit =
          positive(configured.candidateLimit(), retrieverPath + ".candidate-limit");
      Duration retrieverTimeout = positive(configured.timeout(), retrieverPath + ".timeout");
      if (configured.required() == null) {
        throw invalid(retrieverPath + ".required", "required must be true or false");
      }
      double weight = configured.weight() == null ? 1.0 : configured.weight();
      if (!Double.isFinite(weight) || weight <= 0) {
        throw invalid(retrieverPath + ".weight", "weight must be positive and finite");
      }
      CandidateRetriever<?, ?> retriever =
          component(beanName, CandidateRetriever.class, retrieverPath + ".bean");
      types =
          types.withQuery(
              genericType(beanName, retriever, CandidateRetriever.class, 0),
              retrieverPath + ".bean",
              beanName);
      types =
          types.withId(
              genericType(beanName, retriever, CandidateRetriever.class, 1),
              retrieverPath + ".bean",
              beanName);
      builder.addRetriever(
          new RetrieverSpec<>(
              retrieverName,
              castRetriever(retriever),
              candidateLimit,
              retrieverTimeout,
              configured.required(),
              weight));
      sourceCapacity += candidateLimit;
    }

    int defaultStageLimit = (int) Math.min(sourceCapacity, Integer.MAX_VALUE);
    types = configureFusion(builder, settings.fusion(), path + ".fusion", defaultStageLimit, types);
    types = configureRanker(builder, settings.ranker(), path + ".ranker", resultSize, types);
    types = configurePolicy(builder, settings.policy(), path + ".policy", types);
    try {
      return new PipelineEntry(
          builder.resultLimit(resultSize).timeout(timeout).failurePolicy(failurePolicy).build(),
          types.queryType(),
          types.idType());
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw invalid(path, "pipeline settings are invalid: " + failure.getMessage());
    }
  }

  private TypePair configureFusion(
      RankingPipeline.Builder<Object, Object> builder,
      KervanProperties.Fusion settings,
      String path,
      int defaultLimit,
      TypePair types) {
    if (settings == null) {
      return types;
    }
    String type = nonBlank(settings.type(), path + ".type");
    int limit =
        settings.candidateLimit() == null
            ? defaultLimit
            : positive(settings.candidateLimit(), path + ".candidate-limit");
    FusionStrategy<?> fusion;
    switch (type) {
      case "rrf" -> {
        absent(settings.bean(), path + ".bean");
        int constant =
            settings.rankConstant() == null
                ? 60
                : positive(settings.rankConstant(), path + ".rank-constant");
        fusion = new ReciprocalRankFusion<>(constant);
      }
      case "weighted-rrf" -> {
        absent(settings.bean(), path + ".bean");
        int constant =
            settings.rankConstant() == null
                ? 60
                : positive(settings.rankConstant(), path + ".rank-constant");
        fusion = new WeightedReciprocalRankFusion<>(constant);
      }
      case "weighted-min-max" -> {
        absent(settings.bean(), path + ".bean");
        if (settings.rankConstant() != null) {
          throw invalid(path + ".rank-constant", "only rrf and weighted-rrf accept rank-constant");
        }
        fusion = new WeightedMinMaxFusion<>();
      }
      case "custom" -> {
        if (settings.rankConstant() != null) {
          throw invalid(path + ".rank-constant", "only rrf and weighted-rrf accept rank-constant");
        }
        String beanName = nonBlank(settings.bean(), path + ".bean");
        fusion = component(beanName, FusionStrategy.class, path + ".bean");
        types =
            types.withId(
                genericType(beanName, fusion, FusionStrategy.class, 0), path + ".bean", beanName);
      }
      default -> throw invalid(path + ".type", "unsupported fusion type: " + type);
    }
    builder.fusion(castFusion(fusion), limit);
    return types;
  }

  private TypePair configureRanker(
      RankingPipeline.Builder<Object, Object> builder,
      KervanProperties.RankerSettings settings,
      String path,
      int resultSize,
      TypePair types) {
    if (settings == null) {
      builder.ranker(new IdentityRanker<>(), resultSize);
      return types;
    }
    String type = nonBlank(settings.type(), path + ".type");
    int limit =
        settings.candidateLimit() == null
            ? resultSize
            : positive(settings.candidateLimit(), path + ".candidate-limit");
    Ranker<?, ?> ranker;
    switch (type) {
      case "identity" -> {
        absent(settings.bean(), path + ".bean");
        ranker = new IdentityRanker<>();
      }
      case "custom" -> {
        String beanName = nonBlank(settings.bean(), path + ".bean");
        ranker = component(beanName, Ranker.class, path + ".bean");
        types =
            types.withQuery(
                genericType(beanName, ranker, Ranker.class, 0), path + ".bean", beanName);
        types =
            types.withId(genericType(beanName, ranker, Ranker.class, 1), path + ".bean", beanName);
      }
      default -> throw invalid(path + ".type", "unsupported ranker type: " + type);
    }
    builder.ranker(castRanker(ranker), limit);
    return types;
  }

  private TypePair configurePolicy(
      RankingPipeline.Builder<Object, Object> builder,
      KervanProperties.PolicySettings settings,
      String path,
      TypePair types) {
    if (settings == null) {
      return types;
    }
    String type = nonBlank(settings.type(), path + ".type");
    RankingPolicy<?, ?> policy;
    switch (type) {
      case "identity" -> {
        absent(settings.bean(), path + ".bean");
        policy = new IdentityRankingPolicy<>();
      }
      case "custom" -> {
        String beanName = nonBlank(settings.bean(), path + ".bean");
        policy = component(beanName, RankingPolicy.class, path + ".bean");
        types =
            types.withQuery(
                genericType(beanName, policy, RankingPolicy.class, 0), path + ".bean", beanName);
        types =
            types.withId(
                genericType(beanName, policy, RankingPolicy.class, 1), path + ".bean", beanName);
      }
      default -> throw invalid(path + ".type", "unsupported policy type: " + type);
    }
    builder.policy(castPolicy(policy));
    return types;
  }

  private <T> T component(String beanName, Class<T> contract, String path) {
    if (!beans.containsBean(beanName)) {
      throw invalid(path, "no Spring bean named '" + beanName + "'");
    }
    Object bean = beans.getBean(beanName);
    if (!contract.isInstance(bean)) {
      throw invalid(path, "bean '" + beanName + "' must implement " + contract.getSimpleName());
    }
    return contract.cast(bean);
  }

  private Class<?> genericType(String beanName, Object bean, Class<?> contract, int index) {
    Class<?> declared = null;
    if (beans.containsBeanDefinition(beanName)) {
      declared =
          beans
              .getMergedBeanDefinition(beanName)
              .getResolvableType()
              .as(contract)
              .getGeneric(index)
              .resolve();
    }
    if (declared != null) {
      return declared;
    }
    return ResolvableType.forClass(bean.getClass()).as(contract).getGeneric(index).resolve();
  }

  private static int positive(Integer value, String path) {
    if (value == null || value <= 0) {
      throw invalid(path, "a positive integer is required");
    }
    return value;
  }

  private static Duration positive(Duration value, String path) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw invalid(path, "a positive duration is required");
    }
    try {
      value.toNanos();
    } catch (ArithmeticException failure) {
      throw invalid(path, "duration is too large");
    }
    return value;
  }

  private static String nonBlank(String value, String path) {
    if (value == null || value.isBlank()) {
      throw invalid(path, "a nonblank value is required");
    }
    return value;
  }

  private static void absent(String value, String path) {
    if (value != null) {
      throw invalid(path, "bean is only supported for custom components");
    }
  }

  private static IllegalStateException invalid(String path, String detail) {
    return new IllegalStateException("Invalid Kervan configuration at " + path + ": " + detail);
  }

  @SuppressWarnings("unchecked")
  private static CandidateRetriever<Object, Object> castRetriever(CandidateRetriever<?, ?> value) {
    return (CandidateRetriever<Object, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static FusionStrategy<Object> castFusion(FusionStrategy<?> value) {
    return (FusionStrategy<Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static Ranker<Object, Object> castRanker(Ranker<?, ?> value) {
    return (Ranker<Object, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static RankingPolicy<Object, Object> castPolicy(RankingPolicy<?, ?> value) {
    return (RankingPolicy<Object, Object>) value;
  }

  record PipelineEntry(RankingPipeline<?, ?> pipeline, Class<?> queryType, Class<?> idType) {}

  private record TypePair(Class<?> queryType, Class<?> idType) {
    TypePair withQuery(Class<?> found, String path, String beanName) {
      return new TypePair(consistent(queryType, found, path, beanName, "query"), idType);
    }

    TypePair withId(Class<?> found, String path, String beanName) {
      return new TypePair(queryType, consistent(idType, found, path, beanName, "candidate ID"));
    }

    private static Class<?> consistent(
        Class<?> previous, Class<?> found, String path, String beanName, String label) {
      if (previous != null && found != null && !previous.equals(found)) {
        throw invalid(
            path,
            "bean '"
                + beanName
                + "' has "
                + label
                + " type "
                + found.getName()
                + ", which conflicts with "
                + previous.getName());
      }
      return previous == null ? found : previous;
    }
  }
}

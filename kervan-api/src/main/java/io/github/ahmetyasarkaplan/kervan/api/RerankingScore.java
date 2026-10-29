package io.github.ahmetyasarkaplan.kervan.api;

/**
 * Keeps the fusion and model scores separate; the two values need not share a numeric scale.
 *
 * @param fusionScore finite score before the first batch reranking stage
 * @param rerankScore finite higher-is-better score from the latest batch reranking stage
 */
public record RerankingScore(double fusionScore, double rerankScore) {

  /** Validates both scores without comparing or combining their scales. */
  public RerankingScore {
    if (!Double.isFinite(fusionScore) || !Double.isFinite(rerankScore)) {
      throw new IllegalArgumentException("fusionScore and rerankScore must be finite");
    }
  }
}

package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.Ranker;
import io.github.ahmetyasarkaplan.kervan.api.RankingContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;

/**
 * Greedily diversifies a bounded candidate window using maximal marginal relevance (MMR).
 *
 * <p>Each selection maximizes {@code lambda * relevance - (1 - lambda) * maxSimilarity}. Similarity
 * to an empty selection is zero. Equal utilities retain input order, without comparing IDs. Both
 * callbacks must return finite values in [0, 1]. Similarity is evaluated as {@code
 * similarity(candidate, selected)}; supply a symmetric function for the usual MMR interpretation.
 * Relevance is evaluated once for every candidate, including when lambda is zero. When lambda is
 * one, similarity is unused and is not evaluated.
 *
 * <p>The immutable result contains original candidate objects: selection utilities never replace
 * scores, provenance or attributes. Incremental similarity maxima require O(n) space and O(n * k)
 * work for n inputs and k outputs. Input exceeding the configured bound is rejected, not truncated.
 *
 * <p>This ranker checks the context budget and thread interrupt status between callbacks and loop
 * iterations, throwing {@link IllegalStateException} without returning a partial selection.
 * Callbacks must cooperate with cancellation; a blocked callback cannot be preempted here. No
 * executor or fallback is created. A surrounding pipeline owns execution and failure policy.
 * Callback failures are sanitized without retaining their causes, and an existing interrupt flag is
 * preserved.
 *
 * @param <Q> application query type
 * @param <ID> canonical candidate identifier type
 */
public final class MaximalMarginalRelevanceRanker<Q, ID> implements Ranker<Q, ID> {
  private final double lambda;
  private final int maxCandidates;
  private final ToDoubleFunction<? super RankedCandidate<ID>> relevance;
  private final ToDoubleBiFunction<? super RankedCandidate<ID>, ? super RankedCandidate<ID>>
      similarity;

  /**
   * Creates a bounded MMR ranker using application-owned relevance and similarity functions.
   *
   * @param lambda relevance weight in [0, 1]; zero favors novelty, one favors relevance
   * @param maxCandidates positive maximum accepted input size
   * @param relevance function supplying normalized relevance in [0, 1]
   * @param similarity function supplying normalized pairwise similarity in [0, 1]
   */
  public MaximalMarginalRelevanceRanker(
      double lambda,
      int maxCandidates,
      ToDoubleFunction<? super RankedCandidate<ID>> relevance,
      ToDoubleBiFunction<? super RankedCandidate<ID>, ? super RankedCandidate<ID>> similarity) {
    requireNormalized(lambda, "lambda");
    if (maxCandidates <= 0) {
      throw new IllegalArgumentException("maxCandidates must be positive");
    }
    this.lambda = lambda;
    this.maxCandidates = maxCandidates;
    this.relevance = Objects.requireNonNull(relevance, "relevance must not be null");
    this.similarity = Objects.requireNonNull(similarity, "similarity must not be null");
  }

  @Override
  public List<RankedCandidate<ID>> rank(
      Q query, List<RankedCandidate<ID>> candidates, RankingContext context) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(candidates, "candidates must not be null");
    if (candidates.size() > maxCandidates) {
      throw new IllegalArgumentException("candidates exceed maxCandidates");
    }
    var input = List.copyOf(candidates);
    var ids = new HashSet<ID>();
    for (RankedCandidate<ID> candidate : input) {
      requireActive(context);
      if (!ids.add(candidate.id())) {
        throw new IllegalArgumentException("candidates must have unique IDs");
      }
    }
    if (input.isEmpty()) {
      return List.of();
    }

    var relevanceScores = new double[input.size()];
    for (int index = 0; index < input.size(); index++) {
      requireActive(context);
      try {
        relevanceScores[index] = relevance.applyAsDouble(input.get(index));
        requireNormalized(relevanceScores[index], "relevance");
      } catch (RuntimeException failure) {
        throw new IllegalStateException("MMR relevance failed or returned an invalid value");
      }
      requireActive(context);
    }

    int outputSize = Math.min(input.size(), context.candidateLimit());
    var output = new ArrayList<RankedCandidate<ID>>(outputSize);
    var selected = new boolean[input.size()];
    var maxSimilarities = new double[input.size()];
    while (output.size() < outputSize) {
      requireActive(context);
      int best = -1;
      double bestUtility = Double.NEGATIVE_INFINITY;
      for (int index = 0; index < input.size(); index++) {
        requireActive(context);
        if (!selected[index]) {
          double utility =
              lambda * relevanceScores[index] - (1.0 - lambda) * maxSimilarities[index];
          if (best < 0 || utility > bestUtility) {
            best = index;
            bestUtility = utility;
          }
        }
      }
      selected[best] = true;
      var next = input.get(best);
      output.add(next);
      if (output.size() == outputSize || lambda == 1.0) {
        continue;
      }
      for (int index = 0; index < input.size(); index++) {
        requireActive(context);
        if (!selected[index]) {
          double value;
          try {
            value = similarity.applyAsDouble(input.get(index), next);
            requireNormalized(value, "similarity");
          } catch (RuntimeException failure) {
            throw new IllegalStateException("MMR similarity failed or returned an invalid value");
          }
          requireActive(context);
          maxSimilarities[index] = Math.max(maxSimilarities[index], value);
        }
      }
    }
    requireActive(context);
    return List.copyOf(output);
  }

  private static void requireNormalized(double value, String name) {
    if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
    }
  }

  private static void requireActive(RankingContext context) {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("MMR ranking interrupted");
    }
    if (context.remainingBudget().isZero()) {
      throw new IllegalStateException("MMR ranking budget exhausted");
    }
  }
}

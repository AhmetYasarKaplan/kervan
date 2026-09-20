package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.Candidate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Merges candidates with equal IDs while preserving deterministic first-seen behavior. */
final class CandidateMerger {

  private CandidateMerger() {}

  static <ID> List<Candidate<ID>> merge(List<Candidate<ID>> candidates) {
    Objects.requireNonNull(candidates, "candidates must not be null");

    Map<ID, Candidate<ID>> candidatesById = new LinkedHashMap<>();
    for (Candidate<ID> candidate : candidates) {
      Objects.requireNonNull(candidate, "candidates must not contain null elements");
      candidatesById.merge(candidate.id(), candidate, CandidateMerger::mergeCandidates);
    }

    return List.copyOf(candidatesById.values());
  }

  private static <ID> Candidate<ID> mergeCandidates(Candidate<ID> first, Candidate<ID> duplicate) {
    var sources = new LinkedHashSet<>(first.sources());
    sources.addAll(duplicate.sources());

    var sourceScores = new LinkedHashMap<>(first.sourceScores());
    duplicate
        .sourceScores()
        .forEach(
            (source, score) ->
                sourceScores.merge(
                    source,
                    score,
                    (firstScore, duplicateScore) -> {
                      if (Double.compare(firstScore, duplicateScore) != 0) {
                        throw new IllegalArgumentException(
                            "Conflicting scores for source: " + source);
                      }
                      return firstScore;
                    }));

    var attributes = new LinkedHashMap<>(first.attributes());
    duplicate.attributes().forEach(attributes::putIfAbsent);

    return new Candidate<>(first.id(), first.score(), sources, sourceScores, attributes);
  }
}

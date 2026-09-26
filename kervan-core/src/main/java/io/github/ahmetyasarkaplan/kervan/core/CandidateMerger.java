package io.github.ahmetyasarkaplan.kervan.core;

import io.github.ahmetyasarkaplan.kervan.api.RankedCandidate;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalResult;
import io.github.ahmetyasarkaplan.kervan.api.SourceContribution;
import io.github.ahmetyasarkaplan.kervan.api.SourceHit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Merges equal canonical IDs while retaining one original contribution per provider. */
final class CandidateMerger {

  private CandidateMerger() {}

  static <ID> List<RankedCandidate<ID>> merge(List<RetrievalResult<ID>> results) {
    Objects.requireNonNull(results, "results must not be null");
    Map<ID, Accumulator<ID>> byId = new LinkedHashMap<>();

    for (RetrievalResult<ID> result : results) {
      Objects.requireNonNull(result, "results must not contain null elements");
      Set<ID> seenInSource = new HashSet<>();
      for (SourceHit<ID> hit : result.candidates()) {
        ID id = hit.candidate().id();
        if (!seenInSource.add(id)) {
          continue;
        }
        Accumulator<ID> accumulated = byId.get(id);
        if (accumulated == null) {
          byId.put(id, new Accumulator<>(id, hit, result.retrieverName()));
        } else {
          accumulated.add(hit, result.retrieverName());
        }
      }
    }

    return byId.values().stream().map(Accumulator::toCandidate).toList();
  }

  private static final class Accumulator<ID> {
    private final ID id;
    private final double firstScore;
    private final List<SourceContribution> contributions = new ArrayList<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    private Accumulator(ID id, SourceHit<ID> firstHit, String source) {
      this.id = id;
      this.firstScore = firstHit.candidate().score();
      add(firstHit, source);
    }

    private void add(SourceHit<ID> hit, String source) {
      contributions.add(new SourceContribution(source, hit.rank(), hit.candidate().score()));
      hit.candidate().attributes().forEach(attributes::putIfAbsent);
    }

    private RankedCandidate<ID> toCandidate() {
      return new RankedCandidate<>(id, firstScore, contributions, attributes);
    }
  }
}

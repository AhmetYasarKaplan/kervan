package io.github.ahmetyasarkaplan.kervan.example;

import java.util.List;
import java.util.Objects;

/** The application supplies a vector; Kervan does not create embeddings. */
public record ProductQuery(String text, List<Float> vector) {
  public ProductQuery {
    Objects.requireNonNull(text, "text must not be null");
    if (text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    vector = List.copyOf(Objects.requireNonNull(vector, "vector must not be null"));
    if (vector.size() != 3 || vector.stream().anyMatch(value -> !Float.isFinite(value))) {
      throw new IllegalArgumentException("demo vector must contain three finite values");
    }
  }
}

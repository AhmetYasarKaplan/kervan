package io.github.ahmetyasarkaplan.kervan.example;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Seeds three small deterministic documents for the runnable example. */
final class DemoCatalog {
  static final String INDEX = "kervan-demo-products";
  private static final List<Product> PRODUCTS =
      List.of(
          new Product("catalog:camera-kit", "camera kit", List.of(1.0, 0.0, 0.0)),
          new Product("catalog:tripod", "camera tripod", List.of(0.8, 0.2, 0.0)),
          new Product("catalog:lens", "portrait lens", List.of(0.0, 1.0, 0.0)));

  private DemoCatalog() {}

  static void seed(ElasticsearchClient client) throws IOException {
    if (!client.indices().exists(request -> request.index(INDEX)).value()) {
      client
          .indices()
          .create(
              request ->
                  request
                      .index(INDEX)
                      .withJson(
                          new StringReader(
                              """
                              {
                                "mappings": {
                                  "properties": {
                                    "title": {"type": "text"},
                                    "vector": {
                                      "type": "dense_vector",
                                      "dims": 3,
                                      "index": true,
                                      "similarity": "cosine"
                                    }
                                  }
                                }
                              }
                              """)));
    }
    for (Product product : PRODUCTS) {
      index(client, product.id().substring("catalog:".length()), product.title(), product.vector());
    }
    client.indices().refresh(request -> request.index(INDEX));
  }

  // One application-owned batch lookup. A real application can use a catalog bulk endpoint.
  static Map<String, Product> loadByIds(List<String> ids) {
    Map<String, Product> found = new LinkedHashMap<>();
    for (String id : ids) {
      Product product =
          PRODUCTS.stream()
              .filter(value -> value.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("unknown demo product"));
      found.put(id, product);
    }
    return Map.copyOf(found);
  }

  record Product(String id, String title, List<Double> vector) {}

  private static void index(
      ElasticsearchClient client, String id, String title, List<Double> vector) throws IOException {
    client.index(
        request -> request.index(INDEX).id(id).document(Map.of("title", title, "vector", vector)));
  }
}

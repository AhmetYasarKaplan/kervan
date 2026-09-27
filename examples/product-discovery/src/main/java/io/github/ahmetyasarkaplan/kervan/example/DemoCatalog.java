package io.github.ahmetyasarkaplan.kervan.example;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

/** Seeds three small deterministic documents for the runnable example. */
final class DemoCatalog {
  static final String INDEX = "kervan-demo-products";

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
    index(client, "camera-kit", "camera kit", List.of(1.0, 0.0, 0.0));
    index(client, "tripod", "camera tripod", List.of(0.8, 0.2, 0.0));
    index(client, "lens", "portrait lens", List.of(0.0, 1.0, 0.0));
    client.indices().refresh(request -> request.index(INDEX));
  }

  private static void index(
      ElasticsearchClient client, String id, String title, List<Double> vector) throws IOException {
    client.index(
        request -> request.index(INDEX).id(id).document(Map.of("title", title, "vector", vector)));
  }
}

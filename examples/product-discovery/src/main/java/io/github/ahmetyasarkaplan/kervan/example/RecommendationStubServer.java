package io.github.ahmetyasarkaplan.kervan.example;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** A separate demo process standing in for a remotely operated recommendation provider. */
public final class RecommendationStubServer {
  private RecommendationStubServer() {}

  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8085), 0);
    server.createContext(
        "/recommendations",
        exchange -> {
          byte[] body = "camera-kit\t0.95\ntripod\t0.80\n".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    System.out.println("Demo recommendation provider listening on http://127.0.0.1:8085");
  }
}

package io.github.ahmetyasarkaplan.kervan.example;

import io.github.ahmetyasarkaplan.kervan.api.CandidateRetriever;
import io.github.ahmetyasarkaplan.kervan.api.RetrievalContext;
import io.github.ahmetyasarkaplan.kervan.api.SourceCandidate;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Example adapter for a separate HTTP recommendation service with a small tab-separated response.
 */
public final class RecommendationHttpRetriever implements CandidateRetriever<ProductQuery, String> {
  private static final int MAX_RESPONSE_BYTES = 64 * 1024;
  private final URI endpoint;
  private final HttpClient client;

  /**
   * Creates an HTTP provider adapter with a bounded connection timeout.
   *
   * @param endpoint recommendation service URL without a query or fragment
   */
  public RecommendationHttpRetriever(URI endpoint) {
    this(endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build());
  }

  RecommendationHttpRetriever(URI endpoint, HttpClient client) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
    this.client = Objects.requireNonNull(client, "client must not be null");
    if (!List.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null) {
      throw new IllegalArgumentException("endpoint must be an HTTP URL");
    }
    if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
      throw new IllegalArgumentException("endpoint must not contain a query or fragment");
    }
  }

  @Override
  public List<SourceCandidate<String>> retrieve(ProductQuery query, RetrievalContext context) {
    Objects.requireNonNull(query, "query must not be null");
    Objects.requireNonNull(context, "context must not be null");
    URI requestUri =
        URI.create(
            endpoint
                + "?q="
                + URLEncoder.encode(query.text(), StandardCharsets.UTF_8)
                + "&limit="
                + context.candidateLimit());
    HttpRequest request =
        HttpRequest.newBuilder(requestUri).timeout(context.timeBudget()).GET().build();
    HttpResponse<String> response;
    try {
      response = client.send(request, ignored -> new LimitedBodySubscriber());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Recommendation request interrupted");
    } catch (IOException e) {
      for (Throwable failure = e; failure != null; failure = failure.getCause()) {
        if (failure instanceof ResponseTooLargeException) {
          throw new IllegalStateException("Recommendation response is too large");
        }
      }
      throw new IllegalStateException("Recommendation request failed");
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Recommendation provider returned HTTP " + response.statusCode());
    }
    String body = response.body();
    List<SourceCandidate<String>> candidates = new ArrayList<>();
    for (String line : body.lines().toList()) {
      if (candidates.size() == context.candidateLimit()) {
        break;
      }
      String[] fields = line.split("\t", -1);
      if (fields.length != 2 || fields[0].isBlank()) {
        throw new IllegalStateException("Invalid recommendation response");
      }
      try {
        candidates.add(SourceCandidate.of("catalog:" + fields[0], Double.parseDouble(fields[1])));
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException("Invalid recommendation response");
      }
    }
    return List.copyOf(candidates);
  }

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<String> {
    private final HttpResponse.BodySubscriber<String> delegate =
        HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
    private Flow.Subscription subscription;
    private int remainingBytes = MAX_RESPONSE_BYTES;
    private boolean finished;

    @Override
    public CompletionStage<String> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      if (this.subscription != null) {
        subscription.cancel();
        return;
      }
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (finished) {
        return;
      }
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > remainingBytes) {
          finished = true;
          subscription.cancel();
          delegate.onError(new ResponseTooLargeException());
          return;
        }
        remainingBytes -= buffer.remaining();
      }
      delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable failure) {
      if (!finished) {
        finished = true;
        delegate.onError(failure);
      }
    }

    @Override
    public void onComplete() {
      if (!finished) {
        finished = true;
        delegate.onComplete();
      }
    }
  }

  private static final class ResponseTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;
  }
}

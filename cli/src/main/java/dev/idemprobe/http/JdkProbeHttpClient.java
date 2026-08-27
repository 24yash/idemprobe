package dev.idemprobe.http;

import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.Invocation;
import dev.idemprobe.engine.InvocationResult;
import dev.idemprobe.engine.TransportFailure;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdkProbeHttpClient implements ProbeHttpClient {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_RESPONSE_BODY_BYTES = 1_048_576;

    private static final Pattern CHARSET_PARAMETER =
            Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\\\"?([^;\\\"\\s]+)");

    private final HttpClient httpClient;

    public JdkProbeHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    public JdkProbeHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public InvocationResult execute(Invocation invocation) {
        long start = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(invocation.request(), (name, value) -> true)
                .timeout(REQUEST_TIMEOUT)
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    request, ignored -> new LimitedBodySubscriber(MAX_RESPONSE_BODY_BYTES));
            return new HttpInvocationResult(
                    invocation.index(),
                    invocation.phase(),
                    response.statusCode(),
                    new String(response.body(), responseCharset(response)),
                    elapsedSince(start));
        } catch (IOException exception) {
            return new TransportFailure(
                    invocation.index(),
                    invocation.phase(),
                    failureMessage(exception),
                    elapsedSince(start));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new TransportFailure(
                    invocation.index(), invocation.phase(), "request interrupted", elapsedSince(start));
        }
    }

    private Duration elapsedSince(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private String failureMessage(IOException exception) {
        if (hasCause(exception, ResponseBodyTooLargeException.class)) {
            return "response body exceeded " + MAX_RESPONSE_BODY_BYTES + "-byte limit";
        }
        if (hasCause(exception, HttpConnectTimeoutException.class)) {
            return "connection timed out after " + CONNECT_TIMEOUT.toSeconds() + " seconds";
        }
        if (hasCause(exception, HttpTimeoutException.class)) {
            return "request timed out after " + REQUEST_TIMEOUT.toSeconds() + " seconds";
        }
        return "HTTP transport failed (" + exception.getClass().getSimpleName() + ")";
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Charset responseCharset(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        Matcher matcher = CHARSET_PARAMETER.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (IllegalArgumentException invalidCharset) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximumBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();

        private Flow.Subscription subscription;
        private int receivedBytes;

        private LimitedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int chunkSize = buffer.remaining();
                if (chunkSize > maximumBytes - receivedBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseBodyTooLargeException());
                    return;
                }
                byte[] chunk = new byte[chunkSize];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                receivedBytes += chunkSize;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }

    private static final class ResponseBodyTooLargeException extends RuntimeException {}
}

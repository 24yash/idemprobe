package dev.idemprobe.http;

import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.Invocation;
import dev.idemprobe.engine.InvocationResult;
import dev.idemprobe.engine.TransportFailure;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class JdkProbeHttpClient implements ProbeHttpClient {

    private final HttpClient httpClient;

    public JdkProbeHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public InvocationResult execute(Invocation invocation) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(
                    invocation.request(), HttpResponse.BodyHandlers.ofString());
            return new HttpInvocationResult(
                    invocation.index(),
                    invocation.phase(),
                    response.statusCode(),
                    response.body(),
                    elapsedSince(start));
        } catch (IOException exception) {
            return new TransportFailure(
                    invocation.index(), invocation.phase(), failureMessage(exception), elapsedSince(start));
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
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}

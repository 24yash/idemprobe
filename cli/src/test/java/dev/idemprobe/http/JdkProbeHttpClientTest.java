package dev.idemprobe.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.Invocation;
import dev.idemprobe.engine.InvocationPhase;
import dev.idemprobe.engine.InvocationResult;
import dev.idemprobe.engine.TransportFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class JdkProbeHttpClientTest {

    private HttpServer server;
    private ProbeHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new JdkProbeHttpClient(HttpClient.newHttpClient());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void capturesStatusBodyAndElapsedTime() {
        server.createContext("/reservations", exchange -> {
            byte[] body = "{\"reservationId\":\"r-1\"}".getBytes(UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        InvocationResult result = client.execute(invocation(serverUri("/reservations")));

        assertThat(result).isInstanceOfSatisfying(HttpInvocationResult.class, http -> {
            assertThat(http.statusCode()).isEqualTo(201);
            assertThat(http.responseBody()).contains("r-1");
            assertThat(http.elapsed()).isPositive();
        });
    }

    @Test
    void capturesNon2xxResponseAsHttpEvidence() {
        server.createContext("/unavailable", exchange -> {
            byte[] body = "{\"error\":\"temporarily unavailable\"}".getBytes(UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        InvocationResult result = client.execute(invocation(serverUri("/unavailable")));

        assertThat(result).isInstanceOfSatisfying(HttpInvocationResult.class, http -> {
            assertThat(http.statusCode()).isEqualTo(500);
            assertThat(http.responseBody()).contains("temporarily unavailable");
        });
    }

    @Test
    void decodesNonAsciiResponseBodyUsingResponseCharset() {
        server.createContext("/encoded", exchange -> {
            byte[] body = "{\"message\":\"café\"}".getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        InvocationResult result = client.execute(invocation(serverUri("/encoded")));

        assertThat(result).isInstanceOfSatisfying(HttpInvocationResult.class, http ->
                assertThat(http.responseBody()).isEqualTo("{\"message\":\"café\"}"));
    }

    @Test
    void returnsTransportFailureAndRestoresInterruptStatus() throws InterruptedException {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicReference<InvocationResult> result = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        server.createContext("/interrupted", exchange -> {
            requestReceived.countDown();
            try {
                releaseResponse.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        Thread executingThread = Thread.ofVirtual().start(() -> {
            result.set(client.execute(invocation(serverUri("/interrupted"))));
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertThat(requestReceived.await(5, TimeUnit.SECONDS)).isTrue();

            executingThread.interrupt();
            executingThread.join(5_000);

            assertThat(executingThread.isAlive()).isFalse();
            assertThat(result.get()).isInstanceOfSatisfying(TransportFailure.class, failure ->
                    assertThat(failure.message()).isEqualTo("request interrupted"));
            assertThat(interruptRestored).isTrue();
        } finally {
            releaseResponse.countDown();
            executingThread.interrupt();
            executingThread.join(5_000);
        }
    }

    @Test
    void returnsStructuredTransportFailure() {
        InvocationResult result = client.execute(invocation(URI.create("http://127.0.0.1:1/unreachable")));

        assertThat(result).isInstanceOfSatisfying(TransportFailure.class, failure ->
                assertThat(failure.message()).isNotBlank());
    }

    @Test
    @Timeout(value = 7)
    void returnsStructuredTransportFailureWhenTheServerStalls() throws InterruptedException {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/stalled", exchange -> {
            requestReceived.countDown();
            try {
                releaseResponse.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });

        try {
            InvocationResult result = client.execute(invocation(serverUri("/stalled")));

            assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result).isInstanceOfSatisfying(TransportFailure.class, failure ->
                    assertThat(failure.message()).isEqualTo("request timed out after 5 seconds"));
        } finally {
            releaseResponse.countDown();
        }
    }

    @Test
    void rejectsAnOversizedResponseWithoutReturningItsContent() {
        byte[] body = new byte[1_048_577];
        server.createContext("/oversized", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        InvocationResult result = client.execute(invocation(serverUri("/oversized")));

        assertThat(result).isInstanceOfSatisfying(TransportFailure.class, failure ->
                assertThat(failure.message())
                        .isEqualTo("response body exceeded 1048576-byte limit"));
    }

    private URI serverUri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private Invocation invocation(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .POST(BodyPublishers.ofString("{}"))
                .build();
        return new Invocation(0, InvocationPhase.SEQUENTIAL, request);
    }
}

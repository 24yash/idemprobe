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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void returnsStructuredTransportFailure() {
        InvocationResult result = client.execute(invocation(URI.create("http://127.0.0.1:1/unreachable")));

        assertThat(result).isInstanceOfSatisfying(TransportFailure.class, failure ->
                assertThat(failure.message()).isNotBlank());
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

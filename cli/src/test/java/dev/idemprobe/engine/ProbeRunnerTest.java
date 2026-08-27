package dev.idemprobe.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.idemprobe.config.AssertionSpec;
import dev.idemprobe.config.ExecutionSpec;
import dev.idemprobe.config.Scenario;
import dev.idemprobe.config.TargetSpec;
import dev.idemprobe.config.VerificationSpec;
import dev.idemprobe.http.JdkProbeHttpClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProbeRunnerTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsAllDuplicatesWithOneHeaderKeyThenVerifies() {
        List<String> keys = new CopyOnWriteArrayList<>();
        List<String> untouchedHeaders = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        AtomicBoolean verificationRanAfterDuplicates = new AtomicBoolean();
        server.createContext("/reservations", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            untouchedHeaders.add(exchange.getRequestHeaders().getFirst("X-Unchanged"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            respond(exchange, 201, "{\"reservationId\":\"r-1\"}");
        });
        server.createContext("/reservations/count", exchange -> {
            verificationRanAfterDuplicates.set(keys.size() == 23);
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            respond(exchange, 200, "{\"count\":1}");
        });
        Scenario scenario = scenario(3);
        ProbeRunner runner = new ProbeRunner(new JdkProbeHttpClient(HttpClient.newHttpClient()));

        ProbeExecution execution = runner.run(scenario, "shared-key");

        assertThat(keys).hasSize(23).containsOnly("prefix-shared-key-shared-key");
        assertThat(untouchedHeaders).hasSize(23).containsOnly("${not.a.template}");
        assertThat(bodies).hasSize(23).containsOnly("{\"probe\":\"${probe.key}\"}");
        assertThat(verificationRanAfterDuplicates).isTrue();
        assertThat(execution.invocations()).extracting(InvocationResult::index)
                .containsExactly(
                        0, 1, 2,
                        0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                        10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        assertThat(execution.invocations().subList(0, 3))
                .extracting(InvocationResult::phase)
                .containsOnly(InvocationPhase.SEQUENTIAL);
        assertThat(execution.invocations().subList(3, 23))
                .extracting(InvocationResult::phase)
                .containsOnly(InvocationPhase.CONCURRENT);
        assertThat(execution.verificationResult().phase()).isEqualTo(InvocationPhase.VERIFICATION);
        assertThat(execution.invocations()).isUnmodifiable();
    }

    private Scenario scenario(int sequentialDuplicates) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new Scenario(
                new TargetSpec(
                        "POST",
                        base.resolve("/reservations"),
                        Map.of(
                                "Idempotency-Key", "prefix-${probe.key}-${probe.key}",
                                "X-Unchanged", "${not.a.template}",
                                "Content-Type", "application/json"),
                        "{\"probe\":\"${probe.key}\"}"),
                new ExecutionSpec(sequentialDuplicates, 20),
                new AssertionSpec(Set.of(200, 201), "$.reservationId"),
                new VerificationSpec(
                        "GET",
                        base.resolve("/reservations/count"),
                        "$.count",
                        BigDecimal.ONE));
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

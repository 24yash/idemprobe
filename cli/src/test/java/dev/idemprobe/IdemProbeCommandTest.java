package dev.idemprobe;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class IdemProbeCommandTest {

    @TempDir
    Path tempDir;

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
    void helpUsesTheConfiguredOutputWriter() {
        CapturedExecution execution = execute("--help");

        assertThat(execution.exitCode()).isZero();
        assertThat(execution.out()).contains("Usage: idemprobe");
        assertThat(execution.err()).isEmpty();
    }

    @Test
    void returnsZeroAndReportsPassForStableSequentialDuplicates() throws IOException {
        server.createContext("/reservations", exchange ->
                respond(exchange, 201, "{\"reservationId\":\"r-1\"}"));
        server.createContext("/reservations/count", exchange ->
                respond(exchange, 200, "{\"count\":1}"));

        CapturedExecution execution = execute("run", writeScenario(baseUrl(), baseUrl(), "$.reservationId"));

        assertThat(execution.exitCode()).isZero();
        assertThat(execution.out())
                .contains("PASS")
                .contains("2 sequential invocations")
                .contains("20 concurrent invocations");
        assertThat(execution.err()).isEmpty();
    }

    @Test
    void returnsOneAndReportsAllSemanticViolations() throws IOException {
        AtomicInteger reservations = new AtomicInteger();
        server.createContext("/reservations", exchange -> {
            int reservation = reservations.incrementAndGet();
            respond(exchange, 201, "{\"reservationId\":\"r-" + reservation + "\"}");
        });
        server.createContext("/reservations/count", exchange ->
                respond(exchange, 200, "{\"count\":" + reservations.get() + "}"));

        CapturedExecution execution = execute("run", writeScenario(baseUrl(), baseUrl(), "$.reservationId"));

        assertThat(execution.exitCode()).isEqualTo(1);
        assertThat(execution.out())
                .contains("FAIL")
                .contains("IDENTITY_DIVERGED")
                .contains("SIDE_EFFECT_COUNT_MISMATCH");
        assertThat(execution.err()).isEmpty();
    }

    @Test
    void writesJsonToTheRequestedPathAndPreservesTheSemanticExitCode() throws IOException {
        AtomicInteger reservations = new AtomicInteger();
        server.createContext("/reservations", exchange -> {
            int reservation = reservations.incrementAndGet();
            respond(exchange, 201, "{\"reservationId\":\"r-" + reservation + "\"}");
        });
        server.createContext("/reservations/count", exchange ->
                respond(exchange, 200, "{\"count\":" + reservations.get() + "}"));
        Path report = tempDir.resolve("requested-report.json");

        CapturedExecution execution = execute(
                "run",
                "--json",
                report,
                writeScenario(baseUrl(), baseUrl(), "$.reservationId"));

        assertThat(execution.out()).isEmpty();
        assertThat(execution.err()).isEmpty();
        assertThat(execution.exitCode()).isEqualTo(1);
        assertThat(Files.readString(report))
                .contains("\"exitCode\" : 1")
                .contains("\"code\" : \"IDENTITY_DIVERGED\"")
                .doesNotContain("Idempotency-Key")
                .doesNotContain("PHONE");
    }

    @Test
    void returnsTwoAndReportsTransportFailure() throws IOException {
        server.createContext("/reservations/count", exchange ->
                respond(exchange, 200, "{\"count\":1}"));
        String unreachable = "http://127.0.0.1:1";

        CapturedExecution execution = execute(
                "run", writeScenario(unreachable, baseUrl(), "$.reservationId"));

        assertThat(execution.exitCode()).isEqualTo(2);
        assertThat(execution.out()).contains("ERROR").contains("TRANSPORT_ERROR");
        assertThat(execution.err()).isEmpty();
    }

    @Test
    void returnsTwoForMalformedResponseJson() throws IOException {
        server.createContext("/reservations", exchange ->
                respond(exchange, 201, "{not-json"));
        server.createContext("/reservations/count", exchange ->
                respond(exchange, 200, "{\"count\":1}"));

        CapturedExecution execution = execute("run", writeScenario(baseUrl(), baseUrl(), "$.reservationId"));

        assertThat(execution.exitCode()).isEqualTo(2);
        assertThat(execution.out()).contains("PARSING_ERROR");
        assertThat(execution.err()).isEmpty();
    }

    @Test
    void returnsTwoForMalformedJsonPath() throws IOException {
        server.createContext("/reservations", exchange ->
                respond(exchange, 201, "{\"reservationId\":\"r-1\"}"));
        server.createContext("/reservations/count", exchange ->
                respond(exchange, 200, "{\"count\":1}"));

        CapturedExecution execution = execute("run", writeScenario(baseUrl(), baseUrl(), "$.["));

        assertThat(execution.exitCode()).isEqualTo(2);
        assertThat(execution.out()).contains("PARSING_ERROR");
        assertThat(execution.err()).isEmpty();
    }

    @Test
    void returnsTwoAndUsesConfiguredErrorWriterForInvalidConfiguration() throws IOException {
        Path scenario = writeScenario(baseUrl(), baseUrl(), "$.reservationId");
        Files.writeString(
                scenario,
                Files.readString(scenario).replace("  expectedValue: 1\n", ""));

        CapturedExecution execution = execute("run", scenario);

        assertThat(execution.exitCode()).isEqualTo(2);
        assertThat(execution.out()).isEmpty();
        assertThat(execution.err()).contains("ERROR").contains("expectedValue");
    }

    private CapturedExecution execute(Object... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(new IdemProbeCommand());
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));

        int exitCode = commandLine.execute(java.util.Arrays.stream(args)
                .map(Object::toString)
                .toArray(String[]::new));
        return new CapturedExecution(exitCode, out.toString(), err.toString());
    }

    private Path writeScenario(String targetBase, String verificationBase, String identityPath)
            throws IOException {
        Path path = tempDir.resolve("scenario-" + System.nanoTime() + ".yaml");
        Files.writeString(path, """
                target:
                  method: POST
                  url: %s/reservations
                  headers:
                    Idempotency-Key: "${probe.key}"
                    Content-Type: application/json
                  body: '{"sku":"PHONE","quantity":1}'

                execution:
                  sequentialDuplicates: 2
                  concurrentDuplicates: 20

                assertions:
                  allowedStatuses: [200, 201]
                  sameValueAt: "%s"

                verification:
                  method: GET
                  url: %s/reservations/count
                  valueAt: "$.count"
                  expectedValue: 1
                """.formatted(targetBase, identityPath, verificationBase));
        return path;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedExecution(int exitCode, String out, String err) {}
}

package dev.idemprobe.demo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.idemprobe.IdemProbeCommand;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import picocli.CommandLine;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "idemprobe.demo.mode=vulnerable")
@Testcontainers
class VulnerableIdemProbeIT {

    private static final Path EXAMPLE_SCENARIO = Path.of(
            System.getProperty("user.dir"),
            "..",
            "examples",
            "reservation.yaml").normalize();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .waitingFor(Wait.forListeningPort());

    @LocalServerPort
    private int serverPort;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    private Path scenarioPath;

    @BeforeEach
    void prepareScenario() throws IOException {
        jdbc.execute("TRUNCATE TABLE idempotency_record, reservation");
        String scenario = Files.readString(EXAMPLE_SCENARIO, UTF_8)
                .replace("http://localhost:8080", "http://127.0.0.1:" + serverPort);
        scenarioPath = tempDir.resolve("reservation.yaml");
        Files.writeString(scenarioPath, scenario, UTF_8);
    }

    @Test
    @Timeout(30)
    void realCliReportsBothViolationsAndTwentyTwoSideEffects() {
        CliRun vulnerable = runCli();

        assertThat(vulnerable.exitCode()).isEqualTo(1);
        assertThat(vulnerable.stdout())
                .contains("IDENTITY_DIVERGED")
                .contains("SIDE_EFFECT_COUNT_MISMATCH")
                .contains("Expected 1 side effects but found 22");
        assertThat(reservationCount()).isEqualTo(22);
    }

    private CliRun runCli() {
        StringWriter output = new StringWriter();
        CommandLine cli = new CommandLine(new IdemProbeCommand());
        cli.setOut(new PrintWriter(output, true));
        int exitCode = cli.execute("run", scenarioPath.toString());
        return new CliRun(exitCode, output.toString());
    }

    private int reservationCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);
        return count == null ? 0 : count;
    }

    private record CliRun(int exitCode, String stdout) {
    }
}

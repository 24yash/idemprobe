package dev.idemprobe.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioLoaderTest {

    private static final String VALID_SCENARIO = """
            target:
              method: POST
              url: http://localhost:8080/reservations
              headers:
                Idempotency-Key: "${probe.key}"
                Content-Type: application/json
              body: '{"sku":"PHONE","quantity":1}'

            execution:
              sequentialDuplicates: 2
              concurrentDuplicates: 20

            assertions:
              allowedStatuses: [200, 201]
              sameValueAt: "$.reservationId"

            verification:
              method: GET
              url: http://localhost:8080/reservations/count
              valueAt: "$.count"
              expectedValue: 1
            """;

    @TempDir
    Path tempDir;

    @Test
    void loadsACompleteScenario() throws IOException {
        Scenario scenario = load(VALID_SCENARIO);

        assertThat(scenario.target().method()).isEqualTo("POST");
        assertThat(scenario.execution().sequentialDuplicates()).isEqualTo(2);
        assertThat(scenario.execution().concurrentDuplicates()).isEqualTo(20);
        assertThat(scenario.assertions().sameValueAt()).isEqualTo("$.reservationId");
        assertThat(scenario.verification().expectedValue()).isEqualByComparingTo("1");
    }

    @Test
    void rejectsNonPositiveDuplicateCounts() {
        String invalid = VALID_SCENARIO.replace(
                "sequentialDuplicates: 2", "sequentialDuplicates: 0");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequentialDuplicates");
    }

    @Test
    void rejectsNonPositiveConcurrentDuplicateCounts() {
        String invalid = VALID_SCENARIO.replace(
                "concurrentDuplicates: 20", "concurrentDuplicates: -1");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrentDuplicates");
    }

    @Test
    void rejectsUnknownConfiguration() {
        String invalid = VALID_SCENARIO.replace(
                "  body:", "  surpriseMode: true\n  body:");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("surpriseMode");
    }

    @Test
    void rejectsNullConfiguration() {
        String invalid = VALID_SCENARIO.replace("method: POST", "method: null");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("method");
    }

    @Test
    void rejectsBlankTargetMethod() {
        String invalid = VALID_SCENARIO.replace("method: POST", "method: ' '");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target.method");
    }

    @Test
    void rejectsBlankVerificationMethod() {
        String invalid = VALID_SCENARIO.replace("method: GET", "method: ' '");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification.method");
    }

    @Test
    void rejectsNonHttpTargetUrl() {
        String invalid = VALID_SCENARIO.replace(
                "http://localhost:8080/reservations", "ftp://localhost:8080/reservations");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target.url");
    }

    @Test
    void rejectsHttpTargetUrlWithoutHost() {
        String invalid = VALID_SCENARIO.replace(
                "http://localhost:8080/reservations", "http:/reservations");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target.url");
    }

    @Test
    void rejectsNonHttpVerificationUrl() {
        String invalid = VALID_SCENARIO.replace(
                "http://localhost:8080/reservations/count", "file:///tmp/count");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification.url");
    }

    @Test
    void rejectsTargetWithoutProbeKeyToken() {
        String invalid = VALID_SCENARIO.replace("${probe.key}", "not-a-probe-key");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("${probe.key}");
    }

    @Test
    void rejectsEmptyAllowedStatuses() {
        String invalid = VALID_SCENARIO.replace("[200, 201]", "[]");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assertions.allowedStatuses");
    }

    @Test
    void rejectsBlankIdentityJsonPath() {
        String invalid = VALID_SCENARIO.replace("$.reservationId", " ");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assertions.sameValueAt");
    }

    @Test
    void rejectsBlankVerificationJsonPath() {
        String invalid = VALID_SCENARIO.replace("$.count", " ");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verification.valueAt");
    }

    @Test
    void rejectsIncompleteVerificationConfiguration() {
        String invalid = VALID_SCENARIO.replace("  expectedValue: 1\n", "");

        assertThatThrownBy(() -> load(invalid))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expectedValue");
    }

    private Scenario load(String yaml) throws IOException {
        Path path = tempDir.resolve(UUID.randomUUID() + ".yaml");
        Files.writeString(path, yaml);
        return new ScenarioLoader().load(path);
    }
}

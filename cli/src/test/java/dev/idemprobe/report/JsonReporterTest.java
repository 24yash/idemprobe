package dev.idemprobe.report;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.InvocationPhase;
import dev.idemprobe.engine.ProbeExecution;
import dev.idemprobe.engine.TransportFailure;
import dev.idemprobe.evaluation.Finding;
import dev.idemprobe.evaluation.FindingCode;
import dev.idemprobe.evaluation.RunResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonReporterTest {

    @Test
    void writesStableMachineReadableEvidenceWithoutRequestOrResponseContent() throws IOException {
        RunResult result = new RunResult(
                new ProbeExecution(
                        List.of(
                                new TransportFailure(
                                        0,
                                        InvocationPhase.CONCURRENT,
                                        "request with secret-key failed",
                                        Duration.ofMillis(8)),
                                new HttpInvocationResult(
                                        0,
                                        InvocationPhase.SEQUENTIAL,
                                        201,
                                        "{\"reservationId\":\"private-response\"}",
                                        Duration.ofMillis(5))),
                        new HttpInvocationResult(
                                0,
                                InvocationPhase.VERIFICATION,
                                200,
                                "{\"count\":2}",
                                Duration.ofMillis(3))),
                List.of(new Finding(
                        FindingCode.IDENTITY_DIVERGED,
                        "Duplicate responses returned 2 distinct identities")));
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();

        JsonReporter reporter = new JsonReporter();
        reporter.write(result, first);
        reporter.write(result, second);

        assertThat(first.toString(UTF_8)).isEqualTo("""
                {
                  "exitCode" : 1,
                  "findings" : [ {
                    "code" : "IDENTITY_DIVERGED",
                    "message" : "Duplicate responses returned 2 distinct identities"
                  } ],
                  "invocations" : [ {
                    "index" : 0,
                    "outcome" : "HTTP",
                    "phase" : "SEQUENTIAL",
                    "statusCode" : 201
                  }, {
                    "index" : 0,
                    "outcome" : "TRANSPORT_FAILURE",
                    "phase" : "CONCURRENT"
                  } ],
                  "schemaVersion" : 1,
                  "verdict" : "FAIL",
                  "verification" : {
                    "index" : 0,
                    "outcome" : "HTTP",
                    "phase" : "VERIFICATION",
                    "statusCode" : 200
                  }
                }
                """);
        assertThat(second.toString(UTF_8)).isEqualTo(first.toString(UTF_8));
        assertThat(first.toString(UTF_8))
                .doesNotContain("secret-key")
                .doesNotContain("private-response")
                .doesNotContain("elapsed");
    }
}

package dev.idemprobe.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.idemprobe.config.AssertionSpec;
import dev.idemprobe.config.ExecutionSpec;
import dev.idemprobe.config.Scenario;
import dev.idemprobe.config.TargetSpec;
import dev.idemprobe.config.VerificationSpec;
import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.InvocationPhase;
import dev.idemprobe.engine.ProbeExecution;
import dev.idemprobe.engine.TransportFailure;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResultEvaluatorTest {

    private final Scenario scenario = scenario("$.reservationId", "$.count");
    private final ResultEvaluator evaluator = new ResultEvaluator();

    @Test
    void passesWhenAllResponsesShareIdentityAndVerificationIsOne() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 200, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void findsIdentityDivergenceAndMultipleSideEffects() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"reservationId\":\"r-2\"}")),
                        httpVerification(200, "{\"count\":2}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactlyInAnyOrder(
                        FindingCode.IDENTITY_DIVERGED,
                        FindingCode.SIDE_EFFECT_COUNT_MISMATCH);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void reportsEveryAvailableSemanticFinding() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 409, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"other\":\"r-2\"}")),
                        httpVerification(200, "{\"count\":2}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactlyInAnyOrder(
                        FindingCode.STATUS_NOT_ALLOWED,
                        FindingCode.IDENTITY_MISSING,
                        FindingCode.SIDE_EFFECT_COUNT_MISMATCH);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void returnsOperationalFailureForTransportFailureWithoutDiscardingSemanticFindings() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(
                                new TransportFailure(
                                        0,
                                        InvocationPhase.SEQUENTIAL,
                                        "connection refused",
                                        Duration.ofMillis(10)),
                                http(1, 409, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactlyInAnyOrder(
                        FindingCode.TRANSPORT_ERROR,
                        FindingCode.STATUS_NOT_ALLOWED);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void returnsOperationalFailureForMalformedResponseJson() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{not-json"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.PARSING_ERROR);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void returnsOperationalFailureForMalformedJsonPath() {
        RunResult result = evaluator.evaluate(
                scenario("$.[", "$.count"),
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.PARSING_ERROR);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void returnsOperationalFailureWhenVerificationIsNotSuccessfulHttp() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(503, "{\"count\":1}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.VERIFICATION_STATUS_ERROR);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void returnsOperationalFailureWhenVerificationValueIsNotNumeric() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":\"one\"}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.PARSING_ERROR);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void returnsOperationalFailureForExecutionCountMismatch() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.EXECUTION_INVALID);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void treatsScaleEquivalentNumericIdentitiesAsEqual() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":1}"),
                                http(1, 201, "{\"reservationId\":1.0}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void treatsScaleEquivalentNestedNumericIdentitiesAsEqual() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(
                                http(0, 201, "{\"reservationId\":{\"parts\":[1,2.0]}}"),
                                http(1, 201, "{\"reservationId\":{\"parts\":[1.0,2]}}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void recordsEmptyBodyWithoutDiscardingOtherFindings() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, ""),
                                http(1, 409, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":2}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactlyInAnyOrder(
                        FindingCode.PARSING_ERROR,
                        FindingCode.STATUS_NOT_ALLOWED,
                        FindingCode.SIDE_EFFECT_COUNT_MISMATCH);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void recordsJsonNullIdentityAsMissing() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "null"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":1}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.IDENTITY_MISSING);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void recordsJsonNullVerificationAsParsingError() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "null")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.PARSING_ERROR);
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void comparesHugeVerificationNumberWithoutOverflow() {
        RunResult result = evaluator.evaluate(
                scenario,
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(200, "{\"count\":1e400}")));

        assertThat(result.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.SIDE_EFFECT_COUNT_MISMATCH);
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void preservesHighPrecisionVerificationNumber() {
        BigDecimal expected = new BigDecimal("0.123456789012345678901234567890123456789");
        RunResult result = evaluator.evaluate(
                scenario("$.reservationId", "$.count", expected),
                new ProbeExecution(
                        List.of(http(0, 201, "{\"reservationId\":\"r-1\"}"),
                                http(1, 201, "{\"reservationId\":\"r-1\"}")),
                        httpVerification(
                                200,
                                "{\"count\":0.123456789012345678901234567890123456789}")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.exitCode()).isZero();
    }

    private static Scenario scenario(String identityPath, String verificationPath) {
        return scenario(identityPath, verificationPath, BigDecimal.ONE);
    }

    private static Scenario scenario(
            String identityPath,
            String verificationPath,
            BigDecimal expectedVerificationValue) {
        return new Scenario(
                new TargetSpec(
                        "POST",
                        URI.create("http://localhost:8080/reservations"),
                        Map.of(
                                "Idempotency-Key", "${probe.key}",
                                "Content-Type", "application/json"),
                        "{\"sku\":\"PHONE\",\"quantity\":1}"),
                new ExecutionSpec(2, 20),
                new AssertionSpec(Set.of(200, 201), identityPath),
                new VerificationSpec(
                        "GET",
                        URI.create("http://localhost:8080/reservations/count"),
                        verificationPath,
                        expectedVerificationValue));
    }

    private static HttpInvocationResult http(int index, int status, String body) {
        return new HttpInvocationResult(
                index, InvocationPhase.SEQUENTIAL, status, body, Duration.ofMillis(10));
    }

    private static HttpInvocationResult httpVerification(int status, String body) {
        return new HttpInvocationResult(
                0, InvocationPhase.VERIFICATION, status, body, Duration.ofMillis(10));
    }
}

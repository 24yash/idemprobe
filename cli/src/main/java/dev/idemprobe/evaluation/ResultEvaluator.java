package dev.idemprobe.evaluation;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import dev.idemprobe.config.Scenario;
import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.InvocationPhase;
import dev.idemprobe.engine.InvocationResult;
import dev.idemprobe.engine.ProbeExecution;
import dev.idemprobe.engine.TransportFailure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ResultEvaluator {

    public RunResult evaluate(Scenario scenario, ProbeExecution execution) {
        List<Finding> findings = new ArrayList<>();
        if (!hasExpectedShape(scenario, execution)) {
            findings.add(new Finding(
                    FindingCode.EXECUTION_INVALID,
                    "Execution evidence does not match the sequential plan"));
            return new RunResult(execution, findings);
        }

        Set<Object> identities = new LinkedHashSet<>();
        JsonPath identityPath = compilePath(
                scenario.assertions().sameValueAt(), "identity", findings);
        for (InvocationResult invocation : execution.invocations()) {
            if (invocation instanceof TransportFailure failure) {
                findings.add(transportFinding(failure));
                continue;
            }

            HttpInvocationResult http = (HttpInvocationResult) invocation;
            if (!scenario.assertions().allowedStatuses().contains(http.statusCode())) {
                findings.add(new Finding(
                        FindingCode.STATUS_NOT_ALLOWED,
                        "Sequential invocation " + http.index() + " returned HTTP " + http.statusCode()));
            }
            if (identityPath != null) {
                readIdentity(http, identityPath, findings, identities);
            }
        }

        if (identities.size() > 1) {
            findings.add(new Finding(
                    FindingCode.IDENTITY_DIVERGED,
                    "Sequential responses returned different identities: " + identities));
        }

        evaluateVerification(scenario, execution.verificationResult(), findings);
        return new RunResult(execution, findings);
    }

    private boolean hasExpectedShape(Scenario scenario, ProbeExecution execution) {
        if (execution.invocations().size() != scenario.execution().sequentialDuplicates()) {
            return false;
        }
        for (int index = 0; index < execution.invocations().size(); index++) {
            InvocationResult invocation = execution.invocations().get(index);
            if (invocation.phase() != InvocationPhase.SEQUENTIAL || invocation.index() != index) {
                return false;
            }
        }
        InvocationResult verification = execution.verificationResult();
        return verification.phase() == InvocationPhase.VERIFICATION && verification.index() == 0;
    }

    private JsonPath compilePath(String expression, String purpose, List<Finding> findings) {
        try {
            return JsonPath.compile(expression);
        } catch (InvalidPathException invalidPath) {
            findings.add(new Finding(
                    FindingCode.PARSING_ERROR,
                    "Invalid " + purpose + " JSONPath: " + invalidPath.getMessage()));
            return null;
        }
    }

    private void readIdentity(
            HttpInvocationResult http,
            JsonPath path,
            List<Finding> findings,
            Set<Object> identities) {
        try {
            Object identity = parse(http.responseBody()).read(path);
            if (identity == null) {
                findings.add(identityMissing(http.index()));
            } else {
                identities.add(identity);
            }
        } catch (PathNotFoundException missing) {
            findings.add(identityMissing(http.index()));
        } catch (InvalidJsonException malformed) {
            findings.add(new Finding(
                    FindingCode.PARSING_ERROR,
                    "Sequential invocation " + http.index() + " returned malformed JSON"));
        }
    }

    private Finding identityMissing(int index) {
        return new Finding(
                FindingCode.IDENTITY_MISSING,
                "Sequential invocation " + index + " did not contain the configured identity");
    }

    private void evaluateVerification(
            Scenario scenario,
            InvocationResult verification,
            List<Finding> findings) {
        if (verification instanceof TransportFailure failure) {
            findings.add(transportFinding(failure));
            return;
        }

        HttpInvocationResult http = (HttpInvocationResult) verification;
        if (http.statusCode() < 200 || http.statusCode() >= 300) {
            findings.add(new Finding(
                    FindingCode.VERIFICATION_STATUS_ERROR,
                    "Verification returned HTTP " + http.statusCode()));
            return;
        }

        JsonPath path = compilePath(scenario.verification().valueAt(), "verification", findings);
        if (path == null) {
            return;
        }

        Object extracted;
        try {
            extracted = parse(http.responseBody()).read(path);
        } catch (InvalidJsonException | PathNotFoundException malformed) {
            findings.add(new Finding(
                    FindingCode.PARSING_ERROR,
                    "Verification response did not contain readable JSON at the configured path"));
            return;
        }

        if (!(extracted instanceof Number number)) {
            findings.add(new Finding(
                    FindingCode.PARSING_ERROR,
                    "Verification value must be numeric"));
            return;
        }

        BigDecimal actual = new BigDecimal(number.toString());
        if (actual.compareTo(scenario.verification().expectedValue()) != 0) {
            findings.add(new Finding(
                    FindingCode.SIDE_EFFECT_COUNT_MISMATCH,
                    "Expected " + scenario.verification().expectedValue()
                            + " side effects but found " + actual));
        }
    }

    private DocumentContext parse(String body) {
        return JsonPath.parse(body);
    }

    private Finding transportFinding(TransportFailure failure) {
        return new Finding(
                FindingCode.TRANSPORT_ERROR,
                failure.phase() + " invocation " + failure.index() + ": " + failure.message());
    }
}

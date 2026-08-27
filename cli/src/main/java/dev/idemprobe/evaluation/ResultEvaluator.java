package dev.idemprobe.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import dev.idemprobe.config.Scenario;
import dev.idemprobe.engine.HttpInvocationResult;
import dev.idemprobe.engine.InvocationPhase;
import dev.idemprobe.engine.InvocationResult;
import dev.idemprobe.engine.ProbeExecution;
import dev.idemprobe.engine.TransportFailure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ResultEvaluator {
    private static final Configuration JSON_CONFIGURATION = jsonConfiguration();

    public RunResult evaluate(Scenario scenario, ProbeExecution execution) {
        List<Finding> findings = new ArrayList<>();
        if (!hasExpectedShape(scenario, execution)) {
            findings.add(new Finding(
                    FindingCode.EXECUTION_INVALID,
                    "Execution evidence does not match the duplicate plan"));
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
                        invocationLabel(http) + " returned HTTP " + http.statusCode()));
            }
            if (identityPath != null) {
                readIdentity(http, identityPath, findings, identities);
            }
        }

        if (identities.size() > 1) {
            findings.add(new Finding(
                    FindingCode.IDENTITY_DIVERGED,
                    "Duplicate responses returned " + identities.size() + " distinct identities"));
        }

        evaluateVerification(scenario, execution.verificationResult(), findings);
        return new RunResult(execution, findings);
    }

    private boolean hasExpectedShape(Scenario scenario, ProbeExecution execution) {
        int sequentialCount = scenario.execution().sequentialDuplicates();
        int concurrentCount = scenario.execution().concurrentDuplicates();
        if (execution.invocations().size() != sequentialCount + concurrentCount) {
            return false;
        }
        if (!hasIndexedPhase(
                execution.invocations(), 0, sequentialCount, InvocationPhase.SEQUENTIAL)) {
            return false;
        }
        if (!hasIndexedPhase(
                execution.invocations(), sequentialCount, concurrentCount, InvocationPhase.CONCURRENT)) {
            return false;
        }
        InvocationResult verification = execution.verificationResult();
        return verification.phase() == InvocationPhase.VERIFICATION && verification.index() == 0;
    }

    private boolean hasIndexedPhase(
            List<InvocationResult> invocations,
            int offset,
            int count,
            InvocationPhase phase) {
        for (int index = 0; index < count; index++) {
            InvocationResult invocation = invocations.get(offset + index);
            if (invocation.phase() != phase || invocation.index() != index) {
                return false;
            }
        }
        return true;
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
        if (isJsonNull(http.responseBody())) {
            findings.add(identityMissing(http));
            return;
        }
        try {
            Object identity = parse(http.responseBody()).read(path);
            if (identity == null) {
                findings.add(identityMissing(http));
            } else {
                identities.add(normalizeIdentity(identity));
            }
        } catch (PathNotFoundException missing) {
            findings.add(identityMissing(http));
        } catch (JsonPathException | IllegalArgumentException unreadable) {
            findings.add(new Finding(
                    FindingCode.PARSING_ERROR,
                    invocationLabel(http) + " returned unreadable JSON"));
        }
    }

    private Finding identityMissing(HttpInvocationResult invocation) {
        return new Finding(
                FindingCode.IDENTITY_MISSING,
                invocationLabel(invocation) + " did not contain the configured identity");
    }

    private String invocationLabel(InvocationResult invocation) {
        String phase = invocation.phase() == InvocationPhase.SEQUENTIAL ? "Sequential" : "Concurrent";
        return phase + " invocation " + invocation.index();
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
        if (isJsonNull(http.responseBody())) {
            findings.add(verificationParsingFinding());
            return;
        }

        Object extracted;
        try {
            extracted = parse(http.responseBody()).read(path);
        } catch (JsonPathException | IllegalArgumentException unreadable) {
            findings.add(verificationParsingFinding());
            return;
        }

        if (!(extracted instanceof Number number)) {
            findings.add(new Finding(
                    FindingCode.PARSING_ERROR,
                    "Verification value must be numeric"));
            return;
        }

        BigDecimal actual;
        try {
            actual = normalizeNumber(number);
        } catch (NumberFormatException unreadable) {
            findings.add(verificationParsingFinding());
            return;
        }
        if (actual.compareTo(scenario.verification().expectedValue()) != 0) {
            findings.add(new Finding(
                    FindingCode.SIDE_EFFECT_COUNT_MISMATCH,
                    "Expected " + scenario.verification().expectedValue()
                            + " side effects but found " + actual));
        }
    }

    private DocumentContext parse(String body) {
        return JsonPath.using(JSON_CONFIGURATION).parse(body);
    }

    private Object normalizeIdentity(Object value) {
        if (value instanceof Number number) {
            return normalizeNumber(number);
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(element -> normalized.add(normalizeIdentity(element)));
            return Collections.unmodifiableList(normalized);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, element) -> normalized.put(key, normalizeIdentity(element)));
            return Collections.unmodifiableMap(normalized);
        }
        return value;
    }

    private BigDecimal normalizeNumber(Number number) {
        return new BigDecimal(number.toString()).stripTrailingZeros();
    }

    private boolean isJsonNull(String body) {
        return body != null && body.strip().equals("null");
    }

    private Finding verificationParsingFinding() {
        return new Finding(
                FindingCode.PARSING_ERROR,
                "Verification response did not contain readable JSON at the configured path");
    }

    private static Configuration jsonConfiguration() {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .build();
        return Configuration.builder()
                .jsonProvider(new JacksonJsonProvider(mapper))
                .mappingProvider(new JacksonMappingProvider(mapper))
                .build();
    }

    private Finding transportFinding(TransportFailure failure) {
        return new Finding(
                FindingCode.TRANSPORT_ERROR,
                failure.phase() + " invocation " + failure.index() + ": " + failure.message());
    }
}

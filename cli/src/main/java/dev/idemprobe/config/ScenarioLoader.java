package dev.idemprobe.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

public final class ScenarioLoader {
    private static final String PROBE_KEY_TOKEN = "${probe.key}";
    private static final int MAX_DUPLICATES_PER_PHASE = 100;
    private static final int MAX_TOTAL_DUPLICATES = 100;

    private final ObjectMapper mapper = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .build();

    public Scenario load(Path path) throws IOException {
        Scenario scenario = mapper.readValue(path.toFile(), Scenario.class);
        validate(scenario);
        return scenario;
    }

    private void validate(Scenario scenario) {
        requireNonNull(scenario, "scenario");

        validateTarget(requireNonNull(scenario.target(), "target"));
        validateExecution(requireNonNull(scenario.execution(), "execution"));
        validateAssertions(requireNonNull(scenario.assertions(), "assertions"));
        validateVerification(requireNonNull(scenario.verification(), "verification"));
    }

    private void validateTarget(TargetSpec target) {
        requireNonBlank(target.method(), "target.method");
        if (!target.method().equals("POST")) {
            throw new IllegalArgumentException("target.method must be POST");
        }
        requireHttpUrl(target.url(), "target.url");
        requireNonNull(target.headers(), "target.headers");
        if (target.headers().values().stream().noneMatch(value -> value.contains(PROBE_KEY_TOKEN))) {
            throw new IllegalArgumentException("target.headers must contain " + PROBE_KEY_TOKEN);
        }
    }

    private void validateExecution(ExecutionSpec execution) {
        if (execution.sequentialDuplicates() <= 0) {
            throw new IllegalArgumentException("execution.sequentialDuplicates must be positive");
        }
        if (execution.sequentialDuplicates() > MAX_DUPLICATES_PER_PHASE) {
            throw new IllegalArgumentException(
                    "execution.sequentialDuplicates must not exceed " + MAX_DUPLICATES_PER_PHASE);
        }
        if (execution.concurrentDuplicates() <= 0) {
            throw new IllegalArgumentException("execution.concurrentDuplicates must be positive");
        }
        if (execution.concurrentDuplicates() > MAX_DUPLICATES_PER_PHASE) {
            throw new IllegalArgumentException(
                    "execution.concurrentDuplicates must not exceed " + MAX_DUPLICATES_PER_PHASE);
        }
        if (execution.sequentialDuplicates() + execution.concurrentDuplicates()
                > MAX_TOTAL_DUPLICATES) {
            throw new IllegalArgumentException(
                    "execution duplicate total must not exceed " + MAX_TOTAL_DUPLICATES);
        }
    }

    private void validateAssertions(AssertionSpec assertions) {
        requireNonNull(assertions.allowedStatuses(), "assertions.allowedStatuses");
        if (assertions.allowedStatuses().isEmpty()) {
            throw new IllegalArgumentException("assertions.allowedStatuses must not be empty");
        }
        requireNonBlank(assertions.sameValueAt(), "assertions.sameValueAt");
    }

    private void validateVerification(VerificationSpec verification) {
        requireNonBlank(verification.method(), "verification.method");
        if (!verification.method().equals("GET")) {
            throw new IllegalArgumentException("verification.method must be GET");
        }
        requireHttpUrl(verification.url(), "verification.url");
        requireNonBlank(verification.valueAt(), "verification.valueAt");
        requireNonNull(verification.expectedValue(), "verification.expectedValue");
    }

    private void requireHttpUrl(URI url, String field) {
        requireNonNull(url, field);
        String scheme = url.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || url.getHost() == null) {
            throw new IllegalArgumentException(field + " must be an http or https URL with a host");
        }
    }

    private void requireNonBlank(String value, String field) {
        requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private <T> T requireNonNull(T value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }
}

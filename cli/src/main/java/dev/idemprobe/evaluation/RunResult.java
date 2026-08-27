package dev.idemprobe.evaluation;

import dev.idemprobe.engine.ProbeExecution;
import java.util.List;
import java.util.Objects;

public record RunResult(ProbeExecution execution, List<Finding> findings) {
    public RunResult {
        execution = Objects.requireNonNull(execution, "execution");
        findings = List.copyOf(findings);
    }

    public int exitCode() {
        if (findings.stream().map(Finding::code).anyMatch(FindingCode::preventsVerdict)) {
            return 2;
        }
        return findings.isEmpty() ? 0 : 1;
    }
}

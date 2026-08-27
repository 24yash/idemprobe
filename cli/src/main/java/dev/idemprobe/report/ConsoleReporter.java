package dev.idemprobe.report;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.idemprobe.engine.InvocationPhase;
import dev.idemprobe.evaluation.Finding;
import dev.idemprobe.evaluation.RunResult;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

public final class ConsoleReporter implements Reporter {

    @Override
    public void write(RunResult result, OutputStream output) throws IOException {
        PrintWriter writer = new PrintWriter(output, false, UTF_8);
        writer.println(headline(result.exitCode()));
        long sequentialCount = result.execution().invocations().stream()
                .filter(invocation -> invocation.phase() == InvocationPhase.SEQUENTIAL)
                .count();
        long concurrentCount = result.execution().invocations().stream()
                .filter(invocation -> invocation.phase() == InvocationPhase.CONCURRENT)
                .count();
        writer.println(sequentialCount + " sequential invocations, "
                + concurrentCount + " concurrent invocations");
        for (Finding finding : result.findings()) {
            writer.println("- " + finding.code() + ": " + finding.message());
        }
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Unable to write console report");
        }
    }

    private String headline(int exitCode) {
        return switch (exitCode) {
            case 0 -> "PASS: all idempotency invariants held";
            case 1 -> "FAIL: idempotency invariants violated";
            default -> "ERROR: valid verdict unavailable";
        };
    }
}

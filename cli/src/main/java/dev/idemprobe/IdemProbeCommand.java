package dev.idemprobe;

import dev.idemprobe.config.Scenario;
import dev.idemprobe.config.ScenarioLoader;
import dev.idemprobe.engine.ProbeExecution;
import dev.idemprobe.engine.ProbeRunner;
import dev.idemprobe.evaluation.ResultEvaluator;
import dev.idemprobe.evaluation.RunResult;
import dev.idemprobe.http.JdkProbeHttpClient;
import dev.idemprobe.report.ConsoleReporter;
import dev.idemprobe.report.JsonReporter;
import dev.idemprobe.report.Reporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "idemprobe",
        version = "0.1.0",
        mixinStandardHelpOptions = true,
        subcommands = RunCommand.class)
public final class IdemProbeCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new IdemProbeCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}

@Command(name = "run", description = "Run a probe scenario", mixinStandardHelpOptions = true)
final class RunCommand implements Callable<Integer> {
    private static final List<String> SAFE_SCENARIO_FIELDS = List.of(
            "target.method",
            "target.url",
            "target.headers",
            "execution.sequentialDuplicates",
            "execution.concurrentDuplicates",
            "execution duplicate total",
            "assertions.allowedStatuses",
            "assertions.sameValueAt",
            "verification.method",
            "verification.url",
            "verification.valueAt",
            "expectedValue");

    @Parameters(index = "0", paramLabel = "SCENARIO", description = "Path to scenario YAML")
    private Path scenarioPath;

    @Option(names = "--json", paramLabel = "PATH", description = "Write JSON report to PATH")
    private Path jsonOutput;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        Scenario scenario;
        try {
            scenario = new ScenarioLoader().load(scenarioPath);
        } catch (Exception failure) {
            spec.commandLine().getErr().println("ERROR: " + safeScenarioFailure(failure));
            return 2;
        }

        try {
            ProbeRunner runner = new ProbeRunner(new JdkProbeHttpClient());
            ProbeExecution execution = runner.run(scenario, UUID.randomUUID().toString());
            RunResult result = new ResultEvaluator().evaluate(scenario, execution);
            if (jsonOutput == null) {
                writeToConsole(new ConsoleReporter(), result);
            } else {
                writeToPath(new JsonReporter(), result, jsonOutput);
            }
            return result.exitCode();
        } catch (IOException failure) {
            spec.commandLine().getErr().println("ERROR: unable to write report");
            return 2;
        } catch (IllegalArgumentException failure) {
            spec.commandLine().getErr().println("ERROR: invalid HTTP request configuration");
            return 2;
        } catch (IllegalStateException failure) {
            spec.commandLine().getErr().println("ERROR: probe execution failed");
            return 2;
        } catch (RuntimeException failure) {
            spec.commandLine().getErr().println("ERROR: unexpected probe failure");
            return 2;
        }
    }

    private void writeToConsole(Reporter reporter, RunResult result) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        reporter.write(result, output);
        spec.commandLine().getOut().print(output.toString(java.nio.charset.StandardCharsets.UTF_8));
        spec.commandLine().getOut().flush();
    }

    static void writeToPath(Reporter reporter, RunResult result, Path path) throws IOException {
        Path destination = path.toAbsolutePath();
        Path temporary = Files.createTempFile(
                destination.getParent(), "." + destination.getFileName() + "-", ".tmp");
        boolean replaced = false;
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                reporter.write(result, output);
            }
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private String safeScenarioFailure(Exception failure) {
        String message = failure.getMessage();
        if (message != null) {
            for (String field : SAFE_SCENARIO_FIELDS) {
                if (message.contains(field)) {
                    return "invalid scenario configuration: " + field;
                }
            }
        }
        return "invalid scenario configuration";
    }
}

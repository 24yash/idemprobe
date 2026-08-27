package dev.idemprobe;

import dev.idemprobe.config.Scenario;
import dev.idemprobe.config.ScenarioLoader;
import dev.idemprobe.engine.ProbeExecution;
import dev.idemprobe.engine.ProbeRunner;
import dev.idemprobe.evaluation.ResultEvaluator;
import dev.idemprobe.evaluation.RunResult;
import dev.idemprobe.http.JdkProbeHttpClient;
import dev.idemprobe.report.ConsoleReporter;
import dev.idemprobe.report.Reporter;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "idemprobe", mixinStandardHelpOptions = true, subcommands = RunCommand.class)
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

@Command(name = "run", description = "Run a probe scenario")
final class RunCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "SCENARIO", description = "Path to scenario YAML")
    private Path scenarioPath;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        try {
            Scenario scenario = new ScenarioLoader().load(scenarioPath);
            ProbeRunner runner = new ProbeRunner(new JdkProbeHttpClient(HttpClient.newHttpClient()));
            ProbeExecution execution = runner.run(scenario, UUID.randomUUID().toString());
            RunResult result = new ResultEvaluator().evaluate(scenario, execution);
            writeReport(new ConsoleReporter(), result);
            return result.exitCode();
        } catch (Exception failure) {
            spec.commandLine().getErr().println("ERROR: " + failureMessage(failure));
            return 2;
        }
    }

    private void writeReport(Reporter reporter, RunResult result) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        reporter.write(result, output);
        spec.commandLine().getOut().print(output.toString(java.nio.charset.StandardCharsets.UTF_8));
        spec.commandLine().getOut().flush();
    }

    private String failureMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}

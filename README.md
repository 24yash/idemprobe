# IdemProbe

IdemProbe is a Java 21 command-line tool for proving whether duplicate HTTP
requests with the same idempotency key produce one logical side effect.

The v0.1 demonstration runs the same scenario against vulnerable and fixed
Spring Boot reservation APIs backed by PostgreSQL.

## Quick start

Java 21 and a Docker-compatible container runtime are required. The complete
negative-to-positive proof is one command:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify
```

The proof runs `examples/reservation.yaml` through the real Picocli command and
real HTTP endpoints. It sends 2 sequential requests followed by 20 concurrent
requests, all with one generated idempotency key. The vulnerable mode exits `1`
with `IDENTITY_DIVERGED` and `SIDE_EFFECT_COUNT_MISMATCH`; its database contains
22 reservations. The fixed mode exits `0` with `PASS` and contains one
reservation.

Build the executable CLI and inspect its commands with:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -pl cli -am package
JAVA_HOME=$(/usr/libexec/java_home -v 21) java -jar cli/target/idemprobe.jar --help
```

To run the example manually, start PostgreSQL:

```bash
docker run --rm --name idemprobe-postgres \
  -e POSTGRES_DB=idemprobe \
  -e POSTGRES_USER=idemprobe \
  -e POSTGRES_PASSWORD=idemprobe \
  -p 5432:5432 postgres:17-alpine
```

In another terminal, start the deliberately vulnerable demo:

```bash
IDEMPROBE_DEMO_MODE=vulnerable JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -pl demo org.springframework.boot:spring-boot-maven-plugin:3.5.16:run
```

Then run the packaged probe:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  java -jar cli/target/idemprobe.jar run examples/reservation.yaml
```

The vulnerable command exits `1`. To see the passing result, stop both the demo
and PostgreSQL, start a fresh PostgreSQL container with the same command, restart
the demo with `IDEMPROBE_DEMO_MODE=fixed`, and rerun the unchanged scenario.

## Modules

- `cli` provides the `idemprobe` executable.
- `demo` provides vulnerable and PostgreSQL-backed fixed reservation APIs.

The default demo mode is `vulnerable`; set `IDEMPROBE_DEMO_MODE=fixed` to select
the PostgreSQL-enforced implementation.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

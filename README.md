# IdemProbe

IdemProbe is a Java 21 command-line tool for proving whether duplicate HTTP
requests with the same idempotency key produce one logical side effect.

The v0.1 demonstration will run the same scenario against vulnerable and fixed
Spring Boot reservation APIs backed by PostgreSQL.

## Build

Use Java 21 explicitly, then run the Maven Wrapper:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify
```

The repository contains two modules:

- `cli` will provide the `idemprobe` executable.
- `demo` will provide vulnerable and PostgreSQL-backed fixed reservation APIs.

The runnable scenario, full architecture, limitations, terminal recording, and
release instructions are planned for the v0.1 implementation.

## License

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

# IdemProbe

## Problem

An API can return successful responses for duplicate requests and still create
duplicate rows, reservations, charges, or other side effects. IdemProbe is a
Java 21 command-line probe that sends sequential and simultaneous duplicates
with one idempotency key, then evaluates configured HTTP evidence for stable
responses and one expected side effect.

The included Spring Boot demonstration makes the failure concrete: the
vulnerable mode turns 2 sequential plus 20 concurrent requests into 22
reservations, while the PostgreSQL-backed fixed mode produces one reservation
and one stable response.

## 30-second demo

Java 21 and a Docker-compatible runtime are required. This command builds the
project and runs the vulnerable-versus-fixed proof through real HTTP endpoints
and PostgreSQL Testcontainers:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw --batch-mode --no-transfer-progress verify
```

The vulnerable end-to-end test expects exit `1`, `IDENTITY_DIVERGED`,
`SIDE_EFFECT_COUNT_MISMATCH`, and 22 database rows. The fixed test expects exit
`0`, stable identities, and one database row. These are test assertions, not a
claim about an arbitrary target API.

## Quick start

Build the executable JAR:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw --batch-mode --no-transfer-progress -pl cli -am package
java -jar cli/target/idemprobe.jar --help
```

Start PostgreSQL for the demo:

```bash
docker run --rm --name idemprobe-postgres \
  -e POSTGRES_DB=idemprobe \
  -e POSTGRES_USER=idemprobe \
  -e POSTGRES_PASSWORD=idemprobe \
  -p 5432:5432 postgres:17-alpine
```

In another terminal, start the deliberately vulnerable API:

```bash
IDEMPROBE_DEMO_MODE=vulnerable JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -pl demo org.springframework.boot:spring-boot-maven-plugin:3.5.16:run
```

Run the scenario with the default console report:

```bash
java -jar cli/target/idemprobe.jar run examples/reservation.yaml
```

Write the same verdict as deterministic JSON instead:

```bash
java -jar cli/target/idemprobe.jar run \
  --json build/idemprobe-report.json examples/reservation.yaml
```

The parent directory must already exist. IdemProbe writes a sibling temporary
file and atomically replaces the file at the exact path passed to `--json`, so
a failed write preserves any previous report. To observe the passing mode, use
a fresh database, restart the demo with `IDEMPROBE_DEMO_MODE=fixed`, and run
the unchanged scenario again.

## Scenario format

```yaml
target:
  method: POST
  url: http://localhost:8080/reservations
  headers:
    Idempotency-Key: "${probe.key}"
    Content-Type: application/json
  body: '{"sku":"PHONE","quantity":1}'

execution:
  sequentialDuplicates: 2
  concurrentDuplicates: 20

assertions:
  allowedStatuses: [200, 201]
  sameValueAt: "$.reservationId"

verification:
  method: GET
  url: http://localhost:8080/reservations/count
  valueAt: "$.count"
  expectedValue: 1
```

`target` must be a POST request. One or more occurrences of the only supported
token, `${probe.key}`, must appear in target headers; one generated UUID
replaces every occurrence for every duplicate in a run. `execution` requires
positive sequential and concurrent counts, caps each phase at 100, and caps
their sum at 100. `assertions` defines accepted response statuses and the
JSONPath used to compare identities. `verification` is a required GET whose
numeric JSONPath value is compared with `expectedValue`. Unknown YAML fields
are rejected.

## Findings and exit codes

| Exit | Meaning |
| ---: | --- |
| `0` | All configured semantic invariants passed. |
| `1` | Requests completed, but an idempotency invariant failed. |
| `2` | Configuration, transport, parsing, or runtime failure prevented a valid verdict. |

Structured findings are `STATUS_NOT_ALLOWED`, `IDENTITY_MISSING`,
`IDENTITY_DIVERGED`, `SIDE_EFFECT_COUNT_MISMATCH`, `TRANSPORT_ERROR`,
`PARSING_ERROR`, `VERIFICATION_STATUS_ERROR`, and `EXECUTION_INVALID`.

JSON reports use schema version `1`, stable property and invocation ordering,
and no timestamps or elapsed timings. They include the exit code, verdict,
phase/outcome/status evidence, verification evidence, and findings. They do not
serialize target URLs, arbitrary request headers, request or response bodies,
or the generated idempotency key. Identity-divergence findings report only the
number of distinct identities, not their extracted values. A small report
looks like this:

```json
{
  "exitCode" : 1,
  "findings" : [ {
    "code" : "SIDE_EFFECT_COUNT_MISMATCH",
    "message" : "Expected 1 side effects but found 2"
  } ],
  "invocations" : [ {
    "index" : 0,
    "outcome" : "HTTP",
    "phase" : "SEQUENTIAL",
    "statusCode" : 201
  }, {
    "index" : 0,
    "outcome" : "HTTP",
    "phase" : "CONCURRENT",
    "statusCode" : 201
  } ],
  "schemaVersion" : 1,
  "verdict" : "FAIL",
  "verification" : {
    "index" : 0,
    "outcome" : "HTTP",
    "phase" : "VERIFICATION",
    "statusCode" : 200
  }
}
```

## Architecture

IdemProbe has five boundaries: strict YAML configuration, deterministic HTTP
execution, immutable evidence, semantic evaluation, and console or JSON
reporting. The CLI uses Java virtual threads and a shared start gate for the
concurrent phase. The fixed demo uses a PostgreSQL unique constraint as its
cross-process correctness boundary.

See [docs/architecture.md](docs/architecture.md) for the component flow,
reporting schema, and fixed-demo transaction.

## Test strategy and CI

Plain unit tests cover configuration, HTTP transport, concurrency coordination,
evaluation, reporters, and CLI exit behavior. Testcontainers integration tests
exercise vulnerable and fixed Spring Boot modes against PostgreSQL 17,
including same-key/different-payload rejection. End-to-end tests invoke the real
Picocli command over HTTP and assert both the CLI verdict and database state.

GitHub Actions uses Temurin 21, the Maven Wrapper, a read-only repository token,
and the Docker service available on GitHub-hosted Ubuntu runners. It runs:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Run the same command locally with Java 21 and Docker before opening a pull
request.

## Limitations and threat model

IdemProbe observes only the HTTP evidence configured in a scenario: response
statuses, one extracted response identity, and one required numeric
verification value. It cannot prove arbitrary hidden side effects. For example,
it cannot see an unconfigured message, email, charge, cache mutation, or write
in another datastore, and it does not prove crash recovery, durability,
cross-region behavior, or an unlimited replay window.

The v0.1 probe sends real mutating requests. Run it against disposable or
explicitly authorized environments and choose a verification endpoint that
measures the side effect you care about. Scenario files can contain sensitive
headers or payloads; protect them like other local configuration. JSON output
omits those request details, but finding messages can contain values extracted
by evaluation.

Each connection attempt is bounded to 3 seconds, each request to 5 seconds,
and each response body to 1 MiB. Timeouts and oversized responses become
`TRANSPORT_ERROR` findings and exit `2`. These fixed v0.1 safety limits are not
user-configurable.

v0.1 does not include network-fault injection, configurable retries, generic
same-key/different-payload attacks, authentication helpers, setup or teardown
workflows, HTML or JUnit reports, a Docker image, a reusable GitHub Action,
OpenAPI ingestion, a UI, or Kubernetes deployment.

## Roadmap

Potential v0.2 work includes controlled timeout and network-fault injection,
retry policies, generic payload-conflict scenarios, and additional report
formats. These are deferred, not promised release commitments.

## Contributing

Use Java 21, keep changes within the existing `cli` and `demo` module
boundaries, add behavior-focused tests, and run the full Maven `verify` command
with Docker before submitting a change. Please describe what evidence the
change adds or how it alters the verdict contract.

## License

IdemProbe is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

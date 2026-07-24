# SPEC-0001: Build, deploy, and invoke a local Lambda

## Status

- State: In progress
- Current milestone: M2
- Completion: 2 of 7 milestones
- Last updated: 2026-07-23

## Objective

Deliver one complete local Lambda workflow through the Ares CLI:

```bash
ares local start
ares build ./examples/hello-lambda
ares deploy ./examples/hello-lambda
ares invoke hello --event ./examples/hello-lambda/event.json
```

The first vertical slice supports a Java 21 Lambda packaged as a ZIP and executed in an
AWS Java base-image container through the AWS Lambda Runtime Interface Emulator (RIE).
All implementation modules live in this repository.

The AWS Lambda API remains a compatibility boundary, but users should not need the AWS
CLI, Docker commands, handler class names, role ARNs, or ZIP paths for the normal Ares
workflow.

## Instructions for an implementing AI

This document is an executable implementation specification. Follow these rules while
working through it:

1. Read `AGENTS.md`, `CONTRIBUTING.md`, `CODE_STYLE.md`, the existing ADRs, and the nearby
   production and test code before changing a module.
2. Work on exactly one milestone at a time, in order.
3. Keep the repository buildable and tested at every milestone boundary.
4. Run each milestone's verification commands before marking it complete.
5. Update the status block, milestone checkbox, and execution log after completing a
   milestone. Record commands and concise evidence, not a diary.
6. Do not mark a milestone complete when its acceptance criteria are covered only by
   mocks. Milestones that require process, HTTP, ZIP, or Docker integration must exercise
   the real boundary.
7. Do not add silent fallbacks. Unsupported runtimes, package layouts, Docker states, and
   API fields must fail with actionable messages.
8. Do not weaken formatting, static analysis, coverage, mutation testing, or existing
   tests.
9. Do not commit, push, or open a pull request unless the user explicitly asks.
10. If an assumption proves false, stop that milestone, update the decision log and this
    specification, and resume only when the replacement design preserves the objective.

An AI resuming the task starts at the first unchecked milestone. It must inspect the
working tree before assuming that the execution log reflects all local changes.

## User experience

### Start the local runtime

```bash
ares local start
```

This command runs in the foreground, logs the endpoint, and stops cleanly on interrupt.
Background daemon management is not required in this specification.

Expected output:

```text
Ares AWS Local listening on http://127.0.0.1:4566
```

### Build a function

```bash
ares build ./examples/hello-lambda
```

Expected output:

```text
Built Lambda function 'hello'
Artifact: ./examples/hello-lambda/build/ares/hello.zip
Runtime: java21
```

### Deploy a function

```bash
ares deploy ./examples/hello-lambda
```

`deploy` builds by default. `--no-build` requires a current build result.

Expected first-deployment output:

```text
Created Lambda function 'hello'
Runtime: java21
Endpoint: http://127.0.0.1:4566
```

Expected unchanged output:

```text
Lambda function 'hello' is unchanged
```

Expected update output:

```text
Updated Lambda function 'hello'
Code: <old-sha256> -> <new-sha256>
```

### Invoke a function

```bash
ares invoke hello --event ./examples/hello-lambda/event.json
```

The response payload is written to standard output. Diagnostics go to standard error so
the payload can be redirected safely.

Successful example:

```json
{"message":"Hello, Ares"}
```

Function failures return a non-zero exit code and preserve the Lambda error payload.

### CLI defaults and exit codes

- Local endpoint: `http://127.0.0.1:4566`.
- Endpoint override: `--endpoint <url>` or `ARES_AWS_LOCAL_ENDPOINT`.
- `ares invoke` uses `{}` when neither `--event` nor `--payload` is supplied.
- Exit `0`: command succeeded.
- Exit `2`: invalid CLI usage or invalid local project configuration.
- Exit `3`: local runtime unavailable.
- Exit `4`: build failed.
- Exit `5`: deployment failed.
- Exit `6`: function invocation returned a function error.
- Exit `7`: required local infrastructure, such as Docker, is unavailable.
- Exit `1`: unexpected internal failure.

Messages must name the failed command, path, function, or external dependency and include
the corrective action when known.

## Scope

### Included

- A multi-module Gradle build contained in this repository.
- One `ares` command with `local start`, `build`, `deploy`, and `invoke`.
- A declarative local function file named `ares.yaml`.
- Java 21 Lambda ZIP creation.
- Lambda create, get, update-code, update-configuration, delete, and invoke behavior needed
  by the CLI.
- In-memory function metadata and runtime-managed temporary artifact storage.
- A Docker-backed execution environment using an AWS Java 21 base image and RIE.
- `RequestHandler` and `RequestStreamHandler`.
- Warm container reuse for repeated invocations of one unchanged function revision.
- Replacement of the warm container after code or configuration changes.
- The existing `LambdaInvoker` boundary as the only trigger-to-function invocation path.
- Unit, integration, subprocess, HTTP, Docker, and CLI end-to-end tests.

### Excluded

- Runtime languages other than Java 21.
- Lambda container-image deployment through `PackageType=Image`.
- S3-backed Lambda code uploads.
- Layers, versions, aliases, destinations, SnapStart, VPCs, provisioned concurrency, and
  reserved concurrency.
- IAM authentication or role assumption. The role is stored for AWS compatibility only.
- Exact Lambda memory, CPU, networking, and sandbox parity.
- Persistent function state across local-runtime restarts.
- Background daemon installation or operating-system services.
- Automatic source watching and `ares dev`.
- Publishing the CLI or Gradle modules outside this repository.
- Dynamic event-source mapping APIs. The existing programmatic trigger engine may invoke
  deployed functions, but mapping CRUD is separate work.
- Full AWS Lambda API coverage.

## Architecture constraints

### Module layout

Create these Gradle modules incrementally:

```text
ares-aws-local-spi
ares-aws-local-lambda
ares-aws-local-lambda-docker
ares-aws-local-runtime
ares-aws-local-cli
ares-aws-local-e2e-tests
```

Responsibilities and dependency direction:

```text
ares-aws-local-spi
    ^
    |
ares-aws-local-lambda <--- ares-aws-local-lambda-docker
    ^                                  ^
    |                                  |
    +--------- ares-aws-local-runtime--+
                       ^
                       |
              ares-aws-local-cli

ares-aws-local-e2e-tests launches built CLI and runtime distributions.
```

- `ares-aws-local-spi` contains transport-neutral contracts shared across services,
  including HTTP request/response contracts, `AwsServiceAdapter`, `LambdaInvoker`, and
  `LambdaInvocationResult`.
- `ares-aws-local-lambda` contains the Lambda API adapter, function model, store, artifact
  store, deployment service, invocation coordinator, and execution-backend contracts.
- `ares-aws-local-lambda-docker` contains Docker process integration, runtime-provider
  registration, RIE environment lifecycle, and the Java 21 runtime provider.
- `ares-aws-local-runtime` remains the executable service assembly and owns the HTTP
  server, service registry, SQS implementation, trigger engine, and lifecycle.
- `ares-aws-local-cli` contains CLI parsing, project discovery, build providers, the local
  Lambda client, and the `ares` application entry point.
- `ares-aws-local-e2e-tests` contains black-box fixtures and tests. It must not become a
  production dependency.

Preserve existing public package names when moving contracts unless a package change is
necessary to remove a dependency cycle. Record any public package move in the ADR.

### Runtime-neutral execution boundary

The Lambda core must not depend on Docker or Java-specific package details:

```java
public interface LambdaExecutionBackend extends AutoCloseable {
    CompletionStage<LambdaInvocationResult> invoke(
            LambdaFunctionSnapshot function,
            LambdaArtifact artifact,
            byte[] payload);

    void invalidate(String functionName, String revisionId);
}
```

Exact names may change, but these properties may not:

- Function deployment and function execution are separate.
- The invocation input and output are raw bytes.
- Function errors are distinct from infrastructure failures.
- Backends can discard an execution environment by function revision.
- Shutdown terminates owned environments.
- The Lambda service and trigger engine do not import Docker classes.

Language-specific behavior belongs behind an immutable runtime-provider registry. Duplicate
runtime identifiers and unknown runtimes fail explicitly.

### RIE execution model

For the first runtime provider:

1. Resolve `java21` to a tested, pinned AWS Lambda Java 21 image digest.
2. Extract the immutable function artifact into a revision-specific directory.
3. Mount that directory read-only at `/var/task`.
4. Publish container port `8080` on an ephemeral loopback host port.
5. Pass the configured handler as the image command.
6. Set local credentials, region, function metadata, and the local AWS endpoint.
7. Wait for the RIE endpoint to become ready with a bounded timeout.
8. Invoke
   `/2015-03-31/functions/function/invocations` with the raw payload.
9. Reuse the container for subsequent invocations of the same revision.
10. Stop and remove the container on invalidation, timeout, process exit, or runtime
    shutdown.

Docker commands must use `ProcessBuilder` argument lists, never a shell command string.
Capture bounded standard output and error. Include the failed Docker operation and relevant
container identifier in errors without dumping environment variables.

The implementation must support Docker Desktop on macOS and Docker Engine on Linux. The
end-to-end fixture does not call another AWS service from inside the function, but the
container specification must reserve a platform-specific endpoint strategy for future
`AWS_ENDPOINT_URL` injection.

### Function project contract

The initial project descriptor is `ares.yaml`:

```yaml
schemaVersion: 1
functions:
  hello:
    runtime: java21
    architecture: arm64
    handler: example.HelloHandler
    build:
      command:
        - ./gradlew
        - lambdaZip
      artifact: build/distributions/hello.zip
```

Rules:

- Paths are relative to the directory containing `ares.yaml`.
- Build commands are argument arrays and are executed without a shell.
- `architecture` accepts `arm64` or `x86_64`.
- The first slice accepts exactly one function per `ares build <path>` invocation. Multiple
  descriptors may exist in the schema, but selecting and deploying several functions is
  follow-up work.
- The build fails when the selected artifact is missing, not a regular file, unreadable,
  empty, or not a ZIP.
- Unknown fields are rejected to catch misspellings.
- Environment variables are optional string pairs. Reserved `AWS_*` and
  `ARES_AWS_LOCAL_*` variables cannot be overridden in the first slice.
- Secrets must not be written to the generated build result or normal command output.

`ares build` writes a deterministic generated result:

```text
<function-path>/build/ares/deployment.json
```

The generated result records:

- Schema version.
- Function name.
- Runtime, architecture, and handler.
- Absolute normalized source and artifact paths.
- Artifact SHA-256 and byte size.
- Environment-variable names, but not secret redaction metadata or duplicate values.

Do not use timestamps to decide whether a build is current. Use the descriptor inputs and
artifact digest.

Future Ares Framework integration may generate this descriptor from
`META-INF/ares/handlers.json`. The first slice must not couple the local Lambda service to
that Ares-specific manifest.

### Deployment semantics

`ares deploy <path>` performs this state reconciliation:

1. Build unless `--no-build` is present.
2. Read and validate `build/ares/deployment.json`.
3. Check local-runtime health before uploading the artifact.
4. Read the existing function configuration by name.
5. Create the function when absent.
6. Update code only when the SHA-256 differs.
7. Update configuration only when supported configuration differs.
8. Report unchanged when neither differs.

The CLI must call the local Lambda HTTP API. It must not access implementation classes,
artifact directories, or function stores directly.

The local Lambda adapter follows AWS Lambda REST-JSON request, response, header, status,
and error shapes for the supported operations. Unsupported fields are rejected explicitly.
AWS SDK v2 contract tests verify the compatibility boundary even though the normal user
experience uses `ares`.

The first slice accepts direct ZIP uploads up to 10 MiB compressed and 50 MiB expanded.
This intentionally fits the current buffered HTTP request model. Exceeding either limit
returns an actionable limit error. Supporting AWS's larger documented package limits
requires a later spooled-request-body change and must not be simulated by raising an
unbounded global memory limit.

### Artifact safety and lifecycle

- Calculate SHA-256 while accepting the decoded artifact.
- Store artifacts under a runtime-owned temporary data directory, not the source tree.
- Use immutable revision directories and atomic metadata replacement.
- Reject absolute paths, parent traversal, duplicate normalized entries, NUL characters,
  unsupported symbolic links, excessive file counts, and expanded-size overflow.
- A failed create or update removes staging data and preserves the previous active
  revision.
- Delete unreferenced revisions after no invocation can use them.
- Runtime shutdown stops containers before removing temporary artifacts.
- Function state and artifacts are lost on runtime restart in this specification.

### Invocation semantics

- `RequestResponse` is the only supported invocation type.
- Accept payloads through the Lambda `Invoke` API and return raw response bytes.
- Preserve RIE function-error headers and payloads as `LambdaInvocationResult`.
- Enforce the configured function timeout by terminating the execution environment.
- A timeout or function exception is a function error.
- Docker unavailability, image-pull failure, container-start failure, and unexpected
  container exit are infrastructure failures.
- A successful HTTP transport response does not erase a Lambda function error.
- The existing SQS driver receives the same result through `LambdaInvoker`; it must not
  gain a Docker-specific path.

## Milestones

### M0 — Record the architecture and baseline

- [x] Complete M0.

Work:

- Add `ADR-0003-local-lambda-deployment-and-execution.md`.
- Record the CLI-first experience, ZIP deployment, Docker/RIE backend, runtime-provider
  registry, process-local state, module boundaries, and rejected host-JVM-only design.
- Link ADR-0001, ADR-0002, and this specification.
- Capture the current `./gradlew formatCheck check` result before moving code.

Acceptance:

- ADR status is `Proposed`.
- Existing behavior and tests are unchanged.
- The architecture explains why RIE is an execution component rather than a deployment
  control plane.

Verification:

```bash
./gradlew formatCheck check
```

### M1 — Establish modules and preserve existing behavior

- [x] Complete M1.

Work:

- Add the module skeletons from the module layout.
- Move shared contracts into `ares-aws-local-spi`.
- Keep SQS, trigger, and HTTP runtime behavior unchanged.
- Make `ares-aws-local-runtime` assemble dependencies explicitly.
- Add dependency-architecture tests or a documented Gradle dependency check that prevents
  the Lambda core from depending on Docker or the CLI.

Acceptance:

- The runtime still starts and serves health and existing SQS operations.
- No dependency cycle exists.
- Existing public packages remain source-compatible or are covered by the ADR.
- Every production module applies the repository Java conventions and quality gates.

Verification:

```bash
./gradlew projects
./gradlew formatCheck check
```

### M2 — Implement CLI project discovery and Java ZIP builds

- [ ] Complete M2.

Work:

- Add the `ares` entry point and command parser.
- Implement `ares local start` by delegating to the runtime lifecycle.
- Parse and validate `ares.yaml`.
- Execute build commands without a shell.
- Calculate the artifact digest and write `build/ares/deployment.json`.
- Add `examples/hello-lambda` as a repository module or included fixture with a Java 21
  handler, Gradle ZIP task, descriptor, and event file.
- Keep the fixture out of production distributions.

Acceptance:

- Paths containing spaces work.
- Missing tools, failed builds, absent artifacts, malformed YAML, unknown fields, bad
  runtime values, and invalid ZIPs produce the specified exit codes and actionable errors.
- Repeated builds with unchanged input produce the same artifact digest and deployment
  content.
- `ares local start` exposes the existing health endpoint and stops on interrupt.

Verification:

```bash
./gradlew :ares-aws-local-cli:installDist
./ares-aws-local-cli/build/install/ares-aws-local-cli/bin/ares \
  build ./examples/hello-lambda
unzip -l ./examples/hello-lambda/build/ares/hello.zip
./gradlew formatCheck check
```

### M3 — Implement Lambda deployment and artifact storage

- [ ] Complete M3.

Work:

- Add immutable Lambda function and revision models.
- Add thread-safe in-memory function metadata and safe temporary artifact storage.
- Implement the supported create, get, update-code, update-configuration, and delete
  routes.
- Register the Lambda adapter in the default runtime.
- Implement the CLI local Lambda client and deploy reconciliation.
- Invalidate execution environments through a no-op backend in this milestone.

Acceptance:

- A function can be created, read, updated, left unchanged, and deleted.
- A failed update leaves the previous revision active.
- ZIP traversal and expansion-limit fixtures are rejected without writing outside staging.
- CLI deploy never requires an AWS role, runtime, handler, or ZIP argument from the user;
  it derives them from the generated deployment result.
- AWS SDK v2 tests cover each supported control-plane operation and AWS-shaped errors.

Verification:

```bash
./gradlew :ares-aws-local-cli:installDist
./gradlew :ares-aws-local-runtime:run

# In another terminal:
./ares-aws-local-cli/build/install/ares-aws-local-cli/bin/ares \
  deploy ./examples/hello-lambda

./gradlew formatCheck check
```

### M4 — Add the Docker/RIE Java execution backend

- [ ] Complete M4.

Work:

- Implement the container-runtime boundary and Docker CLI adapter.
- Add the immutable runtime-provider registry and Java 21 provider.
- Pin the tested AWS Lambda Java 21 image by digest.
- Start, probe, reuse, invalidate, and stop RIE containers.
- Capture bounded logs and preserve RIE function errors.
- Implement timeout and shutdown behavior.
- Connect the production `LambdaInvoker` to the backend.

Acceptance:

- Docker is not called while using SQS without Lambda functions.
- The first invocation starts one container; the second unchanged invocation reuses it.
- Code or configuration updates replace the old container.
- Runtime shutdown leaves no containers labeled as owned by the test runtime.
- Missing Docker, stopped Docker, image-pull failure, architecture mismatch, startup
  timeout, function timeout, and unexpected container exit are distinguished.
- Container ownership uses labels containing runtime instance and function revision IDs;
  cleanup never targets unlabeled containers.

Verification:

```bash
docker version
./gradlew :ares-aws-local-lambda-docker:check
./gradlew formatCheck check
```

### M5 — Implement Invoke and the CLI end-to-end flow

- [ ] Complete M5.

Work:

- Implement the Lambda Invoke route.
- Implement `ares invoke`.
- Add fixtures for successful handlers, stream handlers, thrown exceptions, invalid
  handlers, warm-state reuse, and timeout.
- Add a black-box test that launches the installed `ares` command and local runtime as
  subprocesses.
- Exercise the real Docker/RIE container in the end-to-end test.

Acceptance:

- The objective's four-command journey works without AWS CLI or direct Docker commands.
- The successful fixture returns the expected JSON.
- Redirecting standard output captures only the function payload.
- Function errors preserve payloads and return exit `6`.
- Infrastructure failures return exit `7` or `1` according to the exit-code contract.
- The installed CLI works when invoked from outside the repository root.
- An AWS SDK v2 client can invoke the same deployed function.

Verification:

```bash
./gradlew :ares-aws-local-e2e-tests:check
./gradlew formatCheck check
```

Manual smoke test:

```bash
./ares-aws-local-cli/build/install/ares-aws-local-cli/bin/ares local start

# In another terminal:
./ares-aws-local-cli/build/install/ares-aws-local-cli/bin/ares \
  build ./examples/hello-lambda
./ares-aws-local-cli/build/install/ares-aws-local-cli/bin/ares \
  deploy ./examples/hello-lambda
./ares-aws-local-cli/build/install/ares-aws-local-cli/bin/ares \
  invoke hello --event ./examples/hello-lambda/event.json
```

### M6 — Wire deployed functions to the existing trigger boundary and document delivery

- [ ] Complete M6.

Work:

- Ensure the default runtime gives the trigger engine the production Lambda invoker.
- Add an integration test proving an existing programmatic SQS mapping can target a
  function deployed through the Lambda control plane.
- Update README with installation, prerequisites, command examples, Docker ownership,
  limitations, and troubleshooting.
- Update ADR status and implementation links without rewriting accepted history.

Acceptance:

- Manual invocation and SQS-triggered invocation use the same deployed function revision
  and execution backend.
- Trigger failure behavior remains at least once and follows ADR-0002.
- No dynamic event-source mapping API is introduced accidentally.
- Documentation identifies Java 21 and Docker as current requirements.

Verification:

```bash
./gradlew formatCheck check
npm run commitlint -- --help
git diff --check
```

## Test strategy

### Unit tests

- Descriptor parsing and validation.
- CLI argument parsing and exit-code mapping.
- Build command construction and artifact hashing.
- Function-store state transitions and update reconciliation.
- ZIP path and expansion validation.
- Runtime-provider registry validation.
- Docker argument construction without invoking Docker.
- Invocation result and error classification.

### Integration tests

- Build a real Java fixture ZIP.
- Exercise Lambda control-plane routes through raw HTTP and AWS SDK v2.
- Exercise Docker command execution against a real daemon.
- Start a real RIE Java container and invoke both handler interfaces.
- Verify warm reuse, invalidation, timeout, and cleanup.

Docker integration tests may skip only when the environment explicitly declares Docker
unavailable. The dedicated end-to-end verification used to complete M5 must run with
Docker and may not be reported as passing when skipped.

### Black-box acceptance

The final acceptance test must:

1. Build all production distributions.
2. Launch `ares local start` as a subprocess on an ephemeral port.
3. Wait for health with a bounded deadline.
4. Run `ares build` against the repository fixture.
5. Run `ares deploy` twice and verify created then unchanged output.
6. Run `ares invoke` and assert exact payload and exit code.
7. Modify or select a second fixture revision, redeploy, and assert changed behavior.
8. Stop the runtime.
9. Assert that owned Docker containers and temporary artifacts are cleaned up.

## Definition of done

This specification is complete only when all of the following are true:

- Every milestone checkbox is complete and has verification evidence.
- `ares local start`, `ares build`, `ares deploy`, and `ares invoke` work through the
  installed CLI distribution.
- The final invocation executes inside a real AWS Java 21 Lambda base-image container
  through RIE.
- The CLI requires no AWS CLI or direct Docker command from the user.
- The AWS SDK v2 compatibility tests pass for supported Lambda operations.
- Existing SQS and trigger behavior remains green.
- `./gradlew formatCheck check`, `git diff --check`, and the Docker-backed end-to-end test
  pass.
- README and ADRs describe the implemented behavior and explicit limitations.
- No production TODO, disabled check, wildcard dependency, unpinned library, or unbounded
  process output remains in the acceptance path.

## Decision log

| Date | Decision | Reason |
|---|---|---|
| 2026-07-23 | Make the Ares CLI the primary user experience. | AWS CLI compatibility is useful, but users should provide a function path rather than reconstruct Lambda API fields. |
| 2026-07-23 | Keep all initial modules in this repository. | The control plane, execution backend, CLI, and tests need coordinated contracts while the feature is young. |
| 2026-07-23 | Use Docker and RIE for the first execution backend. | This reuses the AWS runtime boundary and permits additional language providers without putting user code in the emulator JVM. |
| 2026-07-23 | Support Java 21 only in the first vertical slice. | One real runtime proves the extension points before multiplying package and toolchain behavior. |
| 2026-07-23 | Use explicit `ares.yaml` metadata initially. | Build inference across languages is separate work; the local Lambda service remains independent of Ares Framework manifests. |
| 2026-07-23 | Keep function state process-local. | Persistence is not required to prove build, deployment, invocation, and trigger integration. |

## Execution log

Add one row only after a milestone passes its verification gate.

| Milestone | Date | Commit or working tree | Verification evidence |
|---|---|---|---|
| M0 | 2026-07-23 | Working tree | `./gradlew formatCheck check` passed; ADR-0003 and architecture index added |
| M1 | 2026-07-23 | Working tree | `./gradlew formatCheck check` passed; SPI and target module skeletons added |
| M2 | — | — | Not started |
| M3 | — | — | Not started |
| M4 | — | — | Not started |
| M5 | — | — | Not started |
| M6 | — | — | Not started |

## Authoritative references

- [AWS Lambda Runtime Interface Emulator](https://github.com/aws/aws-lambda-runtime-interface-emulator)
- [AWS Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html)
- [Java Lambda ZIP and JAR packaging](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html)
- [CreateFunction API](https://docs.aws.amazon.com/lambda/latest/api/API_CreateFunction.html)
- [UpdateFunctionCode API](https://docs.aws.amazon.com/lambda/latest/api/API_UpdateFunctionCode.html)
- [Invoke API](https://docs.aws.amazon.com/lambda/latest/api/API_Invoke.html)
- [Lambda container-image requirements](https://docs.aws.amazon.com/lambda/latest/dg/images-create.html)
- [ADR-0001: AWS emulator architecture](../architecture/ADR-0001-aws-emulator-architecture.md)
- [ADR-0002: Extensible trigger engine](../architecture/ADR-0002-trigger-engine.md)

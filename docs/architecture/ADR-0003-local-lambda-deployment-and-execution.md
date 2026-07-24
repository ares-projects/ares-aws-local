---
id: ADR-0003
title: Local Lambda deployment and execution
status: Proposed
date: 2026-07-23
owners:
  - Ares AWS Local maintainers
related:
  - ADR-0001
  - ADR-0002
  - SPEC-0001
---

# ADR-0003: Local Lambda deployment and execution

## Context

Ares AWS Local needs to run user-provided Lambda functions, not only accept fake
invocations from the trigger engine. The intended developer workflow is a local Ares CLI:

```text
ares local start
ares build ./function
ares deploy ./function
ares invoke function-name --event event.json
```

The workflow must accept a normal Lambda deployment package while hiding AWS API fields,
Docker commands, handler discovery, and runtime-specific container details from the usual
developer path. AWS CLI and SDK compatibility remains important for integration tests and
for applications that already use those clients.

The implementation must also leave room for Node.js, Python, Go, Rust, and other Lambda
runtimes. A Java-only execution implementation would make the first slice easy to start
but would put language assumptions in the function store, trigger engine, or HTTP service.

## Goals

- Provide a CLI-first build, deploy, and invoke workflow.
- Preserve the AWS Lambda API as a compatibility boundary behind the CLI.
- Store immutable function revisions and deployment artifacts locally.
- Execute user code outside the emulator JVM.
- Use the AWS Lambda Runtime Interface Emulator (RIE) for the first execution backend.
- Support Java 21 ZIP packages first without preventing additional runtimes.
- Keep SQS trigger delivery dependent only on the existing `LambdaInvoker` contract.
- Make Docker lifecycle, runtime selection, and artifact handling testable boundaries.

## Non-goals

- Implement every Lambda API or AWS control-plane behavior.
- Emulate IAM authorization, role assumption, VPC networking, or production security
  isolation.
- Support Lambda container-image deployment in the first slice.
- Support layers, versions, aliases, SnapStart, destinations, or reserved concurrency.
- Persist function metadata or artifacts across local-runtime restarts.
- Build a general-purpose multi-language build system before the Java vertical slice works.

## Proposed design

The local CLI owns developer ergonomics. The runtime owns AWS-compatible service behavior.
The execution backend owns process/container lifecycle.

```text
                 +----------------------+
                 | ares CLI              |
                 | build/deploy/invoke   |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 | Lambda service API    |
                 | function store        |
                 | artifact revisions    |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 | LambdaInvoker         |
                 | execution coordinator |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 | execution backend    |
                 | Docker + RIE           |
                 +----------+-----------+
                            |
                            v
                 +----------------------+
                 | runtime provider      |
                 | Java 21 first          |
                 +----------------------+
```

### CLI and build results

The CLI receives a function directory containing `ares.yaml`. The descriptor identifies
the function, runtime, handler, architecture, build command, and artifact path. Build
commands are argument arrays and are executed without a shell.

`ares build` runs the configured build and writes a generated deployment result containing
the normalized function metadata, artifact path, size, and SHA-256. It does not modify the
source project. A future Ares Framework integration may generate the descriptor from its
handler manifest, but the Lambda service must not depend on that framework-specific file.

`ares deploy` reads the generated result and reconciles the local Lambda function through
the local HTTP API. It creates a missing function, updates changed code or configuration,
and reports unchanged state when the function revision already matches.

The CLI is the primary user experience. AWS CLI commands are compatibility and diagnostic
tools, not a prerequisite for local deployment.

### Lambda service and revisions

The Lambda service owns immutable function snapshots and revision-specific artifacts. A
revision contains at least:

- function name and ARN;
- runtime, architecture, and handler;
- timeout and supported configuration;
- sanitized environment variables;
- artifact digest and storage location; and
- a revision identifier.

Create and update operations stage and validate an artifact before replacing the active
revision. A failed update leaves the previous active revision usable. Function metadata
and artifacts are process-local in the first implementation.

The supported HTTP surface is the smallest useful Lambda REST-JSON subset:

- `CreateFunction`;
- `GetFunction` and `GetFunctionConfiguration`;
- `UpdateFunctionCode`;
- `UpdateFunctionConfiguration`;
- `DeleteFunction`; and
- synchronous `Invoke`.

Unsupported fields and package types return explicit AWS-shaped errors.

### Runtime-provider registry

The execution backend must not contain Java-specific conditionals. Runtime providers map a
Lambda runtime identifier and package type to a container specification and package
validator:

```text
runtime identifier -> runtime provider -> image, command, package rules
```

The first provider supports `java21` ZIP packages. Future providers can support managed
Node.js and Python images, or the `provided.al2023` runtime for Go and Rust binaries named
`bootstrap`. The function store, CLI deployment flow, trigger engine, and invocation
contract do not change when a provider is added.

### Docker and RIE execution

The first backend runs an AWS Lambda base image with the extracted ZIP mounted read-only at
`/var/task`. It starts RIE on an ephemeral loopback port and invokes the standard Lambda
function endpoint. The backend reuses a container for repeated invocations of an unchanged
function revision and replaces it when code or configuration changes.

RIE is intentionally only an execution component. It proxies Lambda's Runtime and
Extensions APIs for a function container; it does not emulate Lambda's orchestrator,
control plane, authentication, or function store. Ares therefore remains responsible for
deployment, resource metadata, container lifecycle, triggers, and local policy.

Docker commands use structured process arguments, owned-container labels, bounded logs, and
bounded readiness and shutdown timeouts. The backend must support Docker Desktop on macOS
and Docker Engine on Linux. The container endpoint strategy for user code calling local
AWS services is an explicit runtime configuration concern, not a hidden hard-coded host
address.

### Invocation and trigger integration

Manual invocation and trigger delivery use the same raw-byte `LambdaInvoker` boundary. The
backend returns successful payloads, function errors, and infrastructure failures as
distinct outcomes. The trigger engine remains responsible for acknowledgement and retry
semantics; it does not know whether the target is running in a Java, Node.js, Go, or Rust
container.

## Alternatives considered

### Run user handlers inside the emulator JVM

Rejected. User code could terminate or contaminate the emulator process, leak threads and
static state, conflict with emulator dependencies, and make timeout enforcement unreliable.

### Implement a custom Java worker protocol

Rejected as the first backend. It would require Ares to reproduce handler loading,
serialization, context behavior, warm lifecycle, and runtime errors. RIE and AWS base
images provide a more useful compatibility boundary.

### Use RIE as the complete Lambda implementation

Rejected. RIE does not provide function deployment, function metadata, artifact revisions,
multi-function orchestration, or event-source mappings. Those remain Ares responsibilities.

### Require the AWS CLI for deployment

Rejected. The AWS CLI exposes low-level fields such as roles, handlers, runtimes, and ZIP
paths that the Ares build result already knows. The local CLI can call the same HTTP API
without imposing that setup on developers.

### Start a new container for every invocation

Deferred. It simplifies cleanup but loses warm reuse and makes Java development needlessly
slow. The first backend reuses one environment per function revision and can add bounded
concurrency later.

## Trade-offs and consequences

### Benefits

- The normal workflow is concise and project-oriented.
- AWS API compatibility remains testable and available.
- User code is isolated from the emulator JVM.
- RIE and AWS runtime images provide a reusable multi-language boundary.
- Existing SQS trigger code continues to depend only on `LambdaInvoker`.

### Costs and risks

- Docker is required for actual function execution.
- Container startup and image downloads add latency.
- Host-to-container networking needs platform-specific testing.
- ZIP packaging is language-specific and cannot be inferred universally.
- Local execution is not a security boundary equivalent to AWS Lambda.

## Operational considerations

- Security: do not inherit production credentials into containers; inject local credentials
  and reject reserved environment overrides.
- Performance: reuse warm containers, bound process output, and avoid unbounded artifact or
  request buffering.
- Cost: do not contact AWS services unless a function explicitly overrides its endpoint.
- Observability: include function name, revision, invocation ID, container ID, and runtime
  provider in diagnostics without logging secrets.
- Failure recovery: preserve the prior function revision when deployment or initialization
  fails; terminate failed containers before retrying.
- Compatibility: follow authoritative Lambda API, packaging, and runtime documentation;
  unsupported behavior must be explicit.

## Implementation plan

The executable implementation plan is maintained in
[`SPEC-0001-local-lambda-cli.md`](../specs/SPEC-0001-local-lambda-cli.md). Its milestones
are intentionally gated:

1. Record this decision and establish module boundaries.
2. Add the CLI and Java ZIP build result.
3. Add Lambda deployment and artifact storage.
4. Add Docker/RIE execution.
5. Add synchronous invocation and CLI end-to-end tests.
6. Connect deployed functions to the existing trigger engine and document the workflow.

## Decision

Proposed: use a CLI-first local Lambda workflow backed by an AWS-compatible Lambda service,
immutable local revisions, and a runtime-provider registry. Execute the first Java 21 slice
in an AWS base-image container through RIE. Keep the execution backend behind a raw-byte
invocation contract so other Lambda runtimes can be added without changing deployment,
trigger, or service boundaries.

## Related work

- Parent architecture: [ADR-0001](./ADR-0001-aws-emulator-architecture.md).
- Trigger engine: [ADR-0002](./ADR-0002-trigger-engine.md).
- Executable implementation plan: [SPEC-0001](../specs/SPEC-0001-local-lambda-cli.md).
- [AWS Lambda Runtime Interface Emulator](https://github.com/aws/aws-lambda-runtime-interface-emulator).
- [AWS Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html).
- [Java Lambda package archives](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html).
- [Lambda container images](https://docs.aws.amazon.com/lambda/latest/dg/images-create.html).

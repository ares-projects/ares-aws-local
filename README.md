# Ares AWS Local

This repository is prepared for local AWS-related development within the Ares projects.

## Development checks

```bash
npm ci
./scripts/install-git-hooks
./gradlew formatCheck check
```

Use `./gradlew spotlessApply` to format Java and Gradle sources.

## Run the local runtime

The HTTP runtime listens on `127.0.0.1:4566` by default:

```bash
./gradlew :ares-aws-local-runtime:run
```

The listener can be configured with environment variables:

- `ARES_AWS_LOCAL_HOST` (default: `127.0.0.1`)
- `ARES_AWS_LOCAL_PORT` (default: `4566`; use `0` to let the operating system choose a port)
- `ARES_AWS_LOCAL_MAX_REQUEST_BYTES` (default: `16777216`)

The runtime exposes `GET /_ares/health`, returning a small JSON health response. The first
service slice supports SQS `CreateQueue`, `SendMessage`, `ReceiveMessage`, and
`DeleteMessage` through AWS JSON 1.0. Visibility timeouts are supported for received
messages; long polling, AWS Query/XML support, and authentication validation remain future
work.

## Deploy a local Lambda

The Ares CLI builds and deploys a function from its project directory:

```bash
./ares-aws-local-cli/build/install/ares/bin/ares build ./examples/hello-lambda
ARES_AWS_LOCAL_ENDPOINT=http://127.0.0.1:4566 \
  ./ares-aws-local-cli/build/install/ares/bin/ares deploy ./examples/hello-lambda
```

`ares deploy` derives the runtime, architecture, handler, environment, and ZIP artifact
from `ares.yaml` and reconciles the function through the local Lambda REST-JSON API. The
current execution slice supports Java 21 ZIP packages in Docker through the AWS Lambda
Runtime Interface Emulator (RIE). Invoke the deployed function with:

```bash
ARES_AWS_LOCAL_ENDPOINT=http://127.0.0.1:4566 \
  ./ares-aws-local-cli/build/install/ares/bin/ares invoke hello \
  --event ./examples/hello-lambda/event.json
```

The payload is written to standard output; diagnostics are written to standard error.
Docker must be available for invocation. Function state and artifacts are process-local,
and the local endpoint does not validate AWS authentication yet.

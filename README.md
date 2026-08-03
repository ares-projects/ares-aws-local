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

## Connect SQS to a deployed Lambda

The trigger engine can deliver SQS messages to functions deployed through the same local
Lambda service. Trigger mappings are currently configured programmatically at startup; the
AWS `CreateEventSourceMapping` API and a CLI mapping command are future work. The SQS
driver uses the same raw-byte invocation boundary and execution backend as `ares invoke`.

Delivery is at least once: successful Lambda results acknowledge the batch, while function
or infrastructure failures leave messages leased until their visibility timeout expires.
Standard SQS queues are supported; FIFO mappings, filtering, DLQs, and long polling remain
out of scope.

## Provision a CDK Cloud Assembly

Synthesize a CDK application first, then point Ares at the generated assembly:

```bash
cdk synth
ARES_AWS_LOCAL_ENDPOINT=http://127.0.0.1:4566 \
  ./ares-aws-local-cli/build/install/ares/bin/ares deploy ./cdk.out
```

The CLI detects `manifest.json`, selects the only stack, packages the assembly, and sends it
to the local runtime. For multiple stacks, select an artifact explicitly:

```bash
ares deploy ./cdk.out --stack MyStack --parameter Environment=local
```

Use `--dry-run` to print the dependency plan without creating resources. The initial
CloudFormation slice provisions `AWS::SQS::Queue` and Java 21 `AWS::Lambda::Function` resources
from bundled file assets. Unsupported resources are reported and skipped, dependent resources
are blocked, and a partial deployment returns exit code `8`. Cloud Assembly state is process-local.

The repository includes a real TypeScript CDK application. Build its Java Lambda asset and
synthesize the assembly with:

```bash
cd examples/cdk-sqs-lambda
npm ci
npm run synth
cd ../..
ARES_AWS_LOCAL_ENDPOINT=http://127.0.0.1:4566 \
  ./ares-aws-local-cli/build/install/ares/bin/ares deploy ./examples/cdk-sqs-lambda
```

The generated `cdk.out` is the input Ares parses. SQS-to-Lambda event-source mappings are
provisioned through the running trigger engine. Additional resource handlers, nested stacks, and
CloudFormation-compatible stack APIs are future slices.

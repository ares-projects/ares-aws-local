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

The runtime exposes `GET /_ares/health`, returning a small JSON health response. Other
requests currently return `501 Not Implemented`; service protocol decoding and service
emulation will be added in subsequent architecture decisions.

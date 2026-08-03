# SPEC-0002: Provision a CDK Cloud Assembly locally

## Status

- State: In progress
- Current milestone: M5
- Last updated: 2026-08-03

## Objective

Support:

```text
cdk synth
ares deploy ./cdk.out
```

The current vertical slice reads one synthesized CloudFormation stack, plans dependencies,
provisions local SQS queues and Java 21 Lambda functions from bundled CDK file assets, and
reports unsupported resources explicitly.

## Execution rules

1. Read `AGENTS.md`, the existing ADRs, and nearby module tests before changing code.
2. Work through milestones in order and keep the build passing at each boundary.
3. Do not parse CDK source or run `cdk synth` implicitly.
4. Do not silently skip unsupported behavior; report the resource and corrective action.
5. Run `./gradlew formatCheck check` before marking a milestone complete.

## Milestones

- [x] M0: Add the CloudFormation module, immutable models, handler contracts, and stack state.
- [x] M1: Read Cloud Assembly manifests, parse JSON templates, resolve core intrinsics, and plan dependencies.
- [x] M2: Add the runtime control endpoints, deterministic deployment bundle, dry-run, and stack execution.
- [x] M3: Provision `AWS::SQS::Queue`, expose outputs, support rollback, and preserve Lambda deployment.
- [x] M4: Add Lambda file-asset resolution and `AWS::Lambda::Function`.
- [x] M5: Add a real TypeScript CDK example and a checked-in synthesized assembly fixture.
- [ ] M6: Add event-source mappings and CloudFormation-compatible stack APIs.

## Acceptance

- A single-stack `cdk.out` can be selected without `--stack`.
- Multiple stacks require an explicit artifact ID.
- `--parameter Key=Value` overrides assembly values.
- `--dry-run` does not mutate SQS or stack state.
- Named and generated SQS queues are locally addressable through the existing SQS JSON API.
- Java 21 Lambda file assets from a synthesized CDK assembly are staged into the existing local
  Lambda service and expose function name and ARN outputs.
- Unsupported resources produce diagnostics and exit code `8`.
- A failed supported creation rolls back resources created by that attempt.
- Existing Lambda project deployment remains functional.

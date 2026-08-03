---
id: ADR-0004
title: Cloud Assembly provisioning
status: Proposed
date: 2026-08-02
owners:
  - Ares AWS Local maintainers
related:
  - ADR-0001
---

# ADR-0004: Cloud Assembly provisioning

## Context

CDK applications are synthesized into a Cloud Assembly containing a versioned manifest,
CloudFormation templates, and deployment assets. Ares should consume that assembly and
provision local resources without parsing CDK source code or depending on the CDK runtime.

The local runtime already owns service state. A deployment engine must therefore live in the
runtime and call service resource handlers directly, while the CLI packages and uploads the
assembly through an Ares-local control endpoint.

## Decision

Add a transport-neutral CloudFormation module with immutable assembly and template models,
intrinsic resolution, dependency planning, resource-handler contracts, and process-local
stack state. The runtime owns execution and registers service-specific handlers.

The first resource handlers provision `AWS::SQS::Queue` and Java 21 `AWS::Lambda::Function`.
Lambda code is resolved from the file assets copied into the deployment bundle; Ares does not
contact S3. Unsupported resources are reported and skipped; dependent resources are blocked.
A partial result has a non-zero CLI exit code. Supported resources created during a failed
attempt are rolled back in reverse dependency order.

`ares deploy` keeps the existing `ares.yaml` Lambda workflow and detects a `cdk.out` assembly
when the supplied path contains `manifest.json` or `cdk.out/manifest.json`. Multi-stack
assemblies require `--stack`. `--dry-run` uses the same bundle and planner without mutation.

## CloudFormation subset

The initial interpreter supports JSON templates, parameters, common pseudo parameters,
conditions, outputs, `Ref`, `Fn::GetAtt`, `Fn::Sub`, `Fn::Join`, `Fn::Split`, `Fn::Select`,
`Fn::FindInMap`, and condition functions. Explicit `DependsOn` and implicit references form
the deterministic resource graph. YAML, transforms, nested stacks, cross-stack exports,
and full update/delete semantics remain future work.

## Consequences

- Stack state remains process-local and is shared with the existing service stores.
- The CloudFormation API is not emulated yet; the Ares control endpoint is the first transport.
- Resource support grows by registering handlers without changing the parser or scheduler.
- CDK-generated Lambda assets are staged through the existing Lambda artifact store and use the
  existing runtime-neutral execution backend.
- Unsupported behavior is visible and never silently treated as provisioned.

## References

- [AWS CDK synthesis](https://docs.aws.amazon.com/cdk/v2/guide/ref-cli-cmd-synth.html)
- [AWS CDK Cloud Assembly schema](https://docs.aws.amazon.com/cdk/api/v2/python/aws_cdk.cloud_assembly_schema/README.html)
- [CloudFormation templates](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/template-guide.html)
- [CloudFormation dependencies](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-attribute-dependson.html)

---
id: ADR-0002
title: Extensible trigger engine
status: Proposed
date: 2026-07-22
owners:
  - Ares AWS Local maintainers
related:
  - ADR-0001
---

# ADR-0002: Extensible trigger engine

## Context

Ares AWS Local needs to connect emulated resources without making service adapters call
each other directly. The first required integration is SQS delivering batches to Lambda,
but later services have different delivery models. Lambda polls SQS and DynamoDB Streams
through event-source mappings, while SNS pushes each publication to its subscriptions.

One generic event bus would hide those differences and encourage invented behavior.
The integration boundary instead needs shared registration, lifecycle, concurrency, and
diagnostics while leaving source-specific acknowledgement and retry rules in drivers.

## Goals

- Support polling and push integrations under one lifecycle owner.
- Implement standard SQS to Lambda batch delivery against a Lambda invocation boundary.
- Preserve registration and settings as immutable startup state.
- Keep delivery at least once through SQS receipt handles and visibility timeout.
- Allow SNS and DynamoDB Streams drivers without changing existing service adapters.
- Make scheduling, time, execution, and diagnostics deterministic in tests.

## Non-goals

- Implement Lambda resources, packaging, or function execution.
- Implement SNS, DynamoDB, DynamoDB Streams, FIFO queues, filtering, or destinations.
- Expose Lambda event-source mapping APIs in this slice.
- Implement SQS dead-letter queues, redrive policies, long polling, or AWS backoff scaling.
- Reproduce undocumented AWS scheduling details.

## Proposed design

The trigger registry is an immutable startup snapshot. A mapping names a driver and
connects typed source and target references with driver-specific settings.

```text
                              +-------------------+
service event -------------->| push driver       |----> target service
                              +-------------------+

source state <--- claim -----+ polling driver     +----> LambdaInvoker
             <-- acknowledge +--------------------+
                         ^
                         |
                 TriggerEngine
          scheduler, concurrency, lifecycle,
                 and diagnostics
```

### Driver families

A polling driver claims work and returns an asynchronous outcome for one batch. The
engine gives each mapping one non-overlapping polling lane per configured concurrency
slot. Empty polls wait 100 milliseconds; completed work can poll again immediately.
Invocation work runs on virtual threads so a blocked source or target does not occupy the
shared scheduler.

A push driver receives immutable integration events published by a service. The engine
fans an event out to every enabled mapping with the same source reference. One target
failure is reported through diagnostics and does not stop other target deliveries.

The first polling driver is `sqs-lambda`. Future DynamoDB Streams support can retain shard
ordering and checkpoints inside its own polling driver. Future SNS support can publish
after `Publish` commits its service state, then use push drivers for SQS or Lambda targets.

### SQS to Lambda delivery

The SQS driver claims messages atomically with receipt handles and visibility deadlines.
It reads at most ten records from the queue per source read, aggregating reads until one
of these limits is reached:

- configured batch size, from 1 through 10,000;
- configured batching window, from 0 through 300 seconds; or
- Lambda's 6 MiB synchronous invocation payload limit.

Batch sizes above ten require a batching window of at least one second. Claimed records
that do not fit the payload are released immediately. The invoked event uses the
documented SQS `Records` shape, including receive metadata, queue ARN, and region.

On successful invocation, all receipt handles are deleted. Function and infrastructure
failures delete nothing, so messages become visible after their configured visibility
timeout. When `ReportBatchItemFailures` is enabled, a valid response deletes only records
whose message IDs are absent from `batchItemFailures`. Malformed, duplicate, or unknown
identifiers fail the whole batch.

This provides at-least-once delivery. Function handlers must remain idempotent because a
record can be delivered more than once.

### Lifecycle and diagnostics

Mappings are registered programmatically before startup. Duplicate mapping or driver IDs,
unknown drivers, incompatible settings, and invalid source/target relationships fail
startup with actionable messages.

Shutdown stops new polls and waits up to five seconds for active work. Work still running
after that deadline is interrupted where possible, but its SQS records are not deleted;
their existing visibility leases determine when they can be retried.

Structured diagnostics cover lifecycle events, polling failures, isolated push failures,
and shutdown timeouts. The observer is injectable and cannot alter acknowledgement rules.

## Alternatives considered

### One generic event bus

Rejected. It looks uniform but loses material distinctions between polling, push fan-out,
visibility leases, stream checkpoints, and target acknowledgements.

### Direct service-to-service calls

Rejected. Calling Lambda from SQS or SQS from SNS would couple service state to target
availability and make additional integrations combinatorial.

### Implement Lambda execution first

Deferred. A raw asynchronous `LambdaInvoker` contract lets delivery semantics be built
and tested before function packaging and runtime execution are decided.

## Trade-offs and consequences

### Benefits

- Source-specific semantics remain explicit and testable.
- Polling and push drivers reuse lifecycle, concurrency, and diagnostics.
- Lambda execution can evolve independently from event-source delivery.
- Immutable startup mappings avoid partially applied runtime configuration.

### Costs and risks

- Programmatic mapping configuration is not AWS API compatible yet.
- Virtual-thread and scheduler lifecycle must be closed with the runtime.
- The local fixed poll interval does not reproduce AWS adaptive scaling or backoff.
- Large batches require repeated source reads and exact serialized-size accounting.

## Operational considerations

- Security: trigger drivers use only emulated resources and must not invoke real AWS
  endpoints implicitly.
- Performance: per-mapping concurrency is bounded from 1 through 1,000.
- Observability: failures include mapping and driver identifiers without changing retries.
- Failure recovery: SQS visibility timeout is the retry delay in this slice.
- Compatibility: standard queues only; FIFO mappings are rejected explicitly.

## Implementation plan

1. Add trigger contracts, immutable registry, lifecycle engine, and push-driver tests.
2. Add source-owned SQS batch leasing and acknowledgement operations.
3. Add the SQS to Lambda driver and fake-invoker tests.
4. Verify queue creation and message submission through AWS SDK v2 before trigger delivery.
5. Add a Lambda runtime and event-source mapping APIs in separate slices.
6. Add SNS and DynamoDB Streams drivers as their services become available.

## Decision

Proposed: use one lifecycle-managed trigger engine with separate polling and push driver
contracts. Implement SQS to a raw asynchronous Lambda invocation boundary first, with
at-least-once delivery governed by receipt handles and visibility timeout.

## Related work

- Parent architecture: [ADR-0001](./ADR-0001-aws-emulator-architecture.md).
- AWS SQS event-source behavior:
  [Using Lambda with Amazon SQS](https://docs.aws.amazon.com/lambda/latest/dg/with-sqs.html).
- AWS mapping parameters:
  [Lambda parameters for Amazon SQS event source mappings](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-parameters.html).
- AWS partial batch failures:
  [Handling errors for an SQS event source](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-errorhandling.html).
- AWS push model:
  [Fanout to Amazon SQS queues](https://docs.aws.amazon.com/sns/latest/dg/sns-sqs-as-subscriber.html).

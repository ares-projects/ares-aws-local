---
id: ADR-0001
title: AWS emulator architecture
status: Proposed
date: 2026-07-20
owners:
  - Ares AWS Local maintainers
related: []
---

# ADR-0001: AWS emulator architecture

## Context

Ares AWS Local should let applications exercise AWS-dependent behavior locally without
making requests to real AWS services. The emulator will need to accept AWS SDK requests,
understand service-specific protocols, maintain local resource state, and connect services
such as SQS and Lambda.

The architecture should make the first supported services useful without requiring a
complete implementation of AWS. It should also make additional services and protocols
possible without rewriting the HTTP server or cross-service wiring.

## Goals

- Provide a local HTTP endpoint that AWS SDKs can target.
- Support service-specific AWS protocols behind a common request boundary.
- Isolate service behavior from transport, routing, and shared infrastructure.
- Model local resources and their lifecycle explicitly.
- Support integrations such as SQS delivering work to Lambda.
- Make behavior testable through focused service tests and end-to-end flows.
- Allow incremental service and API coverage.

## Non-goals

- Reproduce all AWS services or APIs in the first release.
- Guarantee behavioral parity for undocumented AWS implementation details.
- Replace production AWS integration tests.
- Decide the complete persistence or distributed-runtime model before the first useful
  service exists.

## Proposed architecture

The emulator is organized around a small runtime with service-specific adapters:

```text
AWS SDK request
      |
      v
HTTP ingress and request context
      |
      v
Protocol dispatch and decoding
      |
      v
Service registry ----> service adapter
      |                       |
      v                       v
Resource state          service response
      |
      v
Integration dispatcher ---> Lambda runtime adapter
```

### HTTP ingress

The HTTP server owns the local endpoint, request and response handling, common headers,
request identity, and transport-level errors. It should not contain SQS, Lambda, or other
service behavior.

### Protocol handling

AWS services use different wire protocols. Protocol handling should be represented by an
explicit boundary so that JSON, Query, REST-JSON, XML, or other service protocols can be
added without coupling the HTTP server to one serialization strategy.

The protocol implementation should follow the [Smithy AWS protocol
specifications](https://smithy.io/2.0/aws/protocols/index.html), including:

- [AWS JSON 1.0](https://smithy.io/2.0/aws/protocols/aws-json-1_0-protocol.html) and
  [AWS JSON 1.1](https://smithy.io/2.0/aws/protocols/aws-json-1_1-protocol.html);
- [AWS Query](https://smithy.io/2.0/aws/protocols/aws-query-protocol.html) and
  [AWS EC2 Query](https://smithy.io/2.0/aws/protocols/aws-ec2-query-protocol.html);
- [AWS restJson1](https://smithy.io/2.0/aws/protocols/aws-restjson1-protocol.html); and
- [AWS restXml](https://smithy.io/2.0/aws/protocols/aws-restxml-protocol.html).

These specifications define how requests are identified, serialized, deserialized, and
returned as errors. They should be treated as the compatibility references for protocol
adapters and their compliance tests.

The protocol layer is responsible for decoding an incoming request into a normalized
service request and encoding a service result into the expected AWS response format. It
should preserve service-specific details that affect error responses, headers, or
operation selection.

The first service slice supports SQS `CreateQueue` and `SendMessage` through AWS JSON 1.0.
AWS Query/XML compatibility and SigV4 authentication validation remain future work. The
implementation must reject unsupported fields explicitly rather than silently inventing
behavior.

### Service registry and adapters

The registry maps a service identity and operation to a service adapter. An adapter owns
the behavior of one service or service family and uses shared runtime facilities for
resource state, time, identifiers, and integrations.

The initial adapter contract should make these responsibilities explicit:

- identify supported services and operations;
- validate normalized requests;
- read and mutate resource state;
- publish integration events when behavior requires them;
- return service-level results and errors.

### Resource state

Resource state represents local queues, functions, messages, and other emulated resources.
The first implementation should make isolation, reset behavior, identifiers, and
time-dependent state explicit even if the backing store is in-memory.

Persistence beyond the process lifetime remains an open decision until the supported
workflows require it.

### Integration dispatcher

Cross-service behavior should be modeled as explicit integrations rather than direct calls
from one service adapter into another. For SQS → Lambda, the dispatcher is responsible
for observing eligible messages, applying delivery and retry rules, invoking the Lambda
runtime adapter, and recording the resulting state transitions.

This boundary should leave room for other integrations without making SQS depend directly
on Lambda implementation details.

## Request and event flows

### Synchronous AWS request

1. The SDK sends a request to the local endpoint.
2. HTTP ingress creates a request context.
3. Protocol dispatch selects a decoder and service operation.
4. The service adapter validates the request and mutates resource state.
5. The adapter returns a normalized result or service error.
6. The protocol layer encodes the AWS-compatible response.

### SQS → Lambda integration

1. A message is sent to an emulated queue.
2. Queue state marks the message as available for delivery.
3. The integration dispatcher selects an eligible message and function mapping.
4. The Lambda runtime adapter invokes the function with an event payload.
5. Success, retry, visibility timeout, and dead-letter behavior update queue state.
6. The integration emits observable results for tests and diagnostics.

The exact concurrency, timing, retry, and visibility semantics require a dedicated
implementation decision before they are treated as stable behavior.

## Operational considerations

- Security: local credentials, request identity, and authorization behavior must be
  explicit; the emulator must not accidentally use production credentials.
- Performance: request routing and service state should be measurable before optimizing.
- Cost: the local runtime should not create real AWS resources or make network calls unless
  explicitly configured.
- Observability: request IDs, service operations, integration events, retries, and
  failures should be visible in tests and diagnostics.
- Failure recovery: service errors and integration retries should be deterministic enough
  for repeatable tests.
- Compatibility: supported service operations and protocol coverage should be documented
  and tested rather than implied.

## Implementation plan

Implementation should be split into focused GitHub issues and pull requests linked to this ADR:

1. Bootstrap the HTTP server and request context
   ([PR #3](https://github.com/ares-projects/ares-aws-local/pull/3)).
2. Define the service registry and adapter contract
   ([PR #5](https://github.com/ares-projects/ares-aws-local/pull/5)).
3. Implement protocol dispatch for the first supported service.
4. Implement resource state and reset/isolation behavior.
5. Implement services through focused issues and pull requests. Create a
   service-specific ADR only when it introduces a significant architectural
   decision.

While this ADR is proposed, update it as the design evolves. Once it is accepted, record
material changes in a new ADR rather than rewriting the historical decision.

## Decision

Proposed: use a shared local runtime with explicit HTTP ingress, protocol dispatch, service
adapters, resource state, and an integration dispatcher. The design is ready to be refined
through implementation issues, but the protocol, persistence, and event execution details
remain open until the first service is selected.

## Related work

- Initiative issue: to be created.
- Implementation issues and pull requests: to be linked as work is planned.
- HTTP server and request context bootstrap: [PR #3 — Bootstrap architecture docs and
  local HTTP runtime](https://github.com/ares-projects/ares-aws-local/pull/3).
- Service registry and adapter contract: [PR #5 — Add service registry and adapter
  contract](https://github.com/ares-projects/ares-aws-local/pull/5).

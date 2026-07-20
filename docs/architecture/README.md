# Architecture documentation

This directory contains the architecture proposals and decisions for Ares AWS Local.

We use one document type: an Architecture Decision Record (ADR). An ADR starts as a proposal, captures the design discussion, and becomes the durable record of the decision when it is accepted.

## When to write an ADR

Create an ADR when a change:

- crosses component or module boundaries;
- affects public behavior, APIs, protocols, or integrations;
- has meaningful alternatives or trade-offs;
- introduces security, performance, cost, or operational consequences; or
- would be difficult to reverse later.

Small, local, and easily reversible changes can use a normal issue and pull request without an ADR.

## Workflow

1. Create a GitHub issue for the feature or work item.
2. Copy [`TEMPLATE.md`](./TEMPLATE.md) to `ADR-NNNN-short-title.md`.
3. Set the status to `Proposed` and link the issue.
4. Discuss and refine the proposal in a pull request.
5. Break implementation into focused issues and pull requests linked to the ADR.
6. Set the ADR to `Accepted` when the direction is settled.
7. Set it to `Implemented` and add implementation links when the work is complete.

If an accepted decision changes, create a new ADR and mark the old one `Superseded`. Do not rewrite the historical reasoning in an accepted ADR.

## Statuses

- `Draft`: private or incomplete idea.
- `Proposed`: ready for review, but not yet agreed.
- `Accepted`: the project has chosen this direction.
- `Implemented`: the decision is reflected in the code and operational workflow.
- `Rejected`: considered and intentionally not adopted.
- `Superseded`: replaced by a later ADR.

## Naming

Use a zero-padded number and a short kebab-case title:

```text
ADR-0001-aws-emulator-architecture.md
```

Issues describe the work to do. ADRs describe the architectural reasoning behind that work. Link both from implementation pull requests.

## Current ADRs

- [ADR-0001: AWS emulator architecture](./ADR-0001-aws-emulator-architecture.md)

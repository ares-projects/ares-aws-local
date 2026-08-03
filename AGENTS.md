# Ares AWS Local agent instructions

## Source of truth

Read and follow `CONTRIBUTING.md`, `CODE_STYLE.md`, and `COMMIT_CONVENTION.md`.

When instructions conflict, use this precedence:

1. Existing public API and architectural decisions
2. Module-specific `AGENTS.md`
3. Root `AGENTS.md`
4. General conventions

## Development workflow

Before modifying code, identify the affected module and inspect its nearby production and test code.
Before presenting work as complete, run `./gradlew formatCheck check`.
Do not disable tests, static analysis, or formatting rules to make a change pass.

## Commits

Do not create commits unless explicitly requested. When asked to commit, follow `COMMIT_CONVENTION.md`, never add AI attribution or `Co-authored-by` trailers, and inspect the staged diff first.

## Code

- Target Java 21.
- Prefer immutable types and no wildcard imports.
- Use imports for project types instead of fully-qualified names in expressions or
  signatures; use a fully-qualified name only when it resolves a genuine name collision.
- Order Java declarations as constants, instance fields, constructors, public API methods,
  and private helpers. Keep required or primary method parameters first, followed by related
  options and context values.
- Do not introduce reflection when compile-time generation is possible.
- Public APIs require tests and Javadoc.
- When implementing AWS service behavior, use the authoritative AWS service and protocol
  documentation as the source of truth. Match documented request, response, and error
  semantics; reject unsupported behavior explicitly instead of inventing silent fallbacks.

## Communication Preferences

- Keep communication dry, concise, and low-key. Avoid flattery, forced memes, and unnecessary preambles or postambles.
- Comments should explain why a decision exists, not restate what the code does.
- Error messages must identify the failed input or condition and the corrective action when one is available.

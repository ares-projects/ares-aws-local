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
- Do not introduce reflection when compile-time generation is possible.
- Public APIs require tests and Javadoc.

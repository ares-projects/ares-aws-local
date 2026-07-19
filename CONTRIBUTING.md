# Contributing to Ares AWS Local

## Local setup

Install Java 21 and Node.js 22 or newer. Run `npm ci`, then install hooks with `./scripts/install-git-hooks`.

Before opening a pull request, run `./gradlew formatCheck check` and `npm run commitlint -- --help`.

Keep changes focused, add tests for public behavior, and document architectural decisions.

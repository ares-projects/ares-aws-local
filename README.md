# Ares AWS Local

This repository is prepared for local AWS-related development within the Ares projects.

## Development checks

```bash
npm ci
./scripts/install-git-hooks
./gradlew formatCheck check
```

Use `./gradlew spotlessApply` to format Java and Gradle sources.

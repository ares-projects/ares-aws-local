# Ares AWS Local code style

Spotless, using Palantir Java Format 2.93.0, is the source of truth for formatting. Run `./gradlew spotlessApply` before completing Java changes.

Use Java 21, explicit imports, immutable types where practical, and Javadoc for public APIs. Checkstyle, PMD, SpotBugs, and PIT mutation testing run as part of `check`; JaCoCo requires at least 95% line coverage for each module with production code.

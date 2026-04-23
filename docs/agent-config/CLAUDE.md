# Java Team Agent Instructions

> **Scope:** Repo-level extension. Layers on top of the org-wide baseline in the root `CLAUDE.md`.
> **Owner:** Java Services Team

## Java Team Standards

Read and follow these shared documentation files. They are downloaded from `hp-code-conventions` and kept up to date by the Gradle `downloadAllDocs` task.

- `docs/CODING_STANDARDS.md` - Java coding standards (hexagonal architecture, Kafka patterns, naming conventions, Lombok, constructor injection)
- `docs/HEXAGONAL_ARCHITECTURE_FOR_JAVA_PROJECTS.md` - Strict layer dependency rules for ports and adapters
- `docs/ROLE_AND_MINDSET.md` - Pragmatic senior Java developer role and technical focus
- `docs/FUNDAMENTAL_REFERENCES.md` - Key reference standards and architectural principles
- `docs/TRADE_OFF_POLICY.md` - Balancing pragmatism with technical excellence
- `docs/PERSISTENT_REVIEW_HIERARCHY.md` - Prioritized review checklist (reliability > FHIR logic > architecture > aesthetics)
- `docs/DOCUMENTATION_REVIEW.md` - Documentation review guidelines
- `docs/OUTPUT_FORMAT.md` - Expected structure for code review outputs

## Service Context

Read `docs/SERVICE_CONTEXT.md` for the service domain model, technical landscape, and architectural details.

## Before Committing

Always run `./gradlew downloadAllDocs` before your first commit on a branch. If any files in `docs/` or `.claude/` are updated by the task, include those changes in your commit. These are shared team standards that must stay in sync with the latest from `hp-code-conventions`. Failing to include updated docs in a PR causes other developers to silently fall behind on standards.

## Build Commands

- `./gradlew test` - Run unit tests (also downloads shared docs)
- `./gradlew itest` - Run integration tests
- `./gradlew build` - Full build
- `./gradlew checkstyleMain` - Run checkstyle (if configured in the repo)
- `./gradlew auditVersionPins` - Check if forced dependency pins can be removed by upgrading the Spring Boot BOM

## Dependency Version Pin Hygiene

The `auditVersionPins` task runs automatically as part of every `./gradlew build` via `check.dependsOn`. It logs a report of forced dependency pins and whether upgrading the Spring Boot BOM or a parent dependency would make them removable. It does not fail the build.

When reviewing a PR or working locally, check the `auditVersionPins` output. If it reports removable pins, create a separate PR that bumps the BOM version and removes the stale pins. Do not bundle the BOM upgrade into the current ticket's PR unless the ticket specifically calls for it or the PR author desires and approves it.

When adding a new forced version pin (via `resolutionStrategy.eachDependency` or `ext.set`), always include a `because` clause or inline comment with the CVE or GHSA identifier. This allows the audit task to track why the pin exists and when it can be removed.

## Test Structure

Use `// GIVEN // WHEN // THEN` comment structure in all tests.

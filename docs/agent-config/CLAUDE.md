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

## Build Commands

- `./gradlew test` - Run unit tests (also downloads shared docs)
- `./gradlew itest` - Run integration tests
- `./gradlew build` - Full build
- `./gradlew checkstyleMain` - Run checkstyle

## Test Structure

Use `// GIVEN // WHEN // THEN` comment structure in all tests.

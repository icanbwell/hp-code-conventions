# Coding standards

# Style Guide

[Code Conventions](https://raw.githubusercontent.com/icanbwell/hp-code-conventions/refs/heads/main/checkstyle/config/checkstyle.xml)

## Patterns and Paradigms

- Strive to follow hexagonal architecture, see [Workflow-Event-Service](https://github.com/icanbwell/workflow-event-service) for best example
- Favor design patterns wherever possible
- Prefer to use appropriate components for their intended function to keep business logic clean ex Deserializer -> interceptor -> filter -> consumer
- Prefer declarative programming using stream api and optional api
- Contract first development -> All repos will have asyncApi and OpenApi documents
- Sequence diagrams should exist for all major features (Mermaid preferred)
- Don’t reinvent the wheel, validate if a complex logic is already created in the internal bwell libraries
- Leave it better than you found it. If/when you modify pre-existing files, don’t hesitate to do small refactors to bring alignment to this style guide

## Kafka Standards

Message naming (cloud event `type`)
- past tense events, ex, dataBundled
- Imperative commands ex. bundleData

## PRs

- Prefer PR reviewer to "resolve" PR comments
- If the reviewer approves PR but leaves comments then comments are optional
- if the reviewer requests changes and leaves comments then a re-review is required
- Give a general context in the PR (AI will generate a summary but those can be complemented)
- Use {ticket-number} {action} {context} eg: EA-145 Updated model Foo

## General

- Only commit TODOs in the code if they have an associated ticket number
- Don't use this. for every reference to a field. Use it where it makes sense like in a constructor
- Use type interfaces in the variable declaration and not concrete types, ex: use `Map<>` instead of `HashMap<>`
- Use Lombok annotations:
  - `@Data` annotations for POJOs
  - `@NoArgsConstructor` instead of creating an empty constructor
  - `@AllArgsConstructor` to generate a constructor with 1 parameter for each field in your class
  - `@Slf4j` annotation for logs
  - `@Builder` if you have multiple ways to build an object
- No wildcard imports
- Format instead of concat
- Package names should not have capitals
- Regular camelCase for function names (Lower case first letter no - .NET styles allowed)
- If a linting rule is overridden, a comment is mandatory
- Linting warning (code smell). SonarLint Method Line count max: 75
- Use patientId vs patientID
- Prefer constructor injection to make unit test easy
- Prefer Optional chaining to validate nulls
- Add conditionalOnProperty if the feature could be disabled
- Be careful with the retryable exceptions

## Prefix your booleans

### *IS* for simple state

| Wrong        | Correct        |
|--------------|----------------|
| ❌ active     | ✅ isActive     |
| ❌ authorized | ✅ isAuthorized |


### *HAS* for ownership

| Wrong          | Correct           |
|----------------|-------------------|
| ❌ access       | ✅ hasAccess       |
| ❌ subscription | ✅ hasSubscription |

### *SHOULD* for expected behavior

| Wrong      | Correct          |
|------------|------------------|
| ❌ retry    | ✅ shouldRetry    |
| ❌ continue | ✅ shouldContinue |

### *CAN* for capabilities

| Wrong     | Correct      |
|-----------|--------------|
| ❌ edit    | ✅ canEdit    |
| ❌ comment | ✅ canComment |
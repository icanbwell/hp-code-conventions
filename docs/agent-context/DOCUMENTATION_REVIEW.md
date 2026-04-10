# Documentation Review

In every PR code review:
1. You MUST start your review summary with a clear heading such as `=COPILOT INSTRUCTIONS GUIDELINES=`.
2. **README Updates**:
    - If the PR introduces new features, API endpoints, configuration options, or changes existing functionality, verify that the `README.md` file has been updated accordingly.
    - If the `README.md` is missing updates, flag this in the review and suggest what sections need to be updated.
    - Check if new dependencies, environment variables, or setup steps require `README.md` documentation.
3. **In-Repo Documentation, Diagrams & Design Docs**:
    - Scan for all `*.md` and `*.mmd` files across the entire repository, not just the `docs/` directory. Documentation and diagrams may live alongside the code they describe (e.g., a `README.md` in a module directory, a `.mmd` Mermaid diagram next to the service it documents). Common locations include `docs/`, `docs/design/`, `docs/specs/`, `docs/plans/`, `adrs/`, and the repo root, but do not limit the search to these paths.
    - If the PR changes state transitions, event flows, FSM configuration, domain model structure, service interactions, or coordination patterns, check whether any existing doc or diagram describes the affected behavior and flag if it needs updating.
    - If the PR adds a new state, event type, Kafka topic, consumer, or workflow path that is not reflected in existing documentation, flag the gap and suggest which doc should be updated (or whether a new doc is warranted).
    - Pay particular attention to Mermaid diagrams — both standalone `.mmd` files and Mermaid code blocks embedded in markdown files. These become stale silently and mislead future developers.
    - **ADRs** (`adrs/` directory): If the PR introduces a new architectural pattern, library, or significant trade-off, check whether an ADR exists or should be created.
4. **API Contract Specifications**:
    - **AsyncAPI** (`asyncApi.yaml` or similar): If the PR adds, removes, or modifies Kafka events (new event types, changed payload schemas, new topics, altered CloudEvents headers), verify the AsyncAPI specification is updated in the same PR. Event schema changes without contract updates break downstream consumers silently.
    - **OpenAPI / Swagger** (`openapi.yaml`, Swagger annotations, or auto-generated specs): If the PR adds or modifies REST endpoints, request/response DTOs, query parameters, error responses, or authentication requirements, verify the OpenAPI specification reflects the changes. Check both:
      - Annotation-driven specs (e.g., `@Operation`, `@ApiResponse`, `@Schema` annotations on controllers/DTOs) — ensure annotations match actual behavior.
      - Standalone spec files — ensure they are updated if maintained separately from annotations.
    - For either contract type, flag if: (a) a new event/endpoint exists in code but not in the spec, (b) a field was added/removed/renamed in a DTO or event payload but the spec still shows the old shape, or (c) a new error response or status code is returned but not documented.
    - Contract specs are real contracts with consumers. Treat undocumented changes as potential breaking changes.
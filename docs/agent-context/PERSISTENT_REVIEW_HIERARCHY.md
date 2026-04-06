# Persistent Review Hierarchy (Order of Importance)

Unless otherwise specified for a specific PR, evaluate code in this priority order:
- **Reliability & Security (Critical)**: Identify logic bugs, thread-safety risks, PII leaks in logs, or improper exception handling. These must be addressed before deployment.
- **FHIR R4 & Business Logic (High)**: Ensure the code meets the functional requirements and FHIR R4 intent. Check for resource integrity and correct use of HAPI FHIR models.
- **Observability & Metrics (Medium-High)**: When a PR introduces new functionality, service interactions, or failure modes, verify that corresponding custom metrics are included. If new metrics are missing, notify the PR author that custom metrics should be added to cover the new behavior. Examples include counters for new event types processed, error paths, state transitions, or workflow outcomes. Observability is how distributed systems are debugged in production — new functionality without metrics is incomplete.
- **Architectural Integrity (Medium)**: Check alignment with Hexagonal layers. If a layer is "leaky" (e.g., Infrastructure leaking into Domain), note it as technical debt.
- **Code Aesthetics (Low)**: Suggest minor refactors, Stream optimizations, or naming improvements only if they don't significantly delay deployment.
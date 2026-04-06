# Persistent Review Hierarchy (Order of Importance)

Unless otherwise specified for a specific PR, evaluate code in this priority order:
- **Reliability & Security (Critical)**: Identify logic bugs, thread-safety risks, PII leaks in logs, or improper exception handling. These must be addressed before deployment.
- **FHIR R4 & Business Logic (High)**: Ensure the code meets the functional requirements and FHIR R4 intent. Check for resource integrity and correct use of HAPI FHIR models.
- **Observability & Metrics (Medium-High)**: When a PR introduces new functionality, service interactions, or failure modes, verify that corresponding **application-level custom metrics** are included.
  - **What to check for**: Metrics should describe the newly introduced behavior and its failure modes, not just standard platform telemetry. Look for counters, timers, gauges, or outcome/status metrics tied to new event types, workflow steps, state transitions, external calls, retries, validation failures, and success/error outcomes.
  - **What qualifies as acceptable coverage**: At minimum, a reviewer should expect metrics for (1) the new path being exercised, (2) the main success outcome, and (3) the important failure or retry paths introduced by the change. If a PR adds a new integration or asynchronous workflow, expect metrics that make it possible to distinguish where the flow started, succeeded, failed, or became stuck.
  - **What does _not_ count by itself**: Standard infrastructure/platform metrics such as CPU, memory, JVM/process health, generic HTTP request totals, or existing dashboard coverage do not replace feature-specific instrumentation for the new behavior.
  - **What to request from the author if missing**: Ask for custom metrics that cover the new behavior/failure modes before approval, or for a clear explanation of why the change is not operationally significant enough to require new instrumentation.
  - **Examples**: Counters for new event types processed, error paths, state transitions, workflow outcomes, retry exhaustion, dead-lettered messages, or partner/API-specific failures.
  - Observability is how distributed systems are debugged in production — new functionality without metrics is incomplete.
- **Architectural Integrity (Medium)**: Check alignment with Hexagonal layers. If a layer is "leaky" (e.g., Infrastructure leaking into Domain), note it as technical debt.
- **Code Aesthetics (Low)**: Suggest minor refactors, Stream optimizations, or naming improvements only if they don't significantly delay deployment.
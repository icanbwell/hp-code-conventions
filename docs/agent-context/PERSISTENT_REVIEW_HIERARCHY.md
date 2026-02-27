### Persistent Review Hierarchy (Order of Importance)

Unless otherwise specified for a specific PR, evaluate code in this priority order:
- **Reliability & Security (Critical)**: Identify logic bugs, thread-safety risks, PII leaks in logs, or improper exception handling. These must be addressed before deployment.
- **FHIR R4 & Business Logic (High)**: Ensure the code meets the functional requirements and FHIR R4 intent. Check for resource integrity and correct use of HAPI FHIR models.
- **Architectural Integrity (Medium)**: Check alignment with Hexagonal layers. If a layer is "leaky" (e.g., Infrastructure leaking into Domain), note it as technical debt.
- **Code Aesthetics (Low)**: Suggest minor refactors, Stream optimizations, or naming improvements only if they don't significantly delay deployment.
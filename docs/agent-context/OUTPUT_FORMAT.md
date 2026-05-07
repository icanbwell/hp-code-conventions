# Output Format

For every review, provide the following sections in order:

## PR Summary (always include)

- **What this PR does**: 1-2 sentence plain-English summary of the change and its motivation.
- **Key data flow**: How data moves through the system as a result of this change (entry point → processing → output/persistence). For config-only changes, describe what the config controls at runtime.
- **Critical business logic**: Call out the most important or highest-impact code changes in this PR — the lines/files where core business rules, FHIR resource transformations, or key decision-making logic live. Be specific (file + method/line range). This helps human reviewers focus their time on what matters most.
- **Human attention needed**: Things the bot cannot fully verify — environment config, deploy ordering, feature flag state, manual test scenarios, downstream consumer impact, or anything that requires domain knowledge to validate.

## Review Findings

- **Status**: A high-level status (e.g., "Stable," "Contains Technical Debt," or "Critical Issues Found").
- **Categorized Feedback**:
    - [Critical/Reliability]: Fixes required for production safety.
    - [FHIR/Logic]: Alignment with business and FHIR standards.
    - [Technical Debt/Architecture]: Observations on tradeoffs or architectural leaks.
- **Approval**: Provide a pragmatic approval if no "Critical" issues are present.
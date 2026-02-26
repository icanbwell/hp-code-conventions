## Output Format

For every review, provide:
- **Summary**: A high-level status (e.g., "Stable," "Contains Technical Debt," or "Critical Issues Found").
- **Categorized Feedback**:
    - [Critical/Reliability]: Fixes required for production safety.
    - [FHIR/Logic]: Alignment with business and FHIR standards.
    - [Technical Debt/Architecture]: Observations on tradeoffs or architectural leaks.
- **Approval**: Provide a pragmatic approval if no "Critical" issues are present.
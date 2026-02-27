### Fundamental References

All reviews must be conducted in alignment with these documents:
* **Architecture**: Refer to `docs/HEXAGONAL_ARCHITECTURE_FOR_JAVA_PROJECTS.md` file for layer boundaries and port/adapter definitions.
* **Standards**: Refer to the `docs/CODING_STANDARDS.md` file for style, naming conventions, and Java best practices.
* **Data Model (FHIR R4)**: Use FHIR R4 as the primary baseline.
    * Boundary Handling: Recognize that FHIR resources often function as external Contracts (DTOs) at the entry and exit points (Adapters).
    * Service Intensity: Adjust review depth based on the service's relationship with FHIR:
        * Pass-through: Focus on schema validity and transformation integrity.
        * Resource-Heavy: Focus on deep validation, business rules, and HAPI FHIR model optimization.

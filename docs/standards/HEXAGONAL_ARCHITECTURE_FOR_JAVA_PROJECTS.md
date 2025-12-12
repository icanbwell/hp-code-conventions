# Overview

## Domain Layer:

- Represents the business logic and domain models
- Should be independent of external frameworks and technologies
- Contains
  - Domain Models - Core entities that represent business data
  - Domain Services - Business logic that doesn't fit within a single entity
  - Interfaces - contracts for operations that need implementations e.g. integrationStrategy

## Application Layer:

- Coordinates application logic/use cases
- Acts as a mediator between the domain and the outer layers
- Separates orchestration logic from business rules
- Manages dependencies between the domain and infrastructure
- Factories should reside here because they involve application-specific logic to determine which concrete implementation to use

## Infrastructure:

- Contains implementations for external systems
- Handles technical details like messaging, data access, and external apis
- Concrete strategy implementations reside here, encapsulating the interactions with external systems

## Strict Dependency Rules:

### Domain Layer:

- CANNOT depend on Application Layer
- CANNOT depend on Infrastructure Layer
- SHOULD be completely independent
- ONLY depends on itself

### Application Layer:

- CAN depend on Domain Layer
- CANNOT depend on Infrastructure Layer
- Acts as a mediator

### Infrastructure Layer:

- CAN depend on Application Layer
- CAN depend on Domain Layer
- Implements concrete details

## Key Components

### Domain Layer

Purpose: Core Business Logic
- Domain Models
  - Represent core business entities
  - Contain business rules and validations
  - Immutable when possible
- Domain Services
  - Complex business logic that doesn't fit in models
  - Stateless operations
  - Pure business logic
- Domain Ports (Interfaces)
  - Define contracts for external interactions
  - Technology-agnostic
  - Two types:
    - Inbound Ports (Kafka consumers, REST Controllers)
    - Outbound Ports (Kafka producers, DB Repositories)

### Application Layer

Purpose: Coordinate Use Cases and Workflows
- Use Cases (interfaces)
  - Represent specific business processes
  - Orchestrate domain logic
  - Coordinate between domain and infrastructure
- Application Services (implement useCase interfaces)
  - Implement use case workflows
  - Manage application-specific logic
  - Coordinate domain services and infrastructure adapters
- Factories
  - Create complex domain objects
  - Encapsulate object creation logic

### Infrastructure Layer

Purpose: Implement External Interactions
- Inbound Adapters
  - Translate external requests to application use cases
  - Examples:
    - REST Controllers
    - GraphQL Resolvers
    - Kafka Consumers
- Outbound Adapters
  - Implement outbound ports
  - Interact with external systems
  - Examples:
    - Database repositories
    - External API clients
    - Message queue implementations
- Configuration
  - Dependency injection setup
  - Bean/Component registration
  - Environment-specific configurations
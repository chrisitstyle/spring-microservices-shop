# spring-microservices-shop

A learning and portfolio project focused on building an e-commerce backend with **Java, Spring Boot and microservices architecture**.

The project will be developed incrementally. It will start as a simple Spring Boot monolith and will gradually evolve into a distributed microservices system with independent databases, service-to-service communication, asynchronous messaging, resilience patterns, observability, security and containerization.

The main goal of this repository is not only to build a working application, but also to understand **why and when particular microservices patterns are useful**.

---

## Project Roadmap

### Phase 1 — Spring Boot Fundamentals

The first step is to build a solid foundation with Spring Boot.

The project will cover:

- REST API development
- Controller / Service / Repository layers
- DTOs
- Request validation
- Global exception handling
- Spring Data JPA
- PostgreSQL
- Database migrations
- Unit and integration testing

Example endpoints:

```http
POST /products
GET /products/{id}

POST /users
GET /users/{id}

POST /orders
GET /orders/{id}
```

Status:

- [ ] Create Spring Boot project
- [ ] Configure PostgreSQL
- [ ] Configure database migrations
- [ ] Implement Product domain
- [ ] Implement User domain
- [ ] Implement Order domain
- [ ] Add validation
- [ ] Add global exception handling
- [ ] Add tests

---

## Phase 2 — Build the Monolith

Before introducing microservices, the shop backend will first be implemented as a simple monolithic application.

Initial architecture:

```text
shop-api
│
├── product
│   ├── controller
│   ├── service
│   ├── repository
│   └── domain
│
├── user
│   ├── controller
│   ├── service
│   ├── repository
│   └── domain
│
└── order
    ├── controller
    ├── service
    ├── repository
    └── domain
```

The monolith will contain three main domains:

### Product

Responsible for managing products available in the shop.

### User

Responsible for shop users.

### Order

Responsible for creating and retrieving customer orders.

Example order flow:

```text
User
 |
 v
Create Order
 |
 +---- validate user
 |
 +---- validate product
 |
 v
Save Order
```

Status:

- [ ] Product module
- [ ] User module
- [ ] Order module
- [ ] Order creation flow
- [ ] PostgreSQL persistence
- [ ] Integration tests

---

## Phase 3 — Split the Monolith into Microservices

Once the monolithic version is working, the application will be split into independent Spring Boot services.

Target services:

```text
product-service
user-service
order-service
```

Each service will:

- run as an independent Spring Boot application
- expose its own REST API
- own its domain logic
- have its own database
- be independently deployable

Target architecture:

```text
                  Client
                    |
          +---------+---------+
          |         |         |
          v         v         v

     User Service  Order Service  Product Service
          |             |              |
          v             v              v
       user_db       order_db       product_db
```

A key architectural rule of the project will be:

> A microservice must not directly access another microservice's database.

Status:

- [ ] Extract Product Service
- [ ] Extract User Service
- [ ] Extract Order Service
- [ ] Create separate databases
- [ ] Remove shared database access
- [ ] Run services independently

---

## Phase 4 — Service-to-Service Communication

The next step will introduce communication between services.

Example flow:

```text
POST /orders
      |
      v
order-service
      |
      +---- GET /products/{id} ----> product-service
      |
      +---- GET /users/{id} ------> user-service
      |
      v
create order
```

Topics covered:

- synchronous HTTP communication
- Spring RestClient / WebClient
- API contracts
- DTOs between services
- timeouts
- remote error handling
- HTTP status codes

Status:

- [ ] Order → Product communication
- [ ] Order → User communication
- [ ] Configure timeouts
- [ ] Handle remote errors
- [ ] Add integration tests

---

## Phase 5 — Docker

All services and infrastructure will be containerized.

The complete development environment will be started using:

```bash
docker compose up
```

Planned Docker Compose architecture:

```text
Docker Compose
│
├── product-service
├── order-service
├── user-service
│
├── product-db
├── order-db
└── user-db
```

Topics covered:

- Docker images
- Dockerfile
- Docker Compose
- container networking
- environment variables
- health checks

Status:

- [ ] Dockerize Product Service
- [ ] Dockerize User Service
- [ ] Dockerize Order Service
- [ ] Add PostgreSQL containers
- [ ] Configure networking
- [ ] Add health checks
- [ ] Create docker-compose.yml

---

## Phase 6 — Event-Driven Communication with Kafka

The project will introduce asynchronous communication using Apache Kafka.

A new service will be added:

```text
notification-service
```

Event flow:

```text
order-service
      |
      | OrderCreated
      v
    Kafka
      |
      v
notification-service
```

Example event:

```json
{
  "orderId": 123,
  "userId": 42,
  "totalPrice": 299.99
}
```

Topics covered:

- Kafka producers
- Kafka consumers
- topics
- consumer groups
- event serialization
- asynchronous communication
- retries
- dead-letter topics

Status:

- [ ] Add Kafka
- [ ] Create OrderCreated event
- [ ] Publish events from Order Service
- [ ] Create Notification Service
- [ ] Consume OrderCreated events
- [ ] Configure retry strategy
- [ ] Configure Dead Letter Topic

---

## Phase 7 — Resilience and Failure Handling

This phase will focus on making communication between services more resilient.

Topics covered:

- timeout
- retry
- circuit breaker
- fallback
- failure scenarios
- Resilience4j

Example:

```text
Request
   |
   v
Product Service
   |
   X

Retry
Retry
Retry

Circuit Breaker
      |
      v
    OPEN
```

Status:

- [ ] Configure timeouts
- [ ] Add retry
- [ ] Add Circuit Breaker
- [ ] Test Product Service failure
- [ ] Test User Service failure
- [ ] Test Kafka failure scenarios

---

## Phase 8 — Observability

Planned tools:

```text
Spring Boot Actuator
Prometheus
Grafana
Distributed Tracing
```

Example monitoring architecture:

```text
Spring Boot Services
        |
        v
     Actuator
        |
        v
    Prometheus
        |
        v
     Grafana
```

Important endpoints:

```http
/actuator/health
/actuator/metrics
```

Status:

- [ ] Add Spring Boot Actuator
- [ ] Add health endpoints
- [ ] Configure Prometheus
- [ ] Configure Grafana
- [ ] Add distributed tracing
- [ ] Propagate trace IDs between services

---

## Phase 9 — Security

Topics covered:

- Spring Security
- authentication
- authorization
- JWT
- OAuth2 concepts
- roles and authorities

Example authorization model:

```text
USER
 |
 +---- browse products
 |
 +---- create orders

ADMIN
 |
 +---- create products
 |
 +---- update products
 |
 +---- manage products
```

Status:

- [ ] Configure Spring Security
- [ ] Add authentication
- [ ] Add JWT
- [ ] Add USER role
- [ ] Add ADMIN role
- [ ] Protect selected endpoints

---

## Phase 10 — API Gateway

An API Gateway will become the entry point to the system.

Target architecture:

```text
                       Client
                          |
                          v
                    API Gateway
                          |
          +---------------+---------------+
          |               |               |
          v               v               v
     User Service    Order Service   Product Service
                          |
                          v
                        Kafka
                          |
                          v
                 Notification Service
```

The gateway may be responsible for:

- routing
- authentication
- centralized request logging
- rate limiting
- forwarding requests to appropriate services

Status:

- [ ] Add API Gateway
- [ ] Configure routing
- [ ] Integrate authentication
- [ ] Add request logging
- [ ] Add rate limiting

---

# Technology Stack

Main technologies planned for this project:

```text
Java
Spring Boot
Spring Web
Spring Data JPA
Spring Security
PostgreSQL
Flyway / Liquibase
Apache Kafka
Resilience4j
Docker
Docker Compose
Testcontainers
Spring Boot Actuator
Prometheus
Grafana
```

Kubernetes may be introduced after the core microservices architecture is completed.

---

# Testing Strategy

Planned tools:

- JUnit
- Mockito
- Spring Boot Test
- Testcontainers

The project will gradually introduce:

```text
Unit Tests
     |
     v
Integration Tests
     |
     v
Database Tests
     |
     v
Service Integration Tests
     |
     v
End-to-End Tests
```

---

# Repository Structure

Initial monolith:

```text
spring-microservices-shop/
│
├── src/
├── pom.xml
└── README.md
```

After the microservices split:

```text
spring-microservices-shop/
│
├── api-gateway/
├── product-service/
├── user-service/
├── order-service/
├── notification-service/
├── docker-compose.yml
├── diagrams/
└── README.md
```

---

# Learning Goals

After completing the project, I want to be able to explain and demonstrate:

- how to design a Spring Boot REST API
- how to split a monolith into microservices
- how to define service boundaries
- why every microservice should own its data
- synchronous vs asynchronous communication
- REST vs event-driven communication
- eventual consistency
- Kafka producers and consumers
- how to handle unavailable services
- timeout vs retry vs circuit breaker
- distributed tracing
- application monitoring
- JWT authentication
- API Gateway responsibilities
- Docker-based local environments
- integration testing with Testcontainers

---

# Development Approach

The project follows one important principle:

> Start simple and introduce distributed-system complexity only when there is a reason for it.

The project evolves in the following order:

```text
Spring Boot fundamentals
        ↓
Simple monolith
        ↓
Microservices split
        ↓
Service-to-service HTTP
        ↓
Docker
        ↓
Kafka
        ↓
Resilience
        ↓
Observability
        ↓
Security
        ↓
API Gateway
        ↓
Production-oriented architecture
```

This repository is primarily an educational project used to learn microservices architecture through practical implementation.

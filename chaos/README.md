# Chaos / Failure Testing

This directory contains controlled failure scenarios for the
`spring-microservices-shop` local Kubernetes environment.

The goal is not to randomly break the cluster, but to verify concrete
resilience guarantees of the system.

## Prerequisites

The local Kubernetes environment must already be running.

Expected environment:

- kind cluster: `spring-shop`
- namespace: `spring-shop`
- API Gateway: `http://localhost:8085`
- Keycloak: `http://localhost:8080`
- all application and infrastructure Pods running

Verify:

```powershell
kubectl get pods -n spring-shop
```

## Scenarios

### 1. Product Service Pod crash

Script:

```powershell
.\chaos\pod-crash.ps1
```

Failure injected:

- delete the current Product Service Pod

Expected behavior:

- Kubernetes detects the missing replica
- Deployment creates a replacement Pod
- startup/readiness probes succeed
- the replacement Pod becomes Ready
- Gateway returns HTTP 200 again

This verifies Kubernetes self-healing.

---

### 2. Product Service outage

Script:

```powershell
.\chaos\product-outage.ps1
```

Failure injected:

- scale Product Service from one replica to zero

Expected behavior:

- Product Service Pods disappear
- the Service has no ready endpoints
- requests through the Gateway fail while the dependency is unavailable
- the original replica count is restored
- Product Service becomes Ready again
- Gateway returns HTTP 200

The script restores the original replica count in a `finally` block.

---

### 3. Kafka outage

Script:

```powershell
.\chaos\kafka-outage.ps1
```

Failure injected:

- scale Kafka StatefulSet to zero

During the outage the test creates an order.

Expected behavior:

- order creation succeeds
- the order creation Saga completes
- `OrderCreated` is persisted in the transactional outbox
- the outbox event remains unpublished while Kafka is unavailable
- Notification Service does not process the event yet

After Kafka recovery:

- Kafka becomes available again
- the outbox publisher retries delivery
- `published_at` is set
- Notification Service consumes the event
- the event is processed exactly once

This verifies that a Kafka outage does not cause loss of a committed
business event.

---

### 4. Order database outage

Script:

```powershell
.\chaos\order-db-outage.ps1
```

Failure injected:

- scale the Order PostgreSQL StatefulSet to zero

Expected behavior:

- the database becomes unavailable
- Kubernetes continues managing Order Service
- the database StatefulSet is restored
- PostgreSQL becomes available again
- the existing order count is unchanged
- the same persistent storage is reused

This verifies PostgreSQL persistence and application recovery after a
temporary database outage.

---

### 5. Order Service crash with stale Saga

Script:

```powershell
.\chaos\saga-recovery.ps1
```

Failure injected:

- create a stale `STARTED` order creation Saga
- delete the current Order Service Pod

Expected behavior:

- Kubernetes creates a replacement Order Service Pod
- the Saga recovery worker detects the stale Saga
- the worker claims the Saga
- `recovery_fence` is incremented
- the Saga transitions through compensation
- the final state is `COMPENSATED`
- the recovery lease is released

Expected state transition:

```text
STARTED
   |
   | recovery claim
   | fence: 0 -> 1
   v
COMPENSATING
   |
   v
COMPENSATED
```

The synthetic Saga is removed after the test.

## Safety

Failure scripts should restore intentionally disabled components before
exiting.

Where applicable, recovery actions are placed in PowerShell `finally`
blocks so that a failed assertion does not intentionally leave the local
cluster in a broken state.

These tests are intended for the local `kind` environment and must not be
run against a production Kubernetes cluster.

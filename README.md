# Orderflow

Event-driven microservices architecture utilizing Spring Boot 4, gRPC, PostgreSQL, Apache Kafka, Confluent Schema Registry, and Debezium Change Data Capture (CDC) via the Transactional Outbox Pattern.

---

## Technical Stack & Dependencies

* **Java:** 21 (LTS)
* **Framework:** Spring Boot `4.0.7` | Spring Cloud `2025.1.2` | Spring gRPC `1.0.3`
* **Build System:** Apache Maven (Root Multi-Module POM)
* **Database & Migrations:** PostgreSQL 17 | Flyway
* **Event Streaming & Schemas:** Apache Kafka (KRaft) | Confluent Schema Registry `7.7.7` | Protobuf `3.25.5`
* **Change Data Capture:** Debezium PostgreSQL Connector `3.1.2` (Outbox Pattern)
* **Infrastructure as Code:** Terraform `1.14` (`Mongey/kafka` provider `0.12.1`)
* **Developer Tools:** pgAdmin 4 | Kafka UI

---

## Project Structure

```text
.
├── common/
│   ├── common-exceptions/           # Exception hierarchy & global HTTP/gRPC handlers
│   ├── common-kafka/                # Polymorphic Protobuf event consumer dispatchers
│   ├── common-outbox/               # Transactional outbox entity & repository contracts
│   ├── common-security/             # HMAC payload verification utilities
│   ├── orderflow-events-contract/   # Protobuf schemas & generated classes for Kafka
│   └── orderflow-grpc-contract/     # Protobuf schemas & generated stubs for gRPC
├── docker/
│   ├── docker-compose.infra.yml     # PostgreSQL, Kafka, Schema Registry, Connect
│   ├── docker-compose.services.yml  # Application container definitions
│   ├── docker-compose.tf.yml        # Terraform execution wrapper
│   ├── docker-compose.tools.yml     # pgAdmin, Kafka UI
│   ├── init-db.sql                  # PostgreSQL database initialization script
│   └── kafka-connect.Dockerfile     # Custom Debezium Kafka Connect image
├── services/
│   ├── entitlement-service/         # Entitlement management microservice
│   ├── inventory-service/           # Inventory stock, reservation engine & background sweeper
│   ├── order-service/               # Order orchestration microservice
│   └── payment-service/             # Payment processing microservice
├── terraform/                       # Kafka topic definitions & provider setup
│   ├── main.tf
│   ├── provider.tf
│   └── variables.tf
├── .env.example                     # Environment variables template
├── Makefile                         # Automation commands
├── pom.xml                          # Root parent POM
└── README.md
```

---

## ⚡ Microservices & Port Matrix

| Service | REST Port | gRPC Port | Database | Primary Responsibilities |
| :--- |:----------|:----------| :--- | :--- |
| **Inventory Service** | `8082`    | `9082`    | `inventory_db` | Item catalog management, pessimistic stock reservation engine, scheduled background stock sweeper, gRPC endpoints |
| **Order Service** | `8084`    | `----` | `order_db` | Order orchestration, Saga kickoff, synchronous gRPC inventory checks, transactional outbox writer, SSE real-time updates |
| **Payment Service** | `8086`    | `----`    | `payment_db` |Payment lifecycle management—handling payment intent initialization, asynchronous Saga charge execution, third-party gateway verification, and atomic payment event publishing|
| **Entitlement Service** | `----`    | `----`    | `entitlement_db` |  |

---
## 🏬 Service Deep-Dives

### Inventory Service
Handles high-concurrency stock tracking and reservation management:
* **Pessimistic Reservation Engine:** Reserves stock under high concurrency, preventing overselling during checkout flows.
* **Automated Expiration Sweeper (`ReservationSweeper`):** A fixed-delay task (running every 30s) that recovers stock from orphaned or timed-out `PENDING` reservations in batches using `SKIP LOCKED` to prevent DB row contention across multi-instance deployments.
* **Batch Stock Increments:** Optimized repository-level SQL batch operations for fast inventory restoration.
* **gRPC Capabilities:** Exposes high-throughput Protobuf stubs (`inventory_service.proto`) for inter-service synchronous checks.

### Order Service
Acts as the Saga orchestrator for checkout workflows:
* **Synchronous Stock Validation:** Executes synchronous gRPC calls to the Inventory Service (`InventoryServiceClient`) to reserve items during initial order submission.
* **Transactional Outbox Writer:** Persists domain events into the `outbox` table within the local database transaction boundary. Debezium CDC captures these inserts and routes them to Kafka.
* **Asynchronous Client Feedback:** Maintains real-time SSE (`SseEmitterRegistry`) connections to push status updates back to clients as downstream saga events settle.
* **Saga Step Tracking:** Records structured audit logs and state transition telemetry for each phase of the distributed order saga.

### Payment Service
Serves as the single source of truth for payment processing and lifecycle state management across the checkout Saga:
* **Payment Intent & Charge Execution:** Handles initial payment intent creation and consumes asynchronous charge commands (`ChargePaymentCommandHandler`) dispatched during order orchestration.
* **Gateway Abstraction & Verification:** Provides an extensible gateway layer (`PaymentGateway`, `MockPaymentGateway`) with HMAC payload verification (`SignatureService`) to safely simulate and process third-party provider workflows (e.g., Stripe PaymentIntents).
* **Transactional State & Outbox:** Guarantees atomic persistence of payment records (`Payment`, `PaymentStatus`) and outbox events in a single database transaction boundary, ensuring zero event loss when notifying downstream services via Debezium CDC.
* **Resilient Command Consumption:** Utilizes dedicated retry topics and dead-letter queues (`KafkaRetryConfig`) to ensure transient gateway or network failures do not compromise payment consistency.
---
## 🧩 Shared Common Modules

The architecture relies on lightweight shared modules located under `common/`. Downstream microservices inherit these contracts and utility layers.

| Module | Artifact ID | Description |
| :--- | :--- | :--- |
| **Exceptions** | `common-exceptions` | Unified business exception hierarchy (`BaseCustomException`), error codes (`CommonErrorCode`), and `@RestControllerAdvice` global handlers |
| **Kafka** | `common-kafka` | Polymorphic `AbstractEventConsumer` dispatcher and strongly-typed Protobuf `EventHandler` registry |
| **Outbox** | `common-outbox` | Outbox pattern persistence model and Debezium CDC event publisher contracts |
| **Security** | `common-security` | HMAC signature verification service (`HmacSignatureService`) for payload integrity |
| **Events Contract** | `orderflow-events-contract` | Protobuf event schemas and auto-generated Java classes for domain Kafka events |
| **gRPC Contract** | `orderflow-grpc-contract` | Protobuf definitions and generated stubs for synchronous inter-service gRPC calls |

---
## 🚀 Operations & Development Lifecycle

### 1. Build Shared Modules Locally
Install common modules into your local Maven repository before compiling services:

```bash
mvn clean install -pl common/common-exceptions,common/common-kafka,common/common-outbox,common/common-security,common/orderflow-events-contract,common/orderflow-grpc-contract -am -DskipTests
```

### 2. Environment Startup
```bash
# Start full stack (Infrastructure + Microservices + Dev Tools)
make dev-up

# Or start core infrastructure only
make infra-up
```

### 3. Kafka & Pipeline Initialization
Execute setup tasks in sequence once infrastructure containers are healthy:

```bash
# 1. Provision Kafka topics via Terraform
make topics-apply

# 2. Register Protobuf schemas with Schema Registry
make register-schemas

# 3. Register Debezium CDC connectors with Kafka Connect
make register-connectors
```

### 4. Verification & Management Commands
```bash
# Verify active Debezium connectors
curl -s http://localhost:8083/connectors | jq

# Rebuild and restart a single service after code changes
make rebuild-service MODULE=services/order-service SERVICE=order-service

# Teardown all containers and networks
make down
```
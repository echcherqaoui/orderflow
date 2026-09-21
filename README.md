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
│   ├── connectors/                  # Debezium outbox connector configurations
│   ├── docker-compose.infra.yml     # PostgreSQL, Kafka, Schema Registry, Connect
│   ├── docker-compose.services.yml  # Application container definitions
│   ├── docker-compose.tf.yml        # Terraform execution wrapper
│   ├── docker-compose.tools.yml     # pgAdmin, Kafka UI
│   ├── init-db.sql                  # PostgreSQL database initialization script
│   └── kafka-connect.Dockerfile     # Custom Debezium Kafka Connect image
├── services/
│   ├── entitlement-service/         # Entitlement management microservice
│   ├── inventory-service/           # Inventory stock, reservation lifecycle & sweeper
│   ├── order-service/               # Order orchestration & Saga state machine
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
| **Inventory Service** | `8082` | `9082` | `inventory_db` | Item catalog management, pessimistic stock reservation engine, reservation extension & release lifecycle, background stock sweeper, gRPC endpoints |
| **Order Service** | `8084` | `----` | `order_db` | Order orchestration, end-to-end Saga state machine, gRPC inventory calls, Protobuf event handlers with HMAC signature verification, transactional outbox writer, SSE real-time client state streaming |
| **Payment Service** | `8086` | `----` | `payment_db` | Payment intent creation, async payment charges, third-party gateway abstraction, HMAC signature generation in `MessageMetadata`, atomic outbox event publishing |
| **Entitlement Service** | `----`    | `----`    | `entitlement_db` |  |

---

## 🔄 Checkout Saga Architecture & Lifecycle

### Happy Path Workflow
1. **Reserve Stock:** `Order Service` synchronously calls `Inventory Service` via gRPC to lock stock.
2. **Initialize Payment:** `Order Service` requests `Payment Service` to create a payment intent/session.
3. **Extend Reservation:** Once the payment session is initialized, `Order Service` commands `Inventory Service` to extend stock TTL for the active payment window.
4. **Notify Client:** `Order Service` receives confirmation and pushes payment details to the client via SSE to complete payment.

### Compensating Flow (Failure / Cancellation)
1. **Trigger:** Payment charge failure, gateway rejection, payment timeout, or explicit user cancellation.
2. **Compensation Command:** `Order Service` dispatches a `ReleaseInventoryCommand` to `Inventory Service`.
3. **Stock Recovery:** `Inventory Service` unlocks and restores reserved stock back to the available item inventory.
4. **Order Cancellation:** `Order Service` transitions the order to `CANCELLED` and emits failure telemetry.

---

## 🏬 Service Deep-Dives

### Inventory Service
Handles high-concurrency stock tracking and reservation management:
* **Pessimistic Reservation Engine:** Guarantees stock consistency under high concurrency. Enforces a single active pending reservation per cart session.
* **Reservation Extension & Release Lifecycle:** Listens to async Kafka commands (`ExtendReservationCommandHandler`, `ReleaseInventoryCommandHandler`) to dynamically extend lock TTLs during payment sessions or immediately release reserved inventory back to the pool upon payment failure.
* **Automated Expiration Sweeper (`ReservationSweeper`):** Runs every 30s using `SKIP LOCKED` batch queries to clean up orphaned or timed-out `PENDING` reservations without database row contention.
* **gRPC Interface:** Exposes Protobuf stubs (`InventoryGrpcService`) for fast, synchronous stock verification during initial order placement.

### Order Service
Acts as the distributed Saga Orchestrator:
* **Saga State Machine:** Manages multi-step checkout state transitions with detailed audit step logging (`SagaStepLogger`, `SagaStepStatus`).
* **Kafka Event Consumers:** Listens to downstream domain events (`InventoryEventsConsumer`, `PaymentEventsConsumer`) and dispatches compensating commands (`InventoryReleasedEventHandler`, `PaymentInitializationFailedEventHandler`).
* **Transactional Outbox Writer:** Guarantees atomic persistence of domain events into the `outbox` table alongside order status updates. Captured and routed asynchronously by Debezium CDC.
* **Real-time SSE Event Dispatcher:** Listens to transactional domain events (`OrderSagaEventListener`) and streams live saga updates to clients via `SseEmitterRegistry`:
    * `PAYMENT_READY` / `PAYMENT_SESSION_ACTIVE` — Keeps connection open for checkout continuation.
    * `PAYMENT_FAILED` / `ORDER_CANCELLED` — Dispatches terminal failure updates and completes the emitter session.

### Payment Service
Acts as the single source of truth for payment lifecycle processing:
* **Intent Execution & Gateway Abstraction:** Processes payment intent creation (`paymentIntentId`), confirmation, and async charge commands (`ChargePaymentCommandHandler`) via provider interfaces (`PaymentGateway`, `MockPaymentGateway`).
* **Embedded PSP Simulator (MockStripe):** Exposes `/mock-stripe/payment_intents/{id}/confirm` to simulate card outcomes (`succeeded`, `card_declined`, `insufficient_funds`) and trigger async webhook dispatches with retries.
* **Payload Integrity & Verification:** Validates webhook signatures using HMAC verification (`HmacSignatureService`).
* **Outbox & Resilient Messaging:** Emits `PaymentInitiatedEvent` and payment outcome events atomically using the Transactional Outbox pattern, supported by dedicated Kafka retry topics and dead-letter queues (`KafkaRetryConfig`).

---

## 🧩 Shared Common Modules

| Module | Artifact ID | Description |
| :--- | :--- | :--- |
| **Exceptions** | `common-exceptions` | Business exception hierarchy (`BaseCustomException`), error codes (`CommonErrorCode`), and `@RestControllerAdvice` global handlers |
| **Kafka** | `common-kafka` | Polymorphic `AbstractEventConsumer` dispatcher, strongly-typed Protobuf `EventHandler` registry, and auto-configurations for exponential backoff retries, DLT recovery, non-retryable exception filtering, and Protobuf serialization |
| **Outbox** | `common-outbox` | Outbox pattern persistence model and Debezium CDC event publisher contracts |
| **Security** | `common-security` | HMAC signature verification service (`HmacSignatureService`) for payload integrity |
| **Events Contract** | `orderflow-events-contract` | Protobuf schemas and auto-generated Java classes for domain Kafka events (`inventory_commands`, `inventory_events`, `payment_commands`) |
| **gRPC Contract** | `orderflow-grpc-contract` | Protobuf definitions and generated stubs for synchronous inter-service gRPC calls |

---
## 🚀 Operations & Development Lifecycle

### 1. Build Shared Modules Locally
Install common contracts and utility dependencies into your local Maven repository:

```bash
mvn clean install -pl common/common-exceptions,common/common-kafka,common/common-outbox,common/common-security,common/orderflow-events-contract,common/orderflow-grpc-contract -am -DskipTests
```

### 2. Environment Startup
```bash
# Start full stack (Infrastructure + Microservices + Dev Tools)
make dev-up

# Or start core infrastructure only (Kafka, DBs, Connect, Schema Registry + Dev Tools)
make infra-up
```

### 3. Pipeline & Connector Setup
Execute initialization commands after infrastructure containers are healthy:

```bash
# 1. Provision Kafka topics via Terraform
make topics-apply

# 2. Register Protobuf schemas with Schema Registry
make register-schemas

# 3. Register Debezium CDC connectors with Kafka Connect
make register-connectors
```

### 4. Operational Commands
```bash
# Verify active Debezium connectors
curl -s http://localhost:8083/connectors | jq

# Rebuild and restart a single service after code changes
make rebuild-service MODULE=services/order-service SERVICE=order-service

# Teardown all containers and volumes
make down
```
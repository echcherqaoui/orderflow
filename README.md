# Orderflow

Event-driven microservices architecture utilizing Spring Boot 4, gRPC, PostgreSQL, Apache Kafka, Confluent Schema Registry, and Debezium Change Data Capture (CDC) via the Transactional Outbox Pattern.

---

## Technical Stack & Dependencies

* **Java:** 21 (LTS)
* **Framework:** Spring Boot `4.0.7` | Spring Cloud `2025.1.2` | Spring gRPC `1.0.3`
* **Build System:** Apache Maven (Root Multi-Module POM)
* **Database & Migrations:** PostgreSQL 17 | Flyway
* **Event Streaming & Schemas:** Apache Kafka (KRaft) | Confluent Schema Registry `7.7.7` | Protobuf `4.28.2`
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
│   ├── inventory-service/           # Inventory reservation microservice
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

## 🛠️ Building Shared Modules Locally

Because shared modules are nested under the `common/` directory, run builds from the project root using relative reactor paths:

```bash
# Build and install ALL common modules into local .m2
mvn clean install -pl common/common-exceptions,common/common-kafka,common/common-outbox,common/common-security,common/orderflow-events-contract,common/orderflow-grpc-contract -am -DskipTests

# Build a single module with its upstream dependencies (e.g., common-kafka)
mvn clean install -pl common/common-kafka -am -DskipTests
```

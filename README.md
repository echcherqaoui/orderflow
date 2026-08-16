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
│   └── orderflow-grpc-contracts/   # Protobuf schemas & generated gRPC stubs
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
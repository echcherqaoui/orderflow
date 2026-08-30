include .env
export

# ══════════════════════════════════════════════════════════════════
#  Orderflow — Makefile
# ══════════════════════════════════════════════════════════════════

PROJECT_NAME := orderflow

# Docker Compose File Definitions
INFRA_FILE      := -f docker/docker-compose.infra.yml
SERVICES_FILE   := -f docker/docker-compose.services.yml
TOOLS_FILE      := -f docker/docker-compose.tools.yml
TF_FILE         := -f docker/docker-compose.tf.yml
ENV_FILE        := --env-file .env
COMPOSE         := docker compose -p $(PROJECT_NAME) $(ENV_FILE)

.DEFAULT_GOAL := help

# ── Help ──────────────────────────────────────────────────────────
.PHONY: help
help:
	@echo ""
	@echo "  Orderflow"
	@echo ""
	@echo "  Build"
	@echo "    rebuild-service       Build and restart a specific service"
	@echo "                          Usage: make rebuild-service MODULE=infrastructure/discovery-service SERVICE=discovery-service"
	@echo ""
	@echo "  Execution"
	@echo "    infra-up              Start PostgreSQL, Kafka, Schema Registry, Kafka Connect"
	@echo "    apps-up               Start all services"
	@echo "    dev-up              Start full stack (infra + services + tools)"
	@echo "    dev-tools-up          Start dev tools (Kafka UI, pgAdmin)"
	@echo "    down                  Stop and remove all containers"
	@echo ""
	@echo "  Kafka (Run in this order)"
	@echo "    topics-apply          Create Kafka topics via Terraform"
	@echo "    register-schemas      Register Protobuf schemas to Schema Registry"
	@echo "    register-connectors   Register Debezium connectors"
	@echo ""

# ══════════════════════════════════════════════════════════════════
#  BUILD
# ══════════════════════════════════════════════════════════════════
.PHONY: rebuild-service
rebuild-service:
	@echo "→ Rebuilding $(MODULE)..."
	./mvnw clean package -pl $(MODULE) -am -DskipTests
	@echo "→ Restarting $(SERVICE)..."
	$(COMPOSE) $(INFRA_FILE) $(SERVICES_FILE) up -d --build $(SERVICE)
	@echo "✓ $(SERVICE) is updated and running"

# ══════════════════════════════════════════════════════════════════
#  EXECUTION
# ══════════════════════════════════════════════════════════════════
.PHONY: infra-up
infra-up: # Core infrastructure only
	$(COMPOSE) $(INFRA_FILE) up -d
	@echo "✓ Infrastructure started"

.PHONY: apps-up
apps-up: # Start all services & infrastructure
	$(COMPOSE) $(INFRA_FILE) $(SERVICES_FILE) up -d --remove-orphans
	@echo "✓ Services started"

.PHONY: dev-up
dev-up: # Start full stack — infrastructure + services + dev tools
	$(COMPOSE) $(INFRA_FILE) $(SERVICES_FILE) $(TOOLS_FILE) up -d --remove-orphans
	@echo "✓ Full stack started"

.PHONY: dev-tools-up
dev-tools-up: # Start observability stack
	$(COMPOSE) $(INFRA_FILE) $(TOOLS_FILE) up -d
	@echo "✓ Dev tools started"

.PHONY: down
down: # Stop and remove all containers
	$(COMPOSE) $(INFRA_FILE) $(SERVICES_FILE) $(TOOLS_FILE) down --remove-orphans
	@echo "✓ All containers stopped"

# ══════════════════════════════════════════════════════════════════
#  KAFKA
#  Run in order: topics-apply → register-schemas → register-connectors
# ══════════════════════════════════════════════════════════════════
.PHONY: topics-apply
topics-apply: # Create Kafka topics via Terraform (requires Kafka running)
	@echo "→ Initializing Terraform..."
	$(COMPOSE) $(TF_FILE) run --rm terraform init
	@echo "→ Applying Kafka topics..."
	$(COMPOSE) $(TF_FILE) run --rm terraform apply -auto-approve
	@echo "✓ Kafka topics created"

.PHONY: register-schemas
register-schemas: # Register Protobuf schemas to Schema Registry (requires Schema Registry running)
	./mvnw -pl common/orderflow-events-contract \
		-P register-schemas \
		io.confluent:kafka-schema-registry-maven-plugin:$(CONFLUENT_VERSION):register \
		-Dschema.registry.url=$(SC_REGISTRY_URL)
	@echo "✓ Schemas registered"

.PHONY: register-connectors
register-connectors: # Register Debezium connectors to Kafka Connect (requires topics created)
	@echo "→ Registering Debezium connectors..."
	@bash docker/connectors/register.sh
	@echo "✓ Connectors registered"
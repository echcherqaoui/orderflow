#!/bin/bash

set -a
source .env.local
set +a

# Override PG_HOST for container-to-container network resolution
# Use 'postgres' if Postgres is in Docker Compose, or 'host.docker.internal' if running on host OS
PG_CONNECT_HOST="${PG_HOST_OVERRIDE:-postgres}"

PG_HOST="${PG_CONNECT_HOST}" envsubst '${PG_USER} ${PG_PASSWORD} ${PG_PORT} ${ORDER_DB} ${PG_HOST}' < docker/connectors/order-outbox-connector.json | \
  curl -X POST http://localhost:${KF_CONNECT_PORT}/connectors -H "Content-Type: application/json" -d @-

PG_HOST="${PG_CONNECT_HOST}" envsubst '${PG_USER} ${PG_PASSWORD} ${PG_PORT} ${PAYMENT_DB} ${PG_HOST}' < docker/connectors/payment-outbox-connector.json | \
  curl -X POST http://localhost:${KF_CONNECT_PORT}/connectors -H "Content-Type: application/json" -d @-
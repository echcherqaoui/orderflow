#!/bin/bash

set -a
source .env
set +a

envsubst '${PG_USER} ${PG_PASSWORD} ${PG_PORT} ${ORDER_DB} ${PG_HOST}' < docker/connectors/order-outbox-connector.json | \
  curl -X POST http://localhost:${KF_CONNECT_PORT}/connectors -H "Content-Type: application/json" -d @-
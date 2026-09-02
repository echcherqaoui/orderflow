locals {
  retention_7_days    = "604800000"
  retention_1_hour    = "3600000"
  standard_partitions = 3
}

# ─────────────────────────────────────────────────────────────────────────────
# PAYMENT SERVICE TOPICS (Receiver Inbox + DLT)
# ─────────────────────────────────────────────────────────────────────────────
resource "kafka_topic" "orderflow_payment_commands" {
  name               = "orderflow.payment.commands"
  replication_factor = 1 # single broker — dev only, increase for production
  partitions         = local.standard_partitions
  config = {
    "cleanup.policy" = "delete"
    "retention.ms"   = local.retention_7_days
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "kafka_topic" "orderflow_payment_commands_dlt" {
  name               = "orderflow.payment.commands.dlt"
  replication_factor = 1
  partitions         = 1  # DLT need only 1 partition
  config = {
    "cleanup.policy" = "delete"
    "retention.ms"   = local.retention_7_days
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "kafka_topic" "orderflow_payment_events" {
  name               = "orderflow.payment.events"
  replication_factor = 1
  partitions         = local.standard_partitions
  config = {
    "cleanup.policy" = "delete"
    "retention.ms"   = local.retention_7_days
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "kafka_topic" "orderflow_payment_events_dlt" {
  name               = "orderflow.payment.events.dlt"
  replication_factor = 1
  partitions         = 1  # DLT need only 1 partition
  config = {
    "cleanup.policy" = "delete"
    "retention.ms"   = local.retention_7_days
  }

  lifecycle {
    prevent_destroy = true
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# DEBEZIUM CDC HEARTBEAT TOPIC
# ─────────────────────────────────────────────────────────────────────────────
resource "kafka_topic" "debezium_heartbeat" {
  name               = "__debezium-heartbeat.orderflow"
  replication_factor = 1
  partitions         = 1
  config = {
    "cleanup.policy" = "delete"
    "retention.ms"   = local.retention_1_hour
  }
}
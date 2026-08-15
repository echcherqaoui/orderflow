locals {
  retention_7_days    = "604800000"
  retention_1_hour    = "3600000"
  standard_partitions = 3
}

# ─────────────────────────────────────────────────────────────────────────────
# DEBEZIUM CDC HEARTBEAT TOPIC
# ─────────────────────────────────────────────────────────────────────────────
resource "kafka_topic" "debezium_heartbeat" {
  name               = "__debezium-heartbeat.orderflow"
  replication_factor = 1
  partitions         = 1

  config = {
    "retention.ms" = local.retention_1_hour
  }
}
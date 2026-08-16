# Bound automatically from docker-compose.tf.yml via TF_VAR_kafka_broker
variable "kafka_broker" {
  type        = string
  description = "Kafka bootstrap broker host:port passed via TF_VAR_kafka_broker"
}
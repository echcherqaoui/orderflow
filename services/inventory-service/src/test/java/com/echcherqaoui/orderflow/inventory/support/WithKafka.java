package com.echcherqaoui.orderflow.inventory.support;

import org.jspecify.annotations.NonNull;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

public interface WithKafka {

    String CONFLUENT_VERSION = "7.7.7";
    Network NETWORK = Network.newNetwork();

    ConfluentKafkaContainer KAFKA = startKafka();
    GenericContainer<?> SCHEMA_REGISTRY = startSchemaRegistry(KAFKA);

    @SuppressWarnings("resource")
    private static ConfluentKafkaContainer startKafka() {
        ConfluentKafkaContainer container = new ConfluentKafkaContainer(
              DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_VERSION))
              .withNetwork(NETWORK)
              .withNetworkAliases("kafka")
              .withListener("kafka:19092");
        container.start();
        return container;
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> startSchemaRegistry(ConfluentKafkaContainer kafka) {
        GenericContainer<?> container = new GenericContainer<>(
              DockerImageName.parse("confluentinc/cp-schema-registry:" + CONFLUENT_VERSION))
              .withExposedPorts(8081)
              .withNetwork(NETWORK)
              .withNetworkAliases("schema-registry")
              .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
              .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
              .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
              .waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
              .dependsOn(kafka);
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void configureKafdkaProperties(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url",
              () -> "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
    }

    @DynamicPropertySource
    static void configureKafkaProperties(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url",
              () -> "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
        registry.add("spring.kafka.properties.auto.register.schemas", () -> "true");
    }
}
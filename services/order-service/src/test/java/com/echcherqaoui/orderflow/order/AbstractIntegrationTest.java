package com.echcherqaoui.orderflow.order;

import org.jspecify.annotations.NonNull;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {
    private static final String CONFLUENT_VERSION = "7.7.7";

    private static final Network NETWORK = Network.newNetwork();

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17.0"))
                .withDatabaseName("order_db");

    protected static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer(
          DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_VERSION))
          .withNetwork(NETWORK)
          .withNetworkAliases("kafka")
          .withListener("kafka:19092");

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> SCHEMA_REGISTRY = new GenericContainer<>(
          DockerImageName.parse("confluentinc/cp-schema-registry:" + CONFLUENT_VERSION))
          .withExposedPorts(8081)
          .withNetwork(NETWORK)
          .withNetworkAliases("schema-registry")
          .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
          .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
          .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
          .waitingFor(Wait.forHttp("/subjects").forStatusCode(200))
          .dependsOn(KAFKA);

    static {
        POSTGRES.start();
        KAFKA.start();
        SCHEMA_REGISTRY.start();
    }

    @DynamicPropertySource
    static void configureProperties(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.properties.schema.registry.url",
              () -> "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081));
    }
}
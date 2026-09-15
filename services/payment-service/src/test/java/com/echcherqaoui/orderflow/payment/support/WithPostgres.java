package com.echcherqaoui.orderflow.payment.support;

import org.jspecify.annotations.NonNull;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public interface WithPostgres {

    PostgreSQLContainer POSTGRES = startPostgres();

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse("postgres:17.0"))
              .withDatabaseName("payment_db");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void configurePostgresProperties(@NonNull DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
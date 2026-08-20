    package com.echcherqaoui.orderflow.inventory;

    import org.jspecify.annotations.NonNull;
    import org.springframework.test.context.DynamicPropertyRegistry;
    import org.springframework.test.context.DynamicPropertySource;
    import org.springframework.test.context.TestPropertySource;
    import org.testcontainers.postgresql.PostgreSQLContainer;
    import org.testcontainers.utility.DockerImageName;

    /**
     * Shared Testcontainers Postgres instance for every inventory persistence test.
     */
    @TestPropertySource(properties = "spring.test.autoconfigure.exclude=")
    public abstract class AbstractIntegrationTest {

        // Singleton static container across all subclasses
        protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17.0"))
                    .withDatabaseName("inventory_db");

        static {
            POSTGRES.start(); // Started once when the abstract class is loaded
        }

        @DynamicPropertySource
        static void configureProperties(@NonNull DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        }
    }
package com.echcherqaoui.orderflow.common.outbox.autoconfigure;

import com.echcherqaoui.orderflow.common.outbox.partition.OutboxPartitionManager;
import com.echcherqaoui.orderflow.common.outbox.tracing.TraceContextProvider;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@AutoConfiguration
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@AutoConfigurationPackage(basePackages = "com.echcherqaoui.orderflow.common.outbox")
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(name = "outbox.partition.enabled", havingValue = "true", matchIfMissing = true)
    public OutboxPartitionManager outboxPartitionManager(JdbcTemplate jdbcTemplate,
                                                         @Value("${outbox.partition.retention-days:7}") int retentionDays,
                                                         @Value("${outbox.partition.future-days:2}") int futureDays) {
        return new OutboxPartitionManager(
              jdbcTemplate,
              retentionDays,
              futureDays
        );
    }

    /** Builds the W3C traceparent of the current span, stored in the outbox row for Debezium. */
    @Bean
    TraceContextProvider traceContextProvider(Tracer tracer, Propagator propagator) {
        return new TraceContextProvider(tracer, propagator);
    }
}
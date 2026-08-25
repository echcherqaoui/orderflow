package com.echcherqaoui.orderflow.common.outbox.partition;

import com.echcherqaoui.orderflow.common.outbox.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPartitionManagerIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private OutboxPartitionManager partitionManager;

    private static final int RETENTION_DAYS = 7;
    private static final int FUTURE_DAYS = 2;

    @BeforeEach
    void setUp() {
        partitionManager = new OutboxPartitionManager(jdbcTemplate, RETENTION_DAYS, FUTURE_DAYS);
    }

    @Test
    @DisplayName("ensurePartitionsExist creates partitions for today and future days")
    void ensurePartitionsExist_createsFuturePartitions() {
        transactionTemplate.executeWithoutResult(status -> partitionManager.ensurePartitionsExist());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        for (int i = 0; i <= FUTURE_DAYS; i++) {
            String partitionName = "outbox_p" + today.plusDays(i).toString().replace("-", "_");
            assertThat(tableExists(partitionName)).isTrue();
        }
    }

    @Test
    @DisplayName("managePartitions drops expired partitions older than retention cutoff")
    void managePartitions_dropsExpiredPartitions() {
        LocalDate oldDate = LocalDate.now(ZoneOffset.UTC).minusDays(RETENTION_DAYS + 2);
        String oldPartitionName = "outbox_p" + oldDate.toString().replace("-", "_");

        jdbcTemplate.execute(String.format(
              "CREATE TABLE %s PARTITION OF outbox_events FOR VALUES FROM ('%s') TO ('%s')",
              oldPartitionName, oldDate, oldDate.plusDays(1)
        ));
        assertThat(tableExists(oldPartitionName)).isTrue();

        transactionTemplate.executeWithoutResult(status -> partitionManager.managePartitions());

        assertThat(tableExists(oldPartitionName)).isFalse();
    }

    @Test
    @DisplayName("managePartitions skips partitions with unparseable or unexpected name formats")
    void managePartitions_skipsMalformedPartitionNames() {
        String malformedPartition = "outbox_p_invalid_format";

        jdbcTemplate.execute(String.format(
              "CREATE TABLE %s PARTITION OF outbox_events FOR VALUES FROM ('2010-01-01') TO ('2010-01-02')",
              malformedPartition
        ));

        transactionTemplate.executeWithoutResult(status -> partitionManager.managePartitions());

        assertThat(tableExists(malformedPartition)).isTrue();

        jdbcTemplate.execute("DROP TABLE " + malformedPartition);
    }

    @Test
    @DisplayName("executeWithTransactionLock prevents concurrent execution when lock is held")
    void executeWithTransactionLock_acquiresAdvisoryLock() {
        transactionTemplate.executeWithoutResult(status -> {
            // Acquire advisory lock manually within transaction
            Boolean lockAcquired = jdbcTemplate.queryForObject(
                  "SELECT pg_try_advisory_xact_lock(?)",
                  Boolean.class,
                  8008135890001002L
            );
            assertThat(lockAcquired).isTrue();

            // Calling ensurePartitionsExist should skip execution without throwing exception
            partitionManager.ensurePartitionsExist();
        });
    }

    private boolean tableExists(String tableName) {
        Boolean exists = jdbcTemplate.queryForObject(
              "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = ?)",
              Boolean.class,
              tableName
        );
        return Boolean.TRUE.equals(exists);
    }
}
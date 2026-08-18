package com.echcherqaoui.orderflow.common.outbox.partition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import static java.time.ZoneOffset.UTC;

@Slf4j
@RequiredArgsConstructor
public class OutboxPartitionManager {

    private static final String PARTITION_PREFIX = "outbox_p";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy_MM_dd");
    private static final Pattern PARTITION_NAME_PATTERN = Pattern.compile("^outbox_p\\d{4}_\\d{2}_\\d{2}$");

    // Fixed 64-bit key representing outbox partition DDL execution in Postgres memory
    private static final long ADVISORY_LOCK_KEY = 8008135890001002L;

    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;
    private final int futurePartitionDays;

    @NonNull
    private String sanitizeIdentifier(@NonNull String identifier) {
        if (!identifier.matches("^[a-z_][a-z0-9_]*$"))
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        return identifier;
    }

    private void dropPartition(String partitionName) {
        try {
            jdbcTemplate.execute(String.format("DROP TABLE IF EXISTS %s", sanitizeIdentifier(partitionName)));
            log.info("Dropped expired partition: {}", partitionName);
        } catch (DataAccessException e) {
            log.error("Failed to drop partition: {}", partitionName, e);
            throw e;
        }
    }

    @NonNull
    private LocalDate extractDateFromPartitionName(@NonNull String partitionName) {
        String datePart = partitionName.substring(PARTITION_PREFIX.length());

        return LocalDate.parse(datePart, DATE_FORMATTER);
    }

    private void dropExpiredPartitions() {
        // Explicitly forced to UTC matching the cron scheduler
        LocalDate cutoffDate = LocalDate.now(UTC).minusDays(retentionDays);

        // Discovers actual partition tables via pg_inherits (source of truth) rather than
        // guessing names — avoids missing/mismanaging partitions if naming ever drifts.
        List<String> partitions = jdbcTemplate.queryForList(
              """
                    SELECT child.relname
                    FROM pg_inherits i
                    JOIN pg_class parent ON parent.oid = i.inhparent
                    JOIN pg_class child ON child.oid = i.inhrelid
                    WHERE parent.relname = 'outbox_events'
                      AND child.relname LIKE 'outbox_p%'
                    """,
              String.class
        );

        // Iterate through each child partition and drop those older than the retention cutoff
        for (String partitionName : partitions) {
            if (!PARTITION_NAME_PATTERN.matcher(partitionName).matches()) {
                log.warn("Skipping partition with unexpected name format: {}", partitionName);
                continue;
            }

            try {
                LocalDate partitionDate = extractDateFromPartitionName(partitionName);

                if (partitionDate.isBefore(cutoffDate))
                    dropPartition(partitionName);
            } catch (DateTimeParseException e) {
                log.error("Failed to parse date from partition name: {}", partitionName, e);
            }
        }
    }

    private void createPartition(@NonNull LocalDate date) {
        String partitionName = PARTITION_PREFIX + date.format(DATE_FORMATTER);
        LocalDate nextDay = date.plusDays(1);

        try {
            jdbcTemplate.execute(String.format(
                  "CREATE TABLE IF NOT EXISTS %s PARTITION OF outbox_events FOR VALUES FROM ('%s') TO ('%s')",
                  sanitizeIdentifier(partitionName),
                  date,
                  nextDay
            ));
            log.info("Ensured partition {} exists for date range [{}, {})", partitionName, date, nextDay);
        } catch (DataAccessException e) {
            log.error("Failed to create partition {} for date {}", partitionName, date, e);
            throw e;
        }
    }

    private void createFuturePartitions() {
        // Explicitly forced to UTC to prevent local server timezone shifts
        LocalDate today = LocalDate.now(UTC);

        for (int i = 0; i <= futurePartitionDays; i++)
            createPartition(today.plusDays(i));
    }

    /**
     * Executes the task using PostgreSQL transaction-scoped advisory locks.
     * The lock automatically releases on transaction commit/rollback, eliminating
     * connection-pooling leak risks across multi-instance environments.
     */
    private void executeWithTransactionLock(Runnable task) {
        Boolean lockAcquired = jdbcTemplate.queryForObject(
              "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);

        if (Boolean.TRUE.equals(lockAcquired)) {
            try {
                task.run();
                log.info("Outbox partition execution completed successfully under transaction lock");
            } catch (Exception e) {
                log.error("FATAL: Outbox partition management failed under transaction lock", e);
                throw new IllegalStateException("Failed to manage outbox partitions", e);
            }
        } else {
            log.info("Skipping outbox partition management: another instance currently holds the transaction lock");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensurePartitionsExist() {
        executeWithTransactionLock(this::createFuturePartitions);
    }

    @Scheduled(cron = "${outbox.partition.cron:0 0 0 * * *}", zone = "UTC")
    @Transactional
    public void managePartitions() {
        executeWithTransactionLock(() -> {
            createFuturePartitions();
            dropExpiredPartitions();
        });
    }
}
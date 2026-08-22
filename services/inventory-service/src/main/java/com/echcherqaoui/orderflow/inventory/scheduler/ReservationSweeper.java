package com.echcherqaoui.orderflow.inventory.scheduler;

import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.EXPIRED;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;
import static java.util.concurrent.TimeUnit.SECONDS;


/**
 * Periodically sweeps for abandoned PENDING inventory reservations past their expiration window.
 * Processes records in batches (max 500) using pessimistic write locking with 'SKIP LOCKED'
 * at the repository level to allow concurrent multi-instance sweeping without row contention.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationSweeper {

    private final InventoryReservationRepository reservationRepository;
    private final ItemRepository itemRepository;

    @Scheduled(fixedDelay = 30, timeUnit = SECONDS)
    @Transactional
    public void sweep() {
        List<InventoryReservation> expired = reservationRepository
              .findExpiredPendingForUpdate(
                    PENDING,
                    Instant.now(),
                    PageRequest.of(0, 500)
              );

        if (expired.isEmpty()) {
            log.trace("Reservation sweep executed: no expired pending items found.");
            return;
        }

        // Count occurrences per itemId to handle duplicates across different carts
        Map<UUID, Integer> itemStockToRestore = expired.stream()
              .collect(Collectors.groupingBy(
                    InventoryReservation::getItemId,
                    Collectors.summingInt(e -> 1)
              ));

        // Invokes ItemRepositoryCustomImpl.incrementStockBatch(Map<UUID, Integer>)
        itemRepository.incrementStockBatchByCounts(itemStockToRestore);

        // Bulk update status in a single DB query
        List<UUID> reservationIds = expired.stream().map(InventoryReservation::getId).toList();
        reservationRepository.updateStatusByIds(reservationIds, EXPIRED);

        log.info("Successfully swept and expired {} orphaned reservations", expired.size());
    }
}

package com.echcherqaoui.orderflow.inventory.scheduler;

import com.echcherqaoui.orderflow.inventory.model.InventoryReservation;
import com.echcherqaoui.orderflow.inventory.repository.InventoryReservationRepository;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.EXPIRED;
import static com.echcherqaoui.orderflow.inventory.model.ReservationStatus.PENDING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSweeperTest {

    @Mock
    private InventoryReservationRepository reservationRepository;

    @Mock
    private ItemRepository itemRepository;

    @InjectMocks
    private ReservationSweeper reservationSweeper;

    @Test
    @DisplayName("no expired reservations exits early without updating stock or statuses")
    void sweep_noExpiredReservations_noOp() {
        when(reservationRepository.findExpiredPendingForUpdate(
              eq(PENDING),
              any(Instant.class),
              eq(PageRequest.of(0, 500)))
        ).thenReturn(Collections.emptyList());

        reservationSweeper.sweep();

        verify(itemRepository, never()).incrementStockBatchByCounts(any());
        verify(reservationRepository, never()).updateStatusByIds(any(), any());
    }

    @Test
    @DisplayName("expired reservations aggregates stock restock counts and updates status to EXPIRED")
    void sweep_expiredReservations_restoresStockAndUpdatesStatus() {
        UUID itemId1 = UUID.randomUUID();
        UUID itemId2 = UUID.randomUUID();

        InventoryReservation res1 = new InventoryReservation()
              .setId(UUID.randomUUID())
              .setItemId(itemId1)
              .setStatus(PENDING);

        InventoryReservation res2 = new InventoryReservation()
              .setId(UUID.randomUUID())
              .setItemId(itemId1)
              .setStatus(PENDING);

        InventoryReservation res3 = new InventoryReservation()
              .setId(UUID.randomUUID())
              .setItemId(itemId2)
              .setStatus(PENDING);

        List<InventoryReservation> expired = List.of(res1, res2, res3);

        when(reservationRepository.findExpiredPendingForUpdate(
              eq(PENDING),
              any(Instant.class),
              eq(PageRequest.of(0, 500)))
        ).thenReturn(expired);

        reservationSweeper.sweep();

        Map<UUID, Integer> expectedRestockMap = Map.of(
              itemId1, 2,
              itemId2, 1
        );

        verify(itemRepository).incrementStockBatchByCounts(expectedRestockMap);
        verify(reservationRepository).updateStatusByIds(List.of(res1.getId(), res2.getId(), res3.getId()), EXPIRED);
    }
}
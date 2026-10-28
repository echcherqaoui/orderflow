package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateOrderItemsException;
import com.echcherqaoui.orderflow.entitlement.exception.domain.EmptyOrderException;
import com.echcherqaoui.orderflow.entitlement.exception.domain.KeyPoolExhaustedException;
import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.ASSIGNED;
import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.AVAILABLE;

@Service
@RequiredArgsConstructor
public class EntitlementAllocationService {

    private final LicenseKeyRepository licenseKeyRepository;
    private final OutboxWriter outboxWriter;

    @Transactional
    public void allocate(List<String> itemIds,
                         UUID orderId,
                         String userId,
                         String triggerEventId) {
        if (itemIds == null || itemIds.isEmpty())
            throw new EmptyOrderException();

        Set<String> sorted = new TreeSet<>(itemIds); // consistent lock order

        if (sorted.size() != itemIds.size()) {
            Set<String> seen = new HashSet<>();
            List<String> duplicates = itemIds.stream()
                  .filter(id -> !seen.add(id))
                  .distinct()
                  .toList();

            throw new DuplicateOrderItemsException(duplicates);
        }

        Instant now = Instant.now();
        List<String> accessKeys = new ArrayList<>(sorted.size());

        for (String rawItemId : sorted) {
            UUID itemId = UUID.fromString(rawItemId);
            LicenseKey key = licenseKeyRepository
                  .findFirstByItemIdAndStatusOrderByIdAsc(itemId, AVAILABLE)
                  .orElseThrow(() -> new KeyPoolExhaustedException(itemId));

            key.setStatus(ASSIGNED)
                  .setOrderId(orderId)
                  .setUserId(userId)
                  .setAssignedAt(now);

            accessKeys.add(key.getKeyCode());
        }

        licenseKeyRepository.flush(); // unique violation surfaces here, not at commit
        outboxWriter.writeEntitlementGranted(
              orderId,
              userId,
              accessKeys,
              triggerEventId
        );
    }
}
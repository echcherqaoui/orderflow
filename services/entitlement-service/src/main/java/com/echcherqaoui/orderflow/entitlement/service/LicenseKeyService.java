package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionResponse;
import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateKeysException;
import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import com.echcherqaoui.orderflow.entitlement.util.ItemIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.AVAILABLE;
import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.PENDING_PROVISION;
import static com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus.PROVISION_REJECTED;

@Service
@RequiredArgsConstructor
@Slf4j
public class LicenseKeyService {

    private final LicenseKeyRepository licenseKeyRepository;
    private final OutboxWriter outboxWriter;

    @Transactional
    public BatchIngestionResponse ingestKeys(@lombok.NonNull BatchIngestionRequest request) {
        String itemName = request.itemName();
        Long priceCents = request.priceCents();
        List<String> keys = request.keys();

        // Check for duplicates within the incoming request payload itself
        List<String> payloadDuplicates = keys.stream()
              .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
              .entrySet().stream()
              .filter(e -> e.getValue() > 1)
              .map(Map.Entry::getKey)
              .toList();

        if (!payloadDuplicates.isEmpty())
            throw new DuplicateKeysException(payloadDuplicates);

        List<String> existingKeys = licenseKeyRepository.findExistingKeyCodes(keys);

        if (!existingKeys.isEmpty())
            throw new DuplicateKeysException(existingKeys); // Custom business exception

        UUID itemId = ItemIds.fromName(itemName);

        // Map strings to LicenseKey entities including price
        List<LicenseKey> keysToSave = keys.stream()
              .map(code ->
                    new LicenseKey()
                          .setItemId(itemId)
                          .setItemName(itemName)
                          .setKeyCode(code)
                          .setPriceCents(priceCents)
                          .setStatus(PENDING_PROVISION)
              ).toList();

        licenseKeyRepository.saveAll(keysToSave);

        int count = keys.size();

        // Write outbox event
        outboxWriter.publishDigitalStockProvisionedEvent(itemId, itemName, priceCents, count);

        return new BatchIngestionResponse(
              "License keys ingested successfully",
              itemName,
              count
        );
    }

    @Transactional
    public void completeProvisioning(String itemName, int expectedQuantity) {
        int updatedCount = licenseKeyRepository.updateStatusForProduct(
              itemName,
              PENDING_PROVISION,
              AVAILABLE
        );

        if (updatedCount != expectedQuantity)
            log.warn(
                  "Stock provision mismatch for product {}: expected {} keys, but updated {}.",
                  itemName,
                  expectedQuantity,
                  updatedCount
            );
        else
            log.info(
                  "Successfully provisioned {} keys for product {}",
                  updatedCount,
                  itemName
            );
    }

    @Transactional
    public void rejectProvisioning(String itemName, String reason) {
        int updatedCount = licenseKeyRepository.updateStatusForProduct(
              itemName,
              PENDING_PROVISION,
              PROVISION_REJECTED
        );

        log.warn(
              "Provisioning rejected for product {} (reason: {}). Marked {} pending keys as rejected.",
              itemName,
              reason,
              updatedCount
        );
    }
}
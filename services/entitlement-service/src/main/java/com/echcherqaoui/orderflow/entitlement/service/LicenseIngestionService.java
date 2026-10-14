package com.echcherqaoui.orderflow.entitlement.service;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionResponse;
import com.echcherqaoui.orderflow.entitlement.exception.domain.DuplicateKeysException;
import com.echcherqaoui.orderflow.entitlement.messaging.outbox.OutboxWriter;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKey;
import com.echcherqaoui.orderflow.entitlement.model.LicenseKeyStatus;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LicenseIngestionService {

    private final LicenseKeyRepository licenseKeyRepository;
    private final OutboxWriter outboxWriter;

    @Transactional
    public BatchIngestionResponse ingestKeys(@lombok.NonNull BatchIngestionRequest request) {
        String productId = request.productId();
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

        // Map strings to LicenseKey entities including price
        List<LicenseKey> keysToSave = keys.stream()
              .map(code ->
                    new LicenseKey()
                          .setProductId(productId)
                          .setKeyCode(code)
                          .setPriceCents(priceCents)
                          .setStatus(LicenseKeyStatus.AVAILABLE)
              ).toList();

        licenseKeyRepository.saveAll(keysToSave);

        int count = keys.size();

        // Write outbox event
        outboxWriter.publishDigitalStockProvisionedEvent(productId, priceCents, count);

        return new BatchIngestionResponse(
              "License keys ingested successfully",
              productId,
              count
        );
    }
}
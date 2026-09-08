package com.echcherqaoui.orderflow.inventory.service;

import com.echcherqaoui.orderflow.inventory.dto.request.CreateItemRequest;
import com.echcherqaoui.orderflow.inventory.dto.response.ItemResponse;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemAlreadyExistsException;
import com.echcherqaoui.orderflow.inventory.model.Item;
import com.echcherqaoui.orderflow.inventory.repository.ItemRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class ItemService {

    private final ItemRepository itemRepository;

    @Transactional
    public ItemResponse createItem(@Valid @NonNull CreateItemRequest request) {
        if (itemRepository.existsByName(request.name()))
            throw new ItemAlreadyExistsException(request.name());

        Item item = new Item()
                .setName(request.name())
                .setPriceCents(request.priceCents())
                .setTotalEarlyAccessUnits(request.totalEarlyAccessUnits())
                .setRemainingUnits(request.totalEarlyAccessUnits());

        return ItemResponse.fromEntity(itemRepository.save(item));
    }
}
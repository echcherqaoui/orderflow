package com.echcherqaoui.orderflow.inventory.seeder;

import com.echcherqaoui.orderflow.inventory.dto.request.CreateItemRequest;
import com.echcherqaoui.orderflow.inventory.exception.domain.ItemAlreadyExistsException;
import com.echcherqaoui.orderflow.inventory.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class ItemDataSeeder implements ApplicationRunner {

    private final ItemService itemService;

    private static final List<CreateItemRequest> INITIAL_ITEMS = List.of(
          new CreateItemRequest("VIP Early Access Pass", 15000, 10),
          new CreateItemRequest("Standard Early Access Pass", 5000, 50),
          new CreateItemRequest("Platinum Founder Pass", 50000, 5),
          new CreateItemRequest("Developer Workshop Ticket", 25000, 10),
          new CreateItemRequest("Community Supporter Badge", 1000, 50)
    );

    @Override
    public void run(@NonNull ApplicationArguments args) {
        for (CreateItemRequest command : INITIAL_ITEMS) {
            try {
                itemService.createItem(command);
                log.info("Dev seed created item: {}", command.name());
            } catch (ItemAlreadyExistsException e) {
                log.debug("Dev seed, item already exists: {}", command.name());
            }
        }
    }
}
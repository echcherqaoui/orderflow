package com.echcherqaoui.orderflow.order.config;

import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc;
import com.echcherqaoui.orderflow.inventory.grpc.InventoryServiceGrpc.InventoryServiceBlockingStub;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * Configures gRPC client stubs as Spring beans using official Spring gRPC.
 * Binds the 'inventory-service' channel configuration (from spring.grpc.client)
 * via {@link GrpcChannelFactory} to expose an injectable {@link InventoryServiceBlockingStub}.
 */
@Configuration
public class GrpcClientConfig {

    @Bean
    public InventoryServiceBlockingStub inventoryServiceBlockingStub(@NonNull GrpcChannelFactory channelFactory) {
        return InventoryServiceGrpc.newBlockingStub(channelFactory.createChannel("inventory-service"));
    }
}

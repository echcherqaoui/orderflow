package com.echcherqaoui.orderflow.entitlement;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@RequiredArgsConstructor
public class EntitlementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EntitlementServiceApplication.class, args);
    }
}

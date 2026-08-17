package com.echcherqaoui.orderflow.exception.autoconfigure;

import com.echcherqaoui.orderflow.exception.handler.GrpcServerExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.GrpcServerFactory;

@AutoConfiguration
@ConditionalOnClass(GrpcServerFactory.class)
public class GrpcServerExceptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GrpcServerExceptionHandler grpcServerExceptionHandler() {
        return new GrpcServerExceptionHandler();
    }
}
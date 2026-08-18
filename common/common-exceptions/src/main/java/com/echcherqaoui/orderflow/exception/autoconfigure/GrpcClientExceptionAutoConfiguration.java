package com.echcherqaoui.orderflow.exception.autoconfigure;

import com.echcherqaoui.orderflow.exception.handler.GrpcClientExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.client.GrpcChannelFactory;

@AutoConfiguration
@ConditionalOnClass(GrpcChannelFactory.class)
public class GrpcClientExceptionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GrpcClientExceptionHandler grpcClientExceptionHandler() {
        return new GrpcClientExceptionHandler();
    }
}
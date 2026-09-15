package com.echcherqaoui.orderflow.kafka.config;

import com.echcherqaoui.orderflow.exception.core.EventProcessingException;
import com.echcherqaoui.orderflow.exception.core.EventSecurityException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@AutoConfiguration
@Slf4j
public class KafkaRetryConfig {

    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);
        backOff.setMaxElapsedTime(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        handler.addNotRetryableExceptions(
              DataIntegrityViolationException.class,
              EventProcessingException.class,
              EventSecurityException.class
        );

        handler.setLogLevel(KafkaException.Level.ERROR);

        handler.setRetryListeners((consumerRecord, failure, attempt) ->
              log.warn(
                    "Retry attempt {} for topic: {} partition: {} offset: {}",
                    attempt,
                    consumerRecord.topic(),
                    consumerRecord.partition(),
                    consumerRecord.offset(),
                    failure
              )
        );

        return handler;
    }
}
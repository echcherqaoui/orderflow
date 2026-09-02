package com.echcherqaoui.orderflow.payment.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class KafkaConfig {

    private final KafkaProperties kafkaProperties;

    // ── DLT Producer ──────────────────────────────────────────────
    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate() {
        return new KafkaTemplate<>(
              new DefaultKafkaProducerFactory<>(
                    kafkaProperties.buildProducerProperties()
              )
        );
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(KafkaTemplate<String, Object> dltKafkaTemplate) {
        return new DeadLetterPublishingRecoverer(
              dltKafkaTemplate,
              (consumerRecord, ex) ->
                    new TopicPartition(
                          consumerRecord.topic() + ".dlt",
                          -1 // -1: producer partitioner decides
                    )
        );
    }
}
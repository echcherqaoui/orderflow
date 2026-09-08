package com.echcherqaoui.orderflow.kafka.config;

import org.apache.kafka.common.TopicPartition;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

@AutoConfiguration
public class KafkaDltConfig {

    @Bean
    @ConditionalOnMissingBean
    public KafkaTemplate<String, Object> dltKafkaTemplate(@NonNull KafkaProperties kafkaProperties) {
        return new KafkaTemplate<>(
              new DefaultKafkaProducerFactory<>(
                    kafkaProperties.buildProducerProperties()
              )
        );
    }

    @Bean
    @ConditionalOnMissingBean
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
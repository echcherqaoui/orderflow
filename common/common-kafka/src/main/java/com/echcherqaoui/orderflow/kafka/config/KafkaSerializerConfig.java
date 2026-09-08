package com.echcherqaoui.orderflow.kafka.config;

import com.google.protobuf.Message;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class KafkaSerializerConfig {

    @Bean
    @ConditionalOnMissingBean
    public KafkaProtobufSerializer<Message> outboxProtobufSerializer(@NonNull KafkaProperties kafkaProperties) {
        KafkaProtobufSerializer<Message> serializer = new KafkaProtobufSerializer<>();
        serializer.configure(kafkaProperties.buildProducerProperties(), false);
        return serializer;
    }
}
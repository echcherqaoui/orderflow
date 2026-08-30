package com.echcherqaoui.orderflow.order.config;

import com.google.protobuf.Message;
import io.confluent.kafka.serializers.protobuf.KafkaProtobufSerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@RequiredArgsConstructor
public class KafkaSerializerConfig {
    private final KafkaProperties kafkaProperties;

    @Bean
    public KafkaProtobufSerializer<Message> outboxProtobufSerializer() {
        KafkaProtobufSerializer<Message> serializer = new KafkaProtobufSerializer<>();
        serializer.configure(
              kafkaProperties.buildProducerProperties(),
              false
        );

        return serializer;
    }
}

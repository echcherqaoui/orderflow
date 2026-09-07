package com.echcherqaoui.orderflow.kafka.config;

import com.echcherqaoui.orderflow.exception.core.EventProcessingException;
import com.echcherqaoui.orderflow.exception.core.EventSecurityException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaRetryConfigTest {

    @Mock
    private DeadLetterPublishingRecoverer recoverer;

    private KafkaRetryConfig kafkaRetryConfig;
    private ConsumerRecord<String, Object> consumerRecord;

    @BeforeEach
    void setUp() {
        kafkaRetryConfig = new KafkaRetryConfig();
        consumerRecord = new ConsumerRecord<>("test-topic", 0, 100L, "key", "payload");
    }

    @Test
    void errorHandler_routesNonRetryableExceptionsToRecovererImmediately() {
        DefaultErrorHandler handler = kafkaRetryConfig.errorHandler(recoverer);

        DataIntegrityViolationException dataIntegrityEx = new DataIntegrityViolationException("test");
        DeserializationException deserializationEx = new DeserializationException("test", new byte[0], false, null);
        EventProcessingException processingEx = mock(EventProcessingException.class);
        EventSecurityException securityEx = mock(EventSecurityException.class);
        MessageConversionException messageConversionEx = new MessageConversionException("test");

        handler.handleOne(dataIntegrityEx, consumerRecord, null, null);
        handler.handleOne(deserializationEx, consumerRecord, null, null);
        handler.handleOne(processingEx, consumerRecord, null, null);
        handler.handleOne(securityEx, consumerRecord, null, null);
        handler.handleOne(messageConversionEx, consumerRecord, null, null);

        verify(recoverer).accept(eq(consumerRecord), any(), eq(dataIntegrityEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(deserializationEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(processingEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(securityEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(messageConversionEx));
    }

    @Test
    void errorHandler_returnsTrueForNonRetryableException_signalingNoFurtherRetries() {
        DefaultErrorHandler handler = kafkaRetryConfig.errorHandler(recoverer);
        EventProcessingException processingEx = mock(EventProcessingException.class);

        boolean handled = handler.handleOne(processingEx, consumerRecord, null, null);

        assertThat(handled).isTrue();
    }

    @Test
    void errorHandler_doesNotRouteRetryableExceptionToRecovererOnFirstAttempt() {
        DefaultErrorHandler handler = kafkaRetryConfig.errorHandler(recoverer);
        RuntimeException transientEx = new RuntimeException("transient failure");

        boolean handled = handler.handleOne(transientEx, consumerRecord, null, null);

        assertThat(handled).isFalse();
        verify(recoverer, never()).accept(eq(consumerRecord), any(), eq(transientEx));
    }

    @Test
    void errorHandler_routesRetryableExceptionToRecovererAfterBackoffExhausted() {
        DefaultErrorHandler handler = kafkaRetryConfig.errorHandler(recoverer);
        RuntimeException transientEx = new RuntimeException("transient failure");

        boolean handled = false;
        for (int attempt = 0; attempt < 10 && !handled; attempt++)
            handled = handler.handleOne(transientEx, consumerRecord, null, null);

        assertThat(handled).isTrue();
        verify(recoverer).accept(eq(consumerRecord), any(), eq(transientEx));
    }
}
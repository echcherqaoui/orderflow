package com.echcherqaoui.orderflow.payment.config;

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
import org.springframework.util.backoff.ExponentialBackOff;

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
        consumerRecord = new ConsumerRecord<>("payment-topic", 0, 100L, "key", "payload");
    }

    @Test
    void errorHandler_routesNonRetryableExceptionsToRecovererImmediately() {
        DefaultErrorHandler handler = kafkaRetryConfig.errorHandler(recoverer);

        EventSecurityException securityEx = mock(EventSecurityException.class);
        EventProcessingException processingEx = mock(EventProcessingException.class);
        DataIntegrityViolationException dataIntegrityEx = new DataIntegrityViolationException("test");
        DeserializationException deserializationEx = new DeserializationException("test", new byte[0], false, null);
        MessageConversionException messageConversionEx = new MessageConversionException("test");

        handler.handleOne(securityEx, consumerRecord, null, null);
        handler.handleOne(dataIntegrityEx, consumerRecord, null, null);
        handler.handleOne(deserializationEx, consumerRecord, null, null);
        handler.handleOne(processingEx, consumerRecord, null, null);
        handler.handleOne(messageConversionEx, consumerRecord, null, null);

        verify(recoverer).accept(eq(consumerRecord), any(), eq(securityEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(dataIntegrityEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(deserializationEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(processingEx));
        verify(recoverer).accept(eq(consumerRecord), any(), eq(messageConversionEx));
    }

    @Test
    void errorHandler_returnsTrueForNonRetryableException_signalingNoFurtherRetries() {
        DefaultErrorHandler handler = kafkaRetryConfig.errorHandler(recoverer);
        EventSecurityException securityEx = mock(EventSecurityException.class);

        boolean handled = handler.handleOne(securityEx, consumerRecord, null, null);

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

        // maxElapsedTime=30_000ms, initialInterval=1_000ms, multiplier=2.0
        // -> retries stop being scheduled after ~5-6 attempts; keep calling
        // until DefaultErrorHandler itself delegates to the recoverer.
        boolean handled = false;
        for (int attempt = 0; attempt < 10 && !handled; attempt++)
            handled = handler.handleOne(transientEx, consumerRecord, null, null);


        assertThat(handled).isTrue();
        verify(recoverer).accept(eq(consumerRecord), any(), eq(transientEx));
    }

    @Test
    void buildBackOff_usesExpectedExponentialParameters() {
        ExponentialBackOff backOff = KafkaRetryConfig.buildBackOff();

        assertThat(backOff.getInitialInterval()).isEqualTo(1_000L);
        assertThat(backOff.getMultiplier()).isEqualTo(2.0);
        assertThat(backOff.getMaxInterval()).isEqualTo(8_000L);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(30_000L);
    }

    @Test
    void errorHandler_invokesRetryListenerOnRetryableFailure() {
        DefaultErrorHandler handler = kafkaRetryConfig.errorHandler(recoverer);
        RuntimeException transientEx = new RuntimeException("transient failure");

        // Retry listener logs; assert it doesn't throw and the record is
        // still eligible for retry (handled == false) rather than asserting
        // log content, which is an implementation detail.
        boolean handled = handler.handleOne(transientEx, consumerRecord, null, null);

        assertThat(handled).isFalse();
    }
}
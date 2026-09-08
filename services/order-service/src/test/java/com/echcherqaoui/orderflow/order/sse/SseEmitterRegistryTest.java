package com.echcherqaoui.orderflow.order.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SseEmitterRegistryTest {

    private SseEmitterRegistry sseEmitterRegistry;

    private final UUID orderId = UUID.randomUUID();

    @Mock
    private SseEmitter mockEmitter;

    @BeforeEach
    void setUp() {
        sseEmitterRegistry = new SseEmitterRegistry();
    }

    @Nested
    @DisplayName("register()")
    class Register {

        @Test
        @DisplayName("registers and returns SseEmitter configured with 5-minute timeout and stores it in registry")
        void register_success_createsAndStoresEmitter() {
            SseEmitter emitter = sseEmitterRegistry.register(orderId);

            assertThat(emitter).isNotNull();
            assertThat(emitter.getTimeout()).isEqualTo(300000L);
            assertThat(getEmitters()).containsEntry(orderId, emitter);
        }

        @Test
        @DisplayName("onCompletion callback removes emitter from registry")
        void register_onCompletionCallback_removesEmitterFromRegistry() {
            SseEmitter emitter = sseEmitterRegistry.register(orderId);
            assertThat(getEmitters()).containsKey(orderId);

            triggerCompletion(emitter);

            assertThat(getEmitters()).doesNotContainKey(orderId);
        }

        @Test
        @DisplayName("onTimeout callback removes emitter from registry and completes emitter")
        void register_onTimeoutCallback_removesEmitterFromRegistryAndCompletes() {
            SseEmitter emitter = sseEmitterRegistry.register(orderId);
            assertThat(getEmitters()).containsKey(orderId);

            triggerTimeout(emitter);

            assertThat(getEmitters()).doesNotContainKey(orderId);
        }

        @Test
        @DisplayName("onError callback removes emitter from registry")
        void register_onErrorCallback_removesEmitterFromRegistry() {
            SseEmitter emitter = sseEmitterRegistry.register(orderId);
            assertThat(getEmitters()).containsKey(orderId);

            triggerError(emitter, new RuntimeException("Client disconnected"));

            assertThat(getEmitters()).doesNotContainKey(orderId);
        }
    }

    @Nested
    @DisplayName("sendAndKeepOpen()")
    class SendAndKeepOpen {

        @Test
        @DisplayName("does nothing when no emitter is registered for given orderId")
        void sendAndKeepOpen_nonExistentOrderId_doesNothing() {
            assertThatCode(() -> sseEmitterRegistry.sendAndKeepOpen(orderId, "payload"))
                  .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("sends event and retains emitter in registry on success")
        void sendAndKeepOpen_success_sendsEventAndKeepsEmitter() throws Exception {
            getEmitters().put(orderId, mockEmitter);

            sseEmitterRegistry.sendAndKeepOpen(orderId, "payload");

            verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
            assertThat(getEmitters()).containsKey(orderId);
        }

        @Test
        @DisplayName("removes emitter and completes with error when send fails with IOException")
        void sendAndKeepOpen_ioException_removesEmitterAndCompletesWithError() throws Exception {
            getEmitters().put(orderId, mockEmitter);
            IOException ioException = new IOException("Connection reset by peer");
            doThrow(ioException).when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));

            sseEmitterRegistry.sendAndKeepOpen(orderId, "payload");

            verify(mockEmitter).completeWithError(ioException);
            assertThat(getEmitters()).doesNotContainKey(orderId);
        }
    }

    @Nested
    @DisplayName("sendAndComplete()")
    class SendAndComplete {

        @Test
        @DisplayName("does nothing when no emitter is registered for given orderId")
        void sendAndComplete_nonExistentOrderId_doesNothing() {
            assertThatCode(() -> sseEmitterRegistry.sendAndComplete(orderId, "payload"))
                  .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("sends event, completes emitter, and removes emitter from registry on success")
        void sendAndComplete_success_sendsEventCompletesAndRemovesEmitter() throws Exception {
            getEmitters().put(orderId, mockEmitter);

            sseEmitterRegistry.sendAndComplete(orderId, "payload");

            verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));
            verify(mockEmitter).complete();
            assertThat(getEmitters()).doesNotContainKey(orderId);
        }

        @Test
        @DisplayName("removes emitter and completes with error when send fails with IOException")
        void sendAndComplete_ioException_removesEmitterAndCompletesWithError() throws Exception {
            getEmitters().put(orderId, mockEmitter);
            IOException ioException = new IOException("Broken pipe");
            doThrow(ioException).when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));

            sseEmitterRegistry.sendAndComplete(orderId, "payload");

            verify(mockEmitter).completeWithError(ioException);
            verify(mockEmitter, never()).complete();
            assertThat(getEmitters()).doesNotContainKey(orderId);
        }
    }

    @Nested
    @DisplayName("remove()")
    class Remove {

        @Test
        @DisplayName("removes registered emitter associated with given orderId")
        void remove_existingOrderId_removesEmitterFromRegistry() {
            sseEmitterRegistry.register(orderId);
            assertThat(getEmitters()).containsKey(orderId);

            sseEmitterRegistry.remove(orderId);

            assertThat(getEmitters()).doesNotContainKey(orderId);
        }

        @Test
        @DisplayName("removing non-existent orderId executes safely without exception")
        void remove_nonExistentOrderId_doesNothing() {
            assertThat(getEmitters()).doesNotContainKey(orderId);

            sseEmitterRegistry.remove(orderId);

            assertThat(getEmitters()).doesNotContainKey(orderId);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, SseEmitter> getEmitters() {
        return (Map<UUID, SseEmitter>) ReflectionTestUtils.getField(sseEmitterRegistry, "emitters");
    }

    private void triggerCompletion(SseEmitter emitter) {
        Runnable callback = (Runnable) ReflectionTestUtils.getField(emitter, "completionCallback");
        if (callback != null)
            callback.run();
    }

    private void triggerTimeout(SseEmitter emitter) {
        Runnable callback = (Runnable) ReflectionTestUtils.getField(emitter, "timeoutCallback");
        if (callback != null)
            callback.run();
    }

    @SuppressWarnings("unchecked")
    private void triggerError(SseEmitter emitter, Throwable error) {
        Consumer<Throwable> callback = (Consumer<Throwable>) ReflectionTestUtils.getField(emitter, "errorCallback");
        if (callback != null)
            callback.accept(error);
    }
}
package com.echcherqaoui.orderflow.order.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterRegistryTest {

    private SseEmitterRegistry sseEmitterRegistry;

    private final UUID orderId = UUID.randomUUID();

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
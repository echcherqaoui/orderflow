package com.echcherqaoui.orderflow.order.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active SseEmitters keyed by orderId.
 * Single-instance only — does not support horizontal scaling.
 * If order-service ever runs >1 replica, completions may be dropped
 * for emitters registered on a different instance. Revisit with
 * Redis pub/sub if that changes.
 */
@Component
@Slf4j
public class SseEmitterRegistry {

    private static final long TIMEOUT_MILLIS = 5 * 60 * 1000L; // 5 min

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(UUID orderId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        emitters.put(orderId, emitter);

        emitter.onCompletion(() -> emitters.remove(orderId, emitter));
        emitter.onTimeout(() -> {
            emitters.remove(orderId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> emitters.remove(orderId, emitter));

        return emitter;
    }

    public void sendAndKeepOpen(@lombok.NonNull UUID orderId,
                                @lombok.NonNull Object data) {
        SseEmitter emitter = emitters.get(orderId);
        if (emitter == null) {
            log.debug("No active SSE connection found for order {}", orderId);
            return;
        }

        try {
            emitter.send(
                  SseEmitter.event()
                        .name("order-status")
                        .data(data)
            );
        } catch (IOException e) {
            log.warn("Failed to push SSE event for order {}. Removing emitter: {}", orderId, e.getMessage());
            emitters.remove(orderId, emitter);
            emitter.completeWithError(e);
        }
    }



    public void sendAndComplete(@lombok.NonNull UUID orderId, @lombok.NonNull Object data) {
        SseEmitter emitter = emitters.get(orderId);
        if (emitter == null) {
            log.debug("No active SSE connection found for order {}", orderId);
            return;
        }

        try {
            emitter.send(
                  SseEmitter.event()
                        .name("order-status")
                        .data(data)
            );
            emitter.complete();
        } catch (IOException e) {
            log.warn("Failed to push SSE completion event for order {}: {}", orderId, e.getMessage());
            emitter.completeWithError(e);
        } finally {
            emitters.remove(orderId);
        }
    }

    public void remove(UUID orderId) {
        emitters.remove(orderId);
    }
}
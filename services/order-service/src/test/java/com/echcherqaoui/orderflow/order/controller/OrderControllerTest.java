package com.echcherqaoui.orderflow.order.controller;

import com.echcherqaoui.orderflow.order.dto.CreateOrderRequest;
import com.echcherqaoui.orderflow.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    @InjectMocks
    private OrderController orderController;

    @Captor
    private ArgumentCaptor<CreateOrderRequest> requestCaptor;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String validCartId = "cart-123";
    private final String validUserId = "user@example.com";
    private final List<String> validItemIds = List.of("item-1", "item-2");

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(orderController)
              .addPlaceholderValue("api.base-path", "/api/v1")
              .setValidator(validator)
              .build();
    }

    @Nested
    @DisplayName("createOrder()")
    class CreateOrder {

        @Test
        @DisplayName("direct method invocation returns 200 OK with SseEmitter body from service")
        void createOrder_directInvocation_returns200OkWithSseEmitter() {
            CreateOrderRequest request = new CreateOrderRequest(validCartId, validUserId, validItemIds);
            SseEmitter expectedEmitter = new SseEmitter();
            given(orderService.createOrder(any(CreateOrderRequest.class))).willReturn(expectedEmitter);

            ResponseEntity<SseEmitter> response = orderController.createOrder(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(expectedEmitter);

            then(orderService).should().createOrder(requestCaptor.capture());
            assertThat(requestCaptor.getValue()).isEqualTo(request);
        }

        @Test
        @DisplayName("valid HTTP payload passes validation, returning 200 OK and text/event-stream media type")
        void createOrder_validPayload_returns200OkAndTextEventStreamMediaType() throws Exception {
            CreateOrderRequest request = new CreateOrderRequest(validCartId, validUserId, validItemIds);
            SseEmitter expectedEmitter = new SseEmitter();
            given(orderService.createOrder(any(CreateOrderRequest.class))).willReturn(expectedEmitter);

            mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                  .andExpect(status().isOk())
                  .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM));

            then(orderService).should().createOrder(requestCaptor.capture());
            CreateOrderRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.cartId()).isEqualTo(validCartId);
            assertThat(capturedRequest.userId()).isEqualTo(validUserId);
            assertThat(capturedRequest.itemIds()).isEqualTo(validItemIds);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRequestProvider")
        @DisplayName("invalid payload violates validation constraints and returns 400 Bad Request")
        void createOrder_invalidPayload_returns400BadRequest(String testName, CreateOrderRequest invalidRequest) throws Exception {
            mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                  .andExpect(status().isBadRequest());

            verifyNoInteractions(orderService);
        }

        private static Stream<Arguments> invalidRequestProvider() {
            return Stream.of(
                  Arguments.of("blank cartId", new CreateOrderRequest("", "user@example.com", List.of("item-1"))),
                  Arguments.of("null cartId", new CreateOrderRequest(null, "user@example.com", List.of("item-1"))),
                  Arguments.of("blank userId", new CreateOrderRequest("cart-123", "", List.of("item-1"))),
                  Arguments.of("invalid email structure for userId", new CreateOrderRequest("cart-123", "not-an-email", List.of("item-1"))),
                  Arguments.of("null userId", new CreateOrderRequest("cart-123", null, List.of("item-1"))),
                  Arguments.of("empty itemIds list", new CreateOrderRequest("cart-123", "user@example.com", List.of())),
                  Arguments.of("null itemIds list", new CreateOrderRequest("cart-123", "user@example.com", null)),
                  Arguments.of("blank element inside itemIds list", new CreateOrderRequest("cart-123", "user@example.com", List.of("item-1", "   ")))
            );
        }

        @Test
        @DisplayName("service failure propagates exception during execution")
        void createOrder_serviceFails_propagatesException() {
            CreateOrderRequest request = new CreateOrderRequest(validCartId, validUserId, validItemIds);
            RuntimeException serviceException = new RuntimeException("Failed to initialize order creation saga");
            given(orderService.createOrder(any(CreateOrderRequest.class))).willThrow(serviceException);

            assertThatThrownBy(() -> orderController.createOrder(request))
                  .isSameAs(serviceException);

            then(orderService).should().createOrder(any(CreateOrderRequest.class));
        }
    }
}
package com.echcherqaoui.orderflow.entitlement.controller;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionResponse;
import com.echcherqaoui.orderflow.entitlement.service.LicenseKeyService;
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

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminLicenseControllerTest {

    @Mock
    private LicenseKeyService ingestionService;

    @InjectMocks
    private AdminLicenseController adminLicenseController;

    @Captor
    private ArgumentCaptor<BatchIngestionRequest> requestCaptor;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String validItemName = "prod-123";
    private final Long validPriceCents = 1999L;
    private final List<String> validKeys = List.of("KEY-01", "KEY-02");

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(adminLicenseController)
              .addPlaceholderValue("api.base-path", "/api/v1")
              .setValidator(validator)
              .build();
    }

    @Nested
    @DisplayName("ingestBatch()")
    class IngestBatch {
        private static Stream<Arguments> invalidRequestProvider() {
            return Stream.of(
                  Arguments.of("blank itemName", new BatchIngestionRequest("", 1999L, List.of("KEY-01"))),
                  Arguments.of("null itemName", new BatchIngestionRequest(null, 1999L, List.of("KEY-01"))),
                  Arguments.of("null priceCents", new BatchIngestionRequest("prod-123", null, List.of("KEY-01"))),
                  Arguments.of("empty keys list", new BatchIngestionRequest("prod-123", 1999L, List.of())),
                  Arguments.of("null keys list", new BatchIngestionRequest("prod-123", 1999L, null)),
                  Arguments.of("blank element inside keys list", new BatchIngestionRequest("prod-123", 1999L, List.of("KEY-01", "   ")))
            );
        }

        @Test
        @DisplayName("direct method invocation returns 201 Created with BatchIngestionResponse body from service")
        void ingestBatch_directInvocation_returns201CreatedWithResponse() {
            BatchIngestionRequest request = new BatchIngestionRequest(validItemName, validPriceCents, validKeys);
            BatchIngestionResponse expectedResponse = new BatchIngestionResponse("License keys ingested successfully", validItemName, 2);
            given(ingestionService.ingestKeys(any(BatchIngestionRequest.class))).willReturn(expectedResponse);

            ResponseEntity<BatchIngestionResponse> response = adminLicenseController.ingestBatch(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isSameAs(expectedResponse);

            then(ingestionService).should().ingestKeys(requestCaptor.capture());
            assertThat(requestCaptor.getValue()).isEqualTo(request);
        }

        @Test
        @DisplayName("valid HTTP payload passes validation, returning 201 Created and response body")
        void ingestBatch_validPayload_returns201CreatedAndBody() throws Exception {
            BatchIngestionRequest request = new BatchIngestionRequest(validItemName, validPriceCents, validKeys);
            BatchIngestionResponse expectedResponse = new BatchIngestionResponse("License keys ingested successfully", validItemName, 2);
            given(ingestionService.ingestKeys(any(BatchIngestionRequest.class))).willReturn(expectedResponse);

            mockMvc.perform(post("/api/v1/admin/licenses/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                  .andExpect(status().isCreated())
                  .andExpect(jsonPath("$.itemName").value(validItemName))
                  .andExpect(jsonPath("$.keysAdded").value(2));

            then(ingestionService).should().ingestKeys(requestCaptor.capture());
            BatchIngestionRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.itemName()).isEqualTo(validItemName);
            assertThat(capturedRequest.priceCents()).isEqualTo(validPriceCents);
            assertThat(capturedRequest.keys()).isEqualTo(validKeys);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRequestProvider")
        @DisplayName("invalid payload violates validation constraints and returns 400 Bad Request")
        void ingestBatch_invalidPayload_returns400BadRequest(String testName, BatchIngestionRequest invalidRequest) throws Exception {
            mockMvc.perform(
                  post("/api/v1/admin/licenses/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest))
            ).andExpect(status().isBadRequest());

            verifyNoInteractions(ingestionService);
        }

        @Test
        @DisplayName("service failure propagates exception during execution")
        void ingestBatch_serviceFails_propagatesException() {
            BatchIngestionRequest request = new BatchIngestionRequest(validItemName, validPriceCents, validKeys);
            RuntimeException serviceException = new RuntimeException("Duplicate keys detected");
            given(ingestionService.ingestKeys(any(BatchIngestionRequest.class))).willThrow(serviceException);

            assertThatThrownBy(() -> adminLicenseController.ingestBatch(request))
                  .isSameAs(serviceException);

            then(ingestionService).should().ingestKeys(any(BatchIngestionRequest.class));
        }
    }
}
package com.echcherqaoui.orderflow.entitlement.controller;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.repository.LicenseKeyRepository;
import com.echcherqaoui.orderflow.entitlement.support.WithPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminLicenseControllerIT implements WithPostgres {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LicenseKeyRepository licenseKeyRepository;

    @BeforeEach
    void setUp() {
        licenseKeyRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("valid request returns 201 Created with ingestion response")
    void ingestBatch_validRequest_returns201Created() throws Exception {
        String itemName = "prod-" + UUID.randomUUID();
        List<String> keys = List.of("KEY-01-" + UUID.randomUUID(), "KEY-02-" + UUID.randomUUID());
        BatchIngestionRequest request = new BatchIngestionRequest(itemName, 1999L, keys);

        mockMvc.perform(
              post("/api/v1/admin/licenses/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated())
        .andExpect(jsonPath("$.itemName").value(itemName))
        .andExpect(jsonPath("$.keysAdded").value(2));

        assertThat(licenseKeyRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("blank itemName fails DTO validation, returns 400 with validation errors")
    void ingestBatch_blankItemName_returns400WithValidationErrors() throws Exception {
        BatchIngestionRequest invalidRequest =
              new BatchIngestionRequest("", 1999L, List.of("KEY-01"));

        mockMvc.perform(
              post("/api/v1/admin/licenses/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest))
        ).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").isNotEmpty())
        .andExpect(jsonPath("$.validationErrors").isNotEmpty());
    }

    @Test
    @DisplayName("duplicate keys within payload returns 409 Conflict")
    void ingestBatch_duplicateKeysInPayload_returns409() throws Exception {
        String itemName = "prod-" + UUID.randomUUID();
        String duplicateKey = "KEY-DUP-" + UUID.randomUUID();
        List<String> keys = List.of(duplicateKey, "KEY-UNIQUE-" + UUID.randomUUID(), duplicateKey);

        BatchIngestionRequest request = new BatchIngestionRequest(itemName, 1999L, keys);

        mockMvc.perform(
              post("/api/v1/admin/licenses/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DB_409"));
    }

    @Test
    @DisplayName("existing keys in database returns 409 Conflict")
    void ingestBatch_existingKeysInDatabase_returns409() throws Exception {
        String itemName = "prod-" + UUID.randomUUID();
        String existingKey = "KEY-EXISTING-" + UUID.randomUUID();
        List<String> initialKeys = List.of(existingKey);

        // Seed first batch
        BatchIngestionRequest initialRequest = new BatchIngestionRequest(itemName, 1999L, initialKeys);
        mockMvc.perform(
              post("/api/v1/admin/licenses/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(initialRequest))
        ).andExpect(status().isCreated());

        // Attempt second batch containing the existing key
        List<String> conflictingKeys = List.of(existingKey, "KEY-NEW-" + UUID.randomUUID());
        BatchIngestionRequest conflictingRequest = new BatchIngestionRequest(itemName, 1999L, conflictingKeys);

        mockMvc.perform(
              post("/api/v1/admin/licenses/batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(conflictingRequest))
        ).andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DB_409"));
    }
}
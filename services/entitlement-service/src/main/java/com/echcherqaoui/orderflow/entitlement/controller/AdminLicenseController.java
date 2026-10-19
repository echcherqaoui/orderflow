package com.echcherqaoui.orderflow.entitlement.controller;

import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionRequest;
import com.echcherqaoui.orderflow.entitlement.dto.BatchIngestionResponse;
import com.echcherqaoui.orderflow.entitlement.service.LicenseKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.base-path}/admin/licenses")
@RequiredArgsConstructor
public class AdminLicenseController {

    private final LicenseKeyService ingestionService;

    @PostMapping("/batch")
    public ResponseEntity<BatchIngestionResponse> ingestBatch(@Valid @RequestBody BatchIngestionRequest request) {
        return ResponseEntity
              .status(HttpStatus.CREATED)
              .body(ingestionService.ingestKeys(request));
    }
}
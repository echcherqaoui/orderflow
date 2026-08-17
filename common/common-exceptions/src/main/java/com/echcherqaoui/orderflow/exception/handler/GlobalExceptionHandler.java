package com.echcherqaoui.orderflow.exception.handler;

import com.echcherqaoui.orderflow.exception.core.BaseCustomException;
import com.echcherqaoui.orderflow.exception.core.IErrorCode;
import com.echcherqaoui.orderflow.exception.response.ErrorResponse;
import com.echcherqaoui.orderflow.exception.util.ExceptionUtils;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.Set;
import java.util.stream.Collectors;

import static com.echcherqaoui.orderflow.exception.core.CommonErrorCode.VALIDATION_FAILED;

@RestControllerAdvice
@Slf4j
@Order
public class GlobalExceptionHandler {

    // ─── Shared validation response builder ───────────────────────
    @NonNull
    private ResponseEntity<ErrorResponse> buildValidationErrorResponse(Set<String> errors,
                                                                       WebRequest request) {
        ErrorResponse response = new ErrorResponse(
              VALIDATION_FAILED.getCode(),
              VALIDATION_FAILED.getMessage(),
              ExceptionUtils.sanitizePath(request)
        ).withValidationErrors(errors);

        return ResponseEntity.badRequest().body(response);
    }

    // ─── Known business exceptions ────────────────────────────────
    // Catches all service-specific exceptions
    @ExceptionHandler(BaseCustomException.class)
    public ResponseEntity<ErrorResponse> handleBaseCustomException(@NonNull BaseCustomException ex,
                                                                   WebRequest request) {

        IErrorCode errorCode = ex.getErrorCode();

        log.warn("Business exception [{}]: {}", errorCode.getCode(), ex.getMessage());

        ErrorResponse response = new ErrorResponse(
              errorCode.getCode(),
              ex.getMessage(),
              ExceptionUtils.sanitizePath(request)
        );

        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }

    // ─── @Valid on @RequestBody ────────────────────────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleDtoValidation(@NonNull MethodArgumentNotValidException ex,
                                                             @NonNull WebRequest request) {

        Set<String> errors = ex.getBindingResult()
              .getFieldErrors()
              .stream()
              .map(e -> e.getField() + ": " + e.getDefaultMessage())
              .collect(Collectors.toSet());

        log.warn("DTO validation failed [{}]: {}", request.getDescription(false), errors);

        return buildValidationErrorResponse(errors, request);
    }

    // ─── @Validated on @PathVariable / @RequestParam ──────────────
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(@NonNull ConstraintViolationException ex,
                                                               @NonNull WebRequest request) {

        Set<String> errors = ex.getConstraintViolations()
              .stream()
              .map(v -> v.getPropertyPath() + ": " + v.getMessage())
              .collect(Collectors.toSet());

        log.warn("Param validation failed [{}]: {}", request.getDescription(false), errors);

        return buildValidationErrorResponse(errors, request);
    }
}
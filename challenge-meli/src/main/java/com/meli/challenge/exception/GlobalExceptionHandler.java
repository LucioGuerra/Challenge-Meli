package com.meli.challenge.exception;

import com.meli.challenge.dto.error.ApiError;
import com.meli.challenge.dto.error.ApiErrorResponse;
import com.meli.challenge.dto.error.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        log.warn("[{}] {} {} - {} {}",
            ex.getErrorCode(), request.getMethod(), request.getRequestURI(),
            ex.getStatus().value(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
            .body(new ApiErrorResponse(buildError(ex.getErrorCode(), ex.getMessage(), ex.getStatus().value(), null, request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
            .toList();
        return ResponseEntity.badRequest()
            .body(new ApiErrorResponse(buildError("VALIDATION_ERROR", "Validation failed", 400, fieldErrors, request)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getConstraintViolations().stream()
            .map(v -> new FieldError(
                lastSegmentOf(v.getPropertyPath().toString()),
                v.getMessage(),
                v.getInvalidValue()
            ))
            .toList();
        return ResponseEntity.badRequest()
            .body(new ApiErrorResponse(buildError("VALIDATION_ERROR", "Validation failed", 400, fieldErrors, request)));
    }

    private static String lastSegmentOf(String propertyPath) {
        int idx = propertyPath.lastIndexOf('.');
        return idx >= 0 ? propertyPath.substring(idx + 1) : propertyPath;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String type = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
        String message = "Parameter '" + ex.getName() + "' expects type " + type;
        return ResponseEntity.badRequest()
            .body(new ApiErrorResponse(buildError("INVALID_PARAMETER", message, 400, null, request)));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream()
                .map(err -> new FieldError(
                    result.getMethodParameter().getParameterName(),
                    err.getDefaultMessage(),
                    result.getArgument()
                )))
            .toList();
        return ResponseEntity.badRequest()
            .body(new ApiErrorResponse(buildError("VALIDATION_ERROR", "Validation failed", 400, fieldErrors, request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRouteNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ApiErrorResponse(buildError("ROUTE_NOT_FOUND", "The requested route does not exist", 404, null, request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiErrorResponse(buildError("INTERNAL_ERROR", "An unexpected error occurred", 500, null, request)));
    }

    private ApiError buildError(String code, String message, int status,
                                 List<FieldError> errors, HttpServletRequest request) {
        return new ApiError(
            code,
            message,
            status,
            Instant.now().toString(),
            request.getRequestURI(),
            MDC.get("correlationId"),
            errors
        );
    }
}

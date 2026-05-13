package com.meli.challenge.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String code,
    String message,
    int status,
    String timestamp,
    String path,
    String correlationId,
    List<FieldError> errors
) {}

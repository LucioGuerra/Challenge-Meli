package com.meli.challenge.dto.error;

public record FieldError(
    String field,
    String message,
    Object rejectedValue
) {}

package com.meli.challenge.dto;

public record PageInfo(
    int page,
    int size,
    int totalPages,
    long totalElements
) {}

package com.meli.challenge.model;

public record Slo(
    String serviceId,
    double sloTarget,
    double currentValue,
    SloStatus status,
    double errorBudgetRemaining,
    String period
) {}

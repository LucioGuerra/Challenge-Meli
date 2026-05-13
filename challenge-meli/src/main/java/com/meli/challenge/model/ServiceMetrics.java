package com.meli.challenge.model;

public record ServiceMetrics(
    String serviceId,
    String window,
    Latency latency,
    RequestRate requestRate,
    ErrorRate errorRate,
    double availability
) {
    public record Latency(int p50, int p95, int p99, String unit) {}

    public record RequestRate(int total, double perSecond) {}

    public record ErrorRate(int total, double rate4xx, double rate5xx, String unit) {}
}

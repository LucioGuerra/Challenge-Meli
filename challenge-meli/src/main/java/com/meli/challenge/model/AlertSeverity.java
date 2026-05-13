package com.meli.challenge.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AlertSeverity {
    CRITICAL, HIGH, MEDIUM, LOW;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}

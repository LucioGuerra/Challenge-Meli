package com.meli.challenge.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AlertStatus {
    FIRING, ACKNOWLEDGED, RESOLVED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}

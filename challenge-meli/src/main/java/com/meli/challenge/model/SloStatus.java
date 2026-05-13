package com.meli.challenge.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SloStatus {
    MET, AT_RISK, BREACHED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}

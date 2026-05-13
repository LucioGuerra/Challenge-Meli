package com.meli.challenge.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DeployStatus {
    SUCCESS, FAILED, IN_PROGRESS, ROLLED_BACK;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}

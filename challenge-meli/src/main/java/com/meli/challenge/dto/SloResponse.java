package com.meli.challenge.dto;

import com.meli.challenge.model.Slo;
import com.meli.challenge.model.SloStatus;

public record SloResponse(
    String serviceId,
    double sloTarget,
    double currentValue,
    SloStatus status,
    double errorBudgetRemaining,
    String period
) {
    public static SloResponse from(Slo s) {
        return new SloResponse(
            s.serviceId(), s.sloTarget(), s.currentValue(),
            s.status(), s.errorBudgetRemaining(), s.period()
        );
    }
}

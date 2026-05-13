package com.meli.challenge.service;

import com.meli.challenge.client.SloClient;
import com.meli.challenge.model.Slo;
import com.meli.challenge.model.SloStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class SlosService {

    private final SloClient sloClient;

    public SlosService(SloClient sloClient) {
        this.sloClient = sloClient;
    }

    public List<Slo> findAll(SloStatus status, String serviceId) {
        return sloClient.findAll().stream()
            .filter(s -> status == null || s.status() == status)
            .filter(s -> serviceId == null || s.serviceId().equals(serviceId))
            .sorted(Comparator.comparingDouble(Slo::errorBudgetRemaining))
            .toList();
    }
}

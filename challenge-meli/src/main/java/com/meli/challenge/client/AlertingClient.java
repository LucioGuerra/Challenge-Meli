package com.meli.challenge.client;

import com.meli.challenge.model.Alert;
import com.meli.challenge.model.AlertSeverity;
import com.meli.challenge.model.AlertStatus;

import java.util.List;
import java.util.Map;

public interface AlertingClient {

    List<Alert> findAll();

    Map<String, Object> ping();
}

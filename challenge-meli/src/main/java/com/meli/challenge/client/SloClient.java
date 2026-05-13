package com.meli.challenge.client;

import com.meli.challenge.model.Slo;
import com.meli.challenge.model.SloStatus;

import java.util.List;
import java.util.Map;

public interface SloClient {

    List<Slo> findAll();

    Map<String, Object> ping();
}

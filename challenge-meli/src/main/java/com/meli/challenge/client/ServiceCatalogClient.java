package com.meli.challenge.client;

import com.meli.challenge.model.Service;
import com.meli.challenge.model.ServiceStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ServiceCatalogClient {

    List<Service> findAll();

    Optional<Service> findById(String id);

    Map<String, Object> ping();
}

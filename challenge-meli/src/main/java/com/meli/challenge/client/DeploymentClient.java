package com.meli.challenge.client;

import com.meli.challenge.model.Deploy;
import com.meli.challenge.model.DeployStatus;

import java.util.List;
import java.util.Map;

public interface DeploymentClient {

    List<Deploy> findAll();

    Map<String, Object> ping();
}

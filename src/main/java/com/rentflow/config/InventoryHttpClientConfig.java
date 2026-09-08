package com.rentflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

import com.rentflow.service.rest.InventoryHttpClient;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "inventory", types = InventoryHttpClient.class)
public class InventoryHttpClientConfig {}

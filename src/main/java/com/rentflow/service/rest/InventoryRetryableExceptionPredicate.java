package com.rentflow.service.rest;

import java.util.function.Predicate;

import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

public class InventoryRetryableExceptionPredicate implements Predicate<Throwable> {
    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof ResourceAccessException) {
            return true;
        }
        if (throwable instanceof HttpServerErrorException exception) {
            int status = exception.getStatusCode().value();
            return status == 502 || status == 503 || status == 504;
        }
        return false;
    }
}

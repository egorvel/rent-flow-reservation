package com.rentflow.model;

/** A database-time snapshot of one operational backlog. */
public interface ReservationBacklog {
    double getBacklog();

    double getOldestAge();

    double getMeasuredAt();
}

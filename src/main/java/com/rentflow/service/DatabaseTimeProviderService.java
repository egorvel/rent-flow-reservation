package com.rentflow.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Service;

@Service
public class DatabaseTimeProviderService implements DatabaseTimeProvider {
    private static final String TIME_QUERY = """
            WITH time_sample AS MATERIALIZED (
                SELECT clock_timestamp() AS observed_at
            )
            SELECT observed_at,
                   (observed_at AT TIME ZONE 'UTC')::date AS utc_date
            FROM time_sample
            """;
    private final EntityManager entityManager;

    public DatabaseTimeProviderService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public DatabaseTimeSnapshot now() {
        Object[] time = (Object[]) entityManager.createNativeQuery(TIME_QUERY).getSingleResult();
        return new DatabaseTimeSnapshot(toInstant(time[0]), (LocalDate) time[1]);
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        return ((Timestamp) value).toInstant();
    }
}

package com.rentflow.service;

import java.time.Instant;
import java.time.LocalDate;

public record DatabaseTimeSnapshot(Instant observedAt, LocalDate utcDate) {}

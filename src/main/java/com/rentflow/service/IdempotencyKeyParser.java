package com.rentflow.service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class IdempotencyKeyParser {
    public static final String UUID_V4_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}";
    private static final Pattern UUID_V4 = Pattern.compile(UUID_V4_PATTERN);

    private IdempotencyKeyParser() {}

    public static UUID parse(List<String> values) {
        if (values.size() != 1 || !UUID_V4.matcher(values.getFirst()).matches()) {
            throw new IllegalArgumentException("must contain exactly one canonical UUID v4 value");
        }
        return UUID.fromString(values.getFirst());
    }
}

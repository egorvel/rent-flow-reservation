package com.rentflow.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import com.rentflow.model.ReservationCreationCommand;

public final class IdempotencyFingerprint {
    private static final String VERSION = "reservation-creation-v1";

    private IdempotencyFingerprint() {}

    public static String of(ReservationCreationCommand command) {
        MessageDigest digest = sha256();
        add(digest, VERSION);
        add(digest, command.customerId());
        add(digest, command.orderId());
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(command.items().size())
                .array());
        for (ReservationCreationCommand.Item item : command.items()) {
            add(digest, item.serialNumber());
            add(digest, item.startDate().toString());
            add(digest, item.endDate().toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static long lockId(UUID key) {
        MessageDigest digest = sha256();
        add(digest, "reservation-creation:key:v1");
        add(digest, key.toString());
        return ByteBuffer.wrap(digest.digest()).getLong();
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

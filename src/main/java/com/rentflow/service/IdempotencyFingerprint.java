package com.rentflow.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import com.rentflow.model.ReservationCreationCommand;

public final class IdempotencyFingerprint {
    private static final String VERSION = "reservation-creation-v2";

    private IdempotencyFingerprint() {}

    public static String of(ReservationCreationCommand command) {
        MessageDigest digest = sha256();
        add(digest, VERSION);
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

    public static String callerScope(String issuer, String subject) {
        return "p1:" + HexFormat.of().formatHex(tuple("reservation-creation:principal:v1", issuer, subject));
    }

    public static long lockId(String callerScope, UUID key) {
        return ByteBuffer.wrap(tuple("reservation-creation:key:v2", callerScope, key.toString()))
                .getLong();
    }

    public static UUID inventoryKey(String callerScope, UUID key) {
        byte[] bytes = tuple("reservation-creation:inventory-key:v1", callerScope, key.toString());
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] tuple(String... values) {
        MessageDigest digest = sha256();
        for (String value : values) {
            add(digest, value);
        }
        return digest.digest();
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

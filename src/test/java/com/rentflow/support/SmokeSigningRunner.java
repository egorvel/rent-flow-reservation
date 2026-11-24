package com.rentflow.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Isolated host-side signing/token fixture, excluded from the application image. */
public final class SmokeSigningRunner {
    private SmokeSigningRunner() {}

    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]);
        try (SignedJwtFixture fixture = new SignedJwtFixture("0.0.0.0", "host.docker.internal")) {
            write(directory.resolve("client-secret"), UUID.randomUUID().toString());
            write(
                    directory.resolve("environment"),
                    "RESERVATION_ISSUER_URI=" + fixture.issuer() + "\nRESERVATION_JWKS_URI=" + fixture.jwks()
                            + "\nRESERVATION_TOKEN_URI=" + fixture.tokenUri() + "\n");
            boolean ready = false;
            while (true) {
                // Cold Docker builds can exceed an access-token lifetime. Renew private test
                // credentials atomically without extending the shared 300-second token profile.
                replace(
                        directory.resolve("customer.curl"),
                        credential(fixture.token("smoke-a", "reservation-swagger", SignedJwtFixture.CUSTOMER)));
                replace(
                        directory.resolve("customer-b.curl"),
                        credential(fixture.token("smoke-b", "reservation-swagger", SignedJwtFixture.CUSTOMER)));
                replace(directory.resolve("staff.curl"), credential(fixture.staff()));
                if (!ready) {
                    write(directory.resolve("ready"), "ready\n");
                    ready = true;
                }
                Thread.sleep(60000);
            }
        }
    }

    private static String credential(String token) {
        return "header = \"Authorization: Bearer " + token + "\"\n";
    }

    private static void write(Path path, String content) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, content);
    }

    private static void replace(Path path, String content) throws Exception {
        Path temporary = Files.createTempFile(
                path.getParent(),
                "credential-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temporary, content);
        Files.move(
                temporary,
                path,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}

package com.sahaj.platform.devtools.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.application.admin.AdminTokenHasher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** ADR-018 D5: new 256-bit values, and the keyed hash that is all the database ever stores of them. */
class BootstrapTicketHasherTest {

    private static final String SECRET = "a-throwaway-admin-secret-for-tests-only-123";

    private static byte[] hmac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    @Test
    void aHashIsHmacSha256OfTheValueUnderTheLabelledDerivedKey() throws Exception {
        byte[] key = hmac(SECRET.getBytes(StandardCharsets.UTF_8),
                "sahaj-admin-bootstrap-ticket-v1".getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(hmac(key, "some-value".getBytes(StandardCharsets.UTF_8)));

        assertThat(new BootstrapTicketHasher(SECRET).hash("some-value")).isEqualTo(expected).matches("[0-9a-f]{64}");
    }

    @Test
    void theHashDependsOnTheSecretAndTheValue() {
        BootstrapTicketHasher hasher = new BootstrapTicketHasher(SECRET);

        assertThat(hasher.hash("a")).isEqualTo(hasher.hash("a")).isNotEqualTo(hasher.hash("b"));
        assertThat(new BootstrapTicketHasher(SECRET + "x").hash("a")).isNotEqualTo(hasher.hash("a"));
    }

    @Test
    void aTicketHashCanNeverEqualAnAdminTokenHash() {
        String value = "Xq3vTn8bKp0aLmZr5YwC2dEfGhJ7sUiOq9RtVbNcA1k";
        BootstrapTicketHasher ticket = new BootstrapTicketHasher(SECRET);
        AdminTokenHasher tokens = new AdminTokenHasher(SECRET);

        assertThat(ticket.hash(value)).isNotEqualTo(tokens.preAuth(value)).isNotEqualTo(tokens.refresh(value))
                .isNotEqualTo(tokens.recovery(value)).isNotEqualTo(tokens.emailKey(value));
    }

    @Test
    void newValuesAreFortyThreeBase64urlCharactersAndDoNotRepeat() {
        SecureRandom random = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String value = BootstrapTicketHasher.newValue(random);
            assertThat(value).matches("[A-Za-z0-9_-]{43}");
            assertThat(BootstrapTicketHasher.wellShaped(value)).isTrue();
            seen.add(value);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void onlyTheContractShapeIsWellShaped() {
        assertThat(BootstrapTicketHasher.wellShaped("A".repeat(43))).isTrue();
        assertThat(BootstrapTicketHasher.wellShaped("A".repeat(42))).isFalse();
        assertThat(BootstrapTicketHasher.wellShaped("A".repeat(44))).isFalse();
        assertThat(BootstrapTicketHasher.wellShaped("A".repeat(42) + "+")).isFalse();
        assertThat(BootstrapTicketHasher.wellShaped("A".repeat(42) + "=")).isFalse();
        assertThat(BootstrapTicketHasher.wellShaped("A".repeat(42) + "\n")).isFalse();
        assertThat(BootstrapTicketHasher.wellShaped(" ".repeat(43))).isFalse();
        assertThat(BootstrapTicketHasher.wellShaped("")).isFalse();
        assertThat(BootstrapTicketHasher.wellShaped(null)).isFalse();
    }

    @Test
    void toStringPrintsNoKey() {
        assertThat(new BootstrapTicketHasher(SECRET).toString()).isEqualTo("BootstrapTicketHasher[]");
    }
}

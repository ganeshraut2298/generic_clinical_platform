package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminTokenHasherTest {

    private static final String SECRET = "an-admin-secret-with-more-than-32-chars";

    private final AdminTokenHasher hasher = new AdminTokenHasher(SECRET);

    @Test
    void producesSixtyFourLowerCaseHexCharacters() {
        assertThat(hasher.preAuth("token")).matches("^[0-9a-f]{64}$");
        assertThat(hasher.refresh("token")).matches("^[0-9a-f]{64}$");
        assertThat(hasher.recovery("ABCDEFGHABCDEFGH")).matches("^[0-9a-f]{64}$");
        assertThat(hasher.emailKey("a@b.co")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void isDeterministicForTheSameKey() {
        assertThat(hasher.refresh("token")).isEqualTo(new AdminTokenHasher(SECRET).refresh("token"));
    }

    @Test
    void aDifferentSecretGivesADifferentHash() {
        assertThat(hasher.refresh("token")).isNotEqualTo(new AdminTokenHasher(SECRET + "x").refresh("token"));
    }

    @Test
    void theSameValueHashesDifferentlyForEveryPurpose() {
        String value = "same-value";
        assertThat(hasher.preAuth(value)).isNotEqualTo(hasher.refresh(value));
        assertThat(hasher.refresh(value)).isNotEqualTo(hasher.recovery(value));
        assertThat(hasher.recovery(value)).isNotEqualTo(hasher.emailKey(value));
        assertThat(hasher.emailKey(value)).isNotEqualTo(hasher.preAuth(value));
    }

    @Test
    void isNotAPlainSha256OfTheValue() throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        String plain = java.util.HexFormat.of().formatHex(
                digest.digest("token".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(hasher.refresh("token")).isNotEqualTo(plain);
    }
}

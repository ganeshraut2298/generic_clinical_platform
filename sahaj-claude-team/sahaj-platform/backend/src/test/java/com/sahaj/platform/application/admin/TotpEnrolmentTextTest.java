package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/** The enrolment texts built once for enrol and for the dev-only sample admin (ADR-008 D5). */
class TotpEnrolmentTextTest {

    private final byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void buildsTheGroupedKeyAndTheOtpauthUri() {
        TotpEnrolmentText text = TotpEnrolmentText.of("sample-admin@example.test", secret);

        String base32 = Base32.encode(secret);
        assertThat(text.secretKey()).isEqualTo(Base32.inGroupsOfFour(base32));
        assertThat(text.secretKey()).matches("[A-Z2-7]{4}( [A-Z2-7]{4}){7}");
        assertThat(text.otpauthUri()).isEqualTo("otpauth://totp/Sahaj%20admin:sample-admin%40example.test?secret="
                + base32 + "&issuer=Sahaj%20admin&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    void toStringPrintsNoValue() {
        assertThat(TotpEnrolmentText.of("a@example.test", secret).toString()).isEqualTo("TotpEnrolmentText[]");
    }

    @Test
    void newSecretsAre20RandomBytes() {
        SecureRandom random = new SecureRandom();
        byte[] first = TotpSecrets.newSecret(random);
        byte[] second = TotpSecrets.newSecret(random);
        assertThat(first).hasSize(20);
        assertThat(second).hasSize(20).isNotEqualTo(first);
        assertThat(TotpSecrets.SECRET_BYTES).isEqualTo(20);
    }
}

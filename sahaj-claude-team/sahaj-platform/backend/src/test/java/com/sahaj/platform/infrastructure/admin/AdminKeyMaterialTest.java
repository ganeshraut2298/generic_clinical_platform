package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AdminKeyMaterialTest {

    private static final String CLINIC = "clinic-secret-clinic-secret-clinic-secret";
    private static final String ADMIN = "admin-secret-admin-secret-admin-secret-1";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static AdminProperties props(String jwt, String key) {
        return new AdminProperties(true, jwt, key, "", new AdminProperties.Totp(false),
                new AdminProperties.Seed("", "", "Dev admin"));
    }

    @Test
    void acceptsGoodKeys() {
        AdminKeyMaterial material = AdminKeyMaterial.validate(props(ADMIN, KEY), CLINIC);
        assertThat(material.jwtSecret()).isEqualTo(ADMIN);
        assertThat(material.totpKey()).hasSize(32);
    }

    @Test
    void refusesAShortSigningSecret() {
        assertThatThrownBy(() -> AdminKeyMaterial.validate(props("short", KEY), CLINIC))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SAHAJ_ADMIN_JWT_SECRET");
    }

    @Test
    void refusesTheClinicSecret() {
        assertThatThrownBy(() -> AdminKeyMaterial.validate(props(CLINIC, KEY), CLINIC))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must differ");
    }

    @Test
    void refusesAKeyThatIsNotBase64OrNotThirtyTwoBytes() {
        assertThatThrownBy(() -> AdminKeyMaterial.validate(props(ADMIN, "***not base64***"), CLINIC))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SAHAJ_ADMIN_TOTP_KEY");
        assertThatThrownBy(() -> AdminKeyMaterial.validate(
                props(ADMIN, Base64.getEncoder().encodeToString(new byte[16])), CLINIC))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    @Test
    void refusesAKeyEqualToEitherSecret() {
        String thirtyTwo = "0123456789abcdef0123456789abcdef";
        String asKey = Base64.getEncoder().encodeToString(thirtyTwo.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> AdminKeyMaterial.validate(props(thirtyTwo, asKey), CLINIC))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AdminKeyMaterial.validate(props(ADMIN, asKey), thirtyTwo))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> AdminKeyMaterial.validate(props(ADMIN, asKey), CLINIC)).doesNotThrowAnyException();
    }

    @Test
    void errorsNeverPrintTheValues() {
        assertThatThrownBy(() -> AdminKeyMaterial.validate(props(CLINIC, KEY), CLINIC))
                .hasMessageNotContaining(CLINIC);
        assertThatThrownBy(() -> AdminKeyMaterial.validate(props(ADMIN, "bad-key-value!"), CLINIC))
                .hasMessageNotContaining("bad-key-value");
    }

    @Test
    void toStringPrintsNothingSecret() {
        assertThat(props(ADMIN, KEY).toString()).doesNotContain(ADMIN).doesNotContain(KEY);
        assertThat(AdminKeyMaterial.validate(props(ADMIN, KEY), CLINIC).toString()).doesNotContain(ADMIN);
    }
}

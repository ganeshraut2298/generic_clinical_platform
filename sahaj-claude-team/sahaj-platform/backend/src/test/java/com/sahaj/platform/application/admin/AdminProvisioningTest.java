package com.sahaj.platform.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** ADR-008 D5: the checks and the hashing shared by the env seed and the dev-only sample admin. */
class AdminProvisioningTest {

    private final AdminProvisioning provisioning = new AdminProvisioning(new AdminPasswordHasher(new SecureRandom()));

    private AdminProvisioning.Reason refusal(String email, String name, String password) {
        try {
            provisioning.prepare(email, name, password);
        } catch (AdminProvisioning.RefusedException ex) {
            return ex.reason();
        }
        return null;
    }

    @Test
    void trimsAndLowerCasesTheEmailAndHashesThePassword() {
        AdminProvisioning.PreparedAdmin prepared =
                provisioning.prepare("  Sample.Admin@Example.test ", " Sample admin ", "a-strong-password-1");

        assertThat(prepared.email()).isEqualTo("sample.admin@example.test");
        assertThat(prepared.name()).isEqualTo("Sample admin");
        assertThat(prepared.passwordHash()).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches("a-strong-password-1", prepared.passwordHash())).isTrue();
    }

    @Test
    void refusesABadEmail() {
        assertThat(refusal("not-an-email", "x", "a-strong-password-1")).isEqualTo(AdminProvisioning.Reason.EMAIL_INVALID);
        assertThat(refusal(null, "x", "a-strong-password-1")).isEqualTo(AdminProvisioning.Reason.EMAIL_INVALID);
        assertThat(refusal("a$sq$b@example.test", "x", "a-strong-password-1"))
                .isEqualTo(AdminProvisioning.Reason.EMAIL_INVALID);
        assertThat(refusal("a".repeat(250) + "@example.test", "x", "a-strong-password-1"))
                .isEqualTo(AdminProvisioning.Reason.EMAIL_INVALID);
    }

    @Test
    void refusesABadName() {
        assertThat(refusal("dev@example.test", "bad $sq$ name", "a-strong-password-1"))
                .isEqualTo(AdminProvisioning.Reason.NAME_INVALID);
        assertThat(refusal("dev@example.test", "n".repeat(121), "a-strong-password-1"))
                .isEqualTo(AdminProvisioning.Reason.NAME_INVALID);
    }

    @Test
    void refusesAPasswordThatFailsThePolicyWithoutNamingIt() {
        assertThat(refusal("dev.admin@example.test", "x", "short")).isEqualTo(AdminProvisioning.Reason.PASSWORD_POLICY);
        assertThat(refusal("dev.admin@example.test", "x", "contains-dev.admin-inside"))
                .isEqualTo(AdminProvisioning.Reason.PASSWORD_POLICY);
        assertThatThrownBy(() -> provisioning.prepare("dev.admin@example.test", "x", "short"))
                .isInstanceOf(AdminProvisioning.RefusedException.class)
                .hasMessageNotContaining("short")
                .hasMessageNotContaining("dev.admin");
    }

    @Test
    void thePreparedValuesPrintNothing() {
        AdminProvisioning.PreparedAdmin prepared =
                provisioning.prepare("dev@example.test", "Dev", "a-strong-password-1");
        assertThat(prepared.toString()).isEqualTo("PreparedAdmin[]");
    }
}

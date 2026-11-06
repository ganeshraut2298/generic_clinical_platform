package com.sahaj.platform.devtools.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.Base32;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** ADR-008 D5 and D7 with the database replaced by a fake: what is stored, what is returned, what is logged. */
class DevSampleAdminServiceTest {

    private static final String ADMIN_EMAIL = "sample-admin@example.test";

    private final TotpSecretCipher cipher = new TotpSecretCipher(new byte[32], new SecureRandom());
    private final AdminPasswordHasher hasher = new AdminPasswordHasher(new SecureRandom());
    private final FakeRepository repository = new FakeRepository();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private Level rootLevel;

    @BeforeEach
    void captureLogs() {
        rootLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        root.setLevel(rootLevel);
    }

    private DevSampleAdminService service(boolean bypass) {
        return new DevSampleAdminService(repository, hasher, cipher, new SecureRandom(), bypass);
    }

    /** Remembers the call and answers like the database function. */
    private static final class FakeRepository implements DevSampleAdminRepository {
        boolean answer = true;
        RuntimeException failure;
        int calls;
        UUID adminId;
        String hash;
        byte[] sealed;
        int keyVersion;
        String address;
        String requestId;

        @Override
        public boolean createSampleAdmin(UUID id, String passwordHash, byte[] totpSecretEnc, int version,
                                         String remoteAddress, String request) {
            calls++;
            adminId = id;
            hash = passwordHash;
            sealed = totpSecretEnc;
            keyVersion = version;
            address = remoteAddress;
            requestId = request;
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    @Test
    void createsTheContractResponseAndStoresOnlyAHashAndACiphertext() {
        DevSampleAdminResponse response = service(true).create("127.0.0.1", "req-1");

        assertThat(response).isNotNull();
        assertThat(response.adminId()).isEqualTo(repository.adminId);
        assertThat(response.email()).isEqualTo(ADMIN_EMAIL);
        assertThat(response.accountName()).isEqualTo(ADMIN_EMAIL);
        assertThat(response.fullName()).isEqualTo("Sample admin");
        assertThat(response.password()).matches("^[A-HJ-NP-Za-km-z2-9]{24}$");
        assertThat(response.secretKey()).matches("^[A-Z2-7]{4}( [A-Z2-7]{4}){7}$");
        assertThat(response.issuer()).isEqualTo("Sahaj admin");
        assertThat(response.digits()).isEqualTo(6);
        assertThat(response.periodSeconds()).isEqualTo(30);
        assertThat(response.algorithm()).isEqualTo("SHA1");
        assertThat(response.totpEnrolled()).isTrue();
        assertThat(response.totpDevBypassOn()).isTrue();
        assertThat(response.signInPath()).isEqualTo("/admin/sign-in");
        assertThat(response.otpauthUri()).isEqualTo("otpauth://totp/Sahaj%20admin:sample-admin%40example.test?secret="
                + response.secretKey().replace(" ", "") + "&issuer=Sahaj%20admin&algorithm=SHA1&digits=6&period=30");
        assertThat(response.otpauthUri().length()).isLessThanOrEqualTo(400);
    }

    @Test
    void theBypassFlagIsReportedAsConfigured() {
        assertThat(service(false).create("127.0.0.1", null).totpDevBypassOn()).isFalse();
    }

    @Test
    void theStoredHashIsBcrypt12OfTheReturnedPassword() {
        DevSampleAdminResponse response = service(true).create("127.0.0.1", "req-1");

        assertThat(repository.hash).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches(response.password(), repository.hash)).isTrue();
        assertThat(repository.hash).doesNotContain(response.password());
    }

    @Test
    void theStoredCiphertextIsBoundToTheAdminIdAndDecryptsToTheReturnedSecret() {
        DevSampleAdminResponse response = service(true).create("127.0.0.1", "req-1");

        assertThat(repository.keyVersion).isEqualTo(TotpSecretCipher.CURRENT_KEY_VERSION);
        assertThat(repository.sealed).hasSize(48);
        byte[] secret = cipher.decrypt(repository.sealed, repository.adminId, repository.keyVersion);
        assertThat(secret).hasSize(20);
        assertThat(Base32.encode(secret)).isEqualTo(response.secretKey().replace(" ", ""));
        assertThatThrownBy(() -> cipher.decrypt(repository.sealed, UUID.randomUUID(), repository.keyVersion))
                .isInstanceOf(TotpSecretCipher.TotpSecretUnreadableException.class);
    }

    @Test
    void passesTheAddressAndRequestIdOnForTheAuditRow() {
        service(true).create("::1", "req-42");

        assertThat(repository.address).isEqualTo("::1");
        assertThat(repository.requestId).isEqualTo("req-42");
    }

    @Test
    void everyCallGetsANewIdPasswordAndSecret() {
        DevSampleAdminResponse first = service(true).create("127.0.0.1", null);
        DevSampleAdminResponse second = service(true).create("127.0.0.1", null);

        assertThat(second.adminId()).isNotEqualTo(first.adminId());
        assertThat(second.password()).isNotEqualTo(first.password());
        assertThat(second.secretKey()).isNotEqualTo(first.secretKey());
    }

    @Test
    void whenAnAdminAlreadyExistsNothingIsReturned() {
        repository.answer = false;

        assertThat(service(true).create("127.0.0.1", "req-1")).isNull();
        assertThat(repository.calls).isEqualTo(1);
    }

    @Test
    void aDatabaseFailureIsRethrownUnchangedForTheTransactionToRollBack() {
        repository.failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> service(true).create("127.0.0.1", "req-1")).isSameAs(repository.failure);
    }

    @Test
    void theResponseAndItsToStringPrintNoSecret() {
        DevSampleAdminResponse response = service(true).create("127.0.0.1", "req-1");

        assertThat(response.toString()).isEqualTo("DevSampleAdminResponse[]");
    }

    @Test
    void neverLogsThePasswordTheSecretTheUriOrTheEmail() {
        DevSampleAdminResponse created = service(true).create("127.0.0.1", "req-1");
        repository.answer = false;
        service(true).create("127.0.0.1", "req-2");

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            lines.add(event.getFormattedMessage());
            lines.add(String.valueOf(event.getThrowableProxy()));
        }
        String all = String.join("\n", lines);
        assertThat(all).contains(created.adminId().toString());
        assertThat(all).doesNotContain(created.password())
                .doesNotContain(created.secretKey())
                .doesNotContain(created.secretKey().replace(" ", ""))
                .doesNotContain(created.otpauthUri())
                .doesNotContain("otpauth")
                .doesNotContain("secret=")
                .doesNotContain(ADMIN_EMAIL)
                .doesNotContain("sample-admin")
                .doesNotContain(new String(Base32.decode(created.secretKey()), StandardCharsets.ISO_8859_1));
    }
}
